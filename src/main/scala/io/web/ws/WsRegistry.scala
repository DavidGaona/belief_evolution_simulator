package io.web.ws

import akka.actor.ActorRef
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.util.ByteString

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

case class Dispatch(msg: Message)

object WsRegistry {
    sealed trait BufferedEvent
    case class TextEvent(json: String) extends BufferedEvent
    case class BinaryEvent(bytes: ByteString) extends BufferedEvent

    private class RunBuffer {
        val createdAt: Long = System.currentTimeMillis()
        val events: ArrayBuffer[BufferedEvent] = ArrayBuffer.empty
        var bytes: Long = 0L
        var terminalAt: Option[Long] = None
    }

    // Single lock guarding both subscribers and buffers so that subscribe+snapshot is atomic
    // with respect to dispatch: an event is either visible in the snapshot or delivered live,
    // never both, never neither.
    private val lock = new AnyRef
    private val subscribers = mutable.Map[Long, mutable.Set[ActorRef]]()
    private val buffers     = mutable.Map[Long, RunBuffer]()

    // Per-run byte cap. Older events are dropped (drop-head) once exceeded.
    private val maxBytesPerRun: Long = 200L * 1024 * 1024
    // Retention after run reaches terminal state (matches ephemeral frames TTL).
    private val retentionMs: Long = 60L * 60 * 1000
    // Hard upper bound for runs that never reach terminal (crash, abandoned).
    private val maxAgeMs: Long = 4L * 60 * 60 * 1000

    // Atomically register the actor as a live subscriber AND return the current event snapshot.
    // The actor must drain the snapshot synchronously (before returning to its receive loop) so
    // that buffered events are written to the WS sink ahead of any queued Dispatch messages.
    def subscribeAndSnapshot(runId: Long, actor: ActorRef): Seq[BufferedEvent] = lock.synchronized {
        subscribers.getOrElseUpdate(runId, mutable.Set.empty) += actor
        buffers.get(runId).map(_.events.toList).getOrElse(Nil)
    }

    def unsubscribe(runId: Long, actor: ActorRef): Unit = lock.synchronized {
        subscribers.get(runId).foreach { set =>
            set -= actor
            if (set.isEmpty) subscribers -= runId
        }
    }

    def dispatchBinary(runId: Long, bytes: ByteString): Unit = {
        val targets = lock.synchronized {
            appendUnlocked(runId, BinaryEvent(bytes), bytes.length.toLong)
            subscribers.get(runId).map(_.toList).getOrElse(Nil)
        }
        if (targets.nonEmpty) {
            val msg = BinaryMessage(bytes)
            targets.foreach(_ ! Dispatch(msg))
        }
    }

    def dispatchText(runId: Long, json: String): Unit = {
        val targets = lock.synchronized {
            appendUnlocked(runId, TextEvent(json), json.length.toLong)
            subscribers.get(runId).map(_.toList).getOrElse(Nil)
        }
        if (targets.nonEmpty) {
            val msg = TextMessage(json)
            targets.foreach(_ ! Dispatch(msg))
        }
    }

    // Mark a run terminal so its buffer becomes eligible for cleanup after retention.
    def markTerminal(runId: Long): Unit = lock.synchronized {
        val buf = buffers.getOrElseUpdate(runId, new RunBuffer)
        if (buf.terminalAt.isEmpty) buf.terminalAt = Some(System.currentTimeMillis())
    }

    def cleanup(): Int = lock.synchronized {
        val now = System.currentTimeMillis()
        val toRemove = buffers.collect {
            case (runId, b) if b.terminalAt.exists(_ + retentionMs < now) || (b.createdAt + maxAgeMs < now) => runId
        }.toList
        toRemove.foreach(buffers -= _)
        toRemove.size
    }

    private def appendUnlocked(runId: Long, event: BufferedEvent, size: Long): Unit = {
        val buf = buffers.getOrElseUpdate(runId, new RunBuffer)
        buf.events += event
        buf.bytes += size
        while (buf.bytes > maxBytesPerRun && buf.events.nonEmpty) {
            val head = buf.events.remove(0)
            val headSize = head match {
                case TextEvent(s)   => s.length.toLong
                case BinaryEvent(b) => b.length.toLong
            }
            buf.bytes -= headSize
        }
    }
}
