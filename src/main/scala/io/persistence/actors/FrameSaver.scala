package io.persistence.actors

import akka.actor.{Actor, ActorContext, ActorRef, Props}
import core.simulation.actors.SaveRemainingData
import io.db.DatabaseManager
import utils.logging.Logger

import java.io.{ByteArrayOutputStream, DataOutputStream}
import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContextExecutor, Future}

// One slice of a WS round frame (one AgentProcessor's emission), to persist into network_frames.
case class PersistFrame(networkId: UUID, round: Int, startsAt: Int, frame: Array[Byte], runId: Long)

// Eagerly flush pending rows and notify replyTo with FlushDone once durable (or immediately if
// nothing is pending). Used by Monitor to ensure /frames is queryable before announcing
// run_completed.
case class FlushNow(runId: Long, replyTo: ActorRef)
case class FlushDone(runId: Long)

object FrameSaverRouter {
    @volatile private var saver: ActorRef = null

    def init(context: ActorContext): Unit = {
        if (saver == null) saver = context.actorOf(Props(new FrameSaver), "FrameSaver")
    }

    def getRoute: ActorRef = saver

    def saveRemainingData(): Unit = if (saver != null) saver ! SaveRemainingData

    // If the saver actor was never initialized (skipDatabase etc.) reply synchronously so the
    // caller never deadlocks waiting for FlushDone.
    def flushNow(runId: Long, replyTo: ActorRef): Unit = {
        if (saver != null) saver ! FlushNow(runId, replyTo)
        else replyTo ! FlushDone(runId)
    }
}

class FrameSaver extends Actor {
    private case object CheckActivity
    private case class FlushCompleted(replyTo: ActorRef, runId: Long)

    private val flushThreshold = 1024
    private val idleFlushMs    = 5_000L

    private var stream      = new FrameStreamBuffer(64_000)
    private var pendingRows = 0
    private var lastReceiveAt = System.currentTimeMillis()

    implicit val ec: ExecutionContextExecutor = context.dispatcher
    context.system.scheduler.scheduleWithFixedDelay(
        initialDelay = 5.seconds,
        delay        = 5.seconds,
        receiver     = self,
        message      = CheckActivity
    )

    def receive: Receive = {
        case PersistFrame(networkId, round, startsAt, frame, runId) =>
            stream.addRow(networkId, round, startsAt, frame, runId)
            pendingRows += 1
            lastReceiveAt = System.currentTimeMillis()
            if (pendingRows >= flushThreshold) flush()

        case SaveRemainingData =>
            if (pendingRows > 0) flush()

        case FlushNow(runId, replyTo) =>
            if (pendingRows == 0) {
                replyTo ! FlushDone(runId)
            } else {
                val data = stream.finish()
                val count = pendingRows
                stream = new FrameStreamBuffer(64_000)
                pendingRows = 0
                Future {
                    try DatabaseManager.insertFrameBatch(data)
                    catch { case e: Exception => Logger.logError(s"FrameSaver flush failed ($count rows): ${e.getMessage}") }
                }.onComplete { _ =>
                    self ! FlushCompleted(replyTo, runId)
                }
            }

        case FlushCompleted(replyTo, runId) =>
            replyTo ! FlushDone(runId)

        case CheckActivity =>
            val idle = System.currentTimeMillis() - lastReceiveAt
            if (pendingRows > 0 && idle >= idleFlushMs) flush()
    }

    private def flush(): Unit = {
        if (pendingRows == 0) return
        val data = stream.finish()
        val count = pendingRows
        Future {
            try DatabaseManager.insertFrameBatch(data)
            catch { case e: Exception => Logger.logError(s"FrameSaver flush failed ($count rows): ${e.getMessage}") }
        }
        stream = new FrameStreamBuffer(64_000)
        pendingRows = 0
    }
}

// PGCOPY binary writer for public.network_frames(network_id, round, starts_at, frame).
class FrameStreamBuffer(initialSize: Int) {
    private val buffer  = new ByteArrayOutputStream(initialSize)
    private val dataOut = new DataOutputStream(buffer)
    writeHeader()

    private def writeHeader(): Unit = {
        dataOut.writeBytes("PGCOPY\n")
        dataOut.write(0xff); dataOut.write(0x0d); dataOut.write(0x0a); dataOut.write(0x00)
        dataOut.writeInt(0)
        dataOut.writeInt(0)
    }

    def addRow(networkId: UUID, round: Int, startsAt: Int, frame: Array[Byte], runId: Long): Unit = {
        dataOut.writeShort(5)

        dataOut.writeInt(16)
        dataOut.writeLong(networkId.getMostSignificantBits)
        dataOut.writeLong(networkId.getLeastSignificantBits)

        dataOut.writeInt(4)
        dataOut.writeInt(round)

        dataOut.writeInt(4)
        dataOut.writeInt(startsAt)

        dataOut.writeInt(frame.length)
        dataOut.write(frame)

        dataOut.writeInt(8)
        dataOut.writeLong(runId)
    }

    def finish(): Array[Byte] = {
        dataOut.writeShort(-1)
        dataOut.flush()
        buffer.toByteArray
    }
}
