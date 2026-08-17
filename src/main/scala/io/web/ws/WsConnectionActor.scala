package io.web.ws

import akka.actor.{Actor, ActorRef}
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.pattern.pipe
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import com.google.firebase.auth.FirebaseAuth
import io.db.DatabaseManager
import io.web.SimulationCache
import io.web.auth.AuthenticatedUser
import spray.json.*
import DefaultJsonProtocol.*
import utils.logging.Logger

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

private case class AuthResult(user: Option[AuthenticatedUser])
case object ConnectionClosed

class WsConnectionActor(outRef: ActorRef)(implicit mat: Materializer) extends Actor {
    implicit val ec: ExecutionContext = context.dispatcher

    private var user: Option[AuthenticatedUser] = None
    private val subscribedRuns = scala.collection.mutable.Set[Long]()

    override def postStop(): Unit =
        subscribedRuns.foreach(WsRegistry.unsubscribe(_, self))

    def receive: Receive = unauthenticated

    private val unauthenticated: Receive = {
        case tm: TextMessage.Strict   => doAuth(tm.text)
        case tm: TextMessage.Streamed => tm.textStream.runWith(Sink.ignore)
        case _: BinaryMessage         =>
        case AuthResult(Some(u))      =>
            user = Some(u)
            context.become(authenticated)
            outRef ! TextMessage(s"""{"type":"auth_ok","userId":"${u.uid}"}""")
        case AuthResult(None)         =>
            outRef ! TextMessage("""{"type":"error","code":"auth_failed"}""")
            context.stop(self)
        case ConnectionClosed         => context.stop(self)
    }

    private val authenticated: Receive = {
        case tm: TextMessage.Strict   => handleControl(tm.text)
        case tm: TextMessage.Streamed => tm.textStream.runWith(Sink.ignore)
        case _: BinaryMessage         =>
        case Dispatch(msg)            => outRef ! msg
        case ConnectionClosed         => context.stop(self)
    }

    private def doAuth(text: String): Unit = {
        Try(text.parseJson.asJsObject.fields) match {
            case Success(fields) if fields.get("type").exists(_.convertTo[String] == "auth") =>
                fields.get("token").map(_.convertTo[String]) match {
                    case Some(token) =>
                        Future {
                            Try(FirebaseAuth.getInstance().verifyIdToken(token)) match {
                                case Success(decoded) =>
                                    val uid   = decoded.getUid
                                    val email = Option(decoded.getEmail).getOrElse("")
                                    val name  = Option(decoded.getName).getOrElse("")
                                    val photo = Option(decoded.getPicture)
                                    val maybeUser = DatabaseManager.createOrUpdateUser(uid, email, name, photo)
                                        .map(u => AuthenticatedUser(uid, email, name, photo, u.roles, u.id))
                                    AuthResult(maybeUser)
                                case Failure(ex) =>
                                    Logger.log(s"WS auth token verification failed: ${ex.getMessage}")
                                    AuthResult(None)
                            }
                        }.pipeTo(self)
                    case None =>
                        outRef ! TextMessage("""{"type":"error","code":"invalid_message"}""")
                }
            case _ =>
                outRef ! TextMessage("""{"type":"error","code":"auth_required"}""")
        }
    }

    private def handleControl(text: String): Unit = {
        Try(text.parseJson.asJsObject.fields) match {
            case Success(fields) =>
                fields.get("type").map(_.convertTo[String]) match {
                    case Some("subscribe") =>
                        fields.get("runId").map(_.convertTo[String].toLong).foreach(doSubscribe)
                    case Some("unsubscribe") =>
                        fields.get("runId").map(_.convertTo[String].toLong).foreach { runId =>
                            WsRegistry.unsubscribe(runId, self)
                            subscribedRuns -= runId
                        }
                    case _ =>
                }
            case Failure(_) =>
        }
    }

    private def doSubscribe(runId: Long): Unit = {
        // Atomically register as subscriber and capture the current event snapshot. Any event
        // dispatched after this call reaches us via Dispatch; any event dispatched before is in
        // the snapshot. Draining it synchronously here keeps the original order at outRef.
        val snapshot = WsRegistry.subscribeAndSnapshot(runId, self)
        subscribedRuns += runId

        if (snapshot.nonEmpty) {
            snapshot.foreach {
                case WsRegistry.TextEvent(json)   => outRef ! TextMessage(json)
                case WsRegistry.BinaryEvent(bytes) => outRef ! BinaryMessage(bytes)
            }
            return
        }

        // No in-memory buffer: either the run is brand-new (no events emitted yet) or its
        // buffer has been evicted. Decide based on DB state.
        DatabaseManager.getRunSummary(runId) match {
            case None =>
                outRef ! TextMessage(s"""{"type":"error","code":"run_not_found","runId":"$runId"}""")
                WsRegistry.unsubscribe(runId, self)
                subscribedRuns -= runId

            case Some(summary) if isTerminalStatus(summary.status) =>
                // Buffer evicted (run is old); fall back to DB-backed replay.
                replayFromDatabase(runId)

            case Some(_) =>
                // Running but nothing emitted yet — live events will arrive via Dispatch.
        }
    }

    private def isTerminalStatus(status: String): Boolean =
        status == "completed" || status == "cancelled" || status == "error"

    private def replayFromDatabase(runId: Long): Unit = {
        val networkIds = SimulationCache.getNetworkIds(runId)
        Future {
            for (networkId <- networkIds) {
                outRef ! TextMessage(s"""{"event":"topology_ready","runId":"$runId","networkId":"$networkId"}""")
                outRef ! TextMessage(s"""{"event":"network_started","runId":"$runId","networkId":"$networkId"}""")
                Try(UUID.fromString(networkId)).foreach { nid =>
                    DatabaseManager.getLastFrameRound(nid).foreach { lastRound =>
                        val frames = DatabaseManager.getFramesInRange(nid, 0, lastRound)
                        if (frames.nonEmpty) outRef ! BinaryMessage(ByteString(frames))
                    }
                }
                SimulationCache.getResults(runId, networkId).foreach { r =>
                    outRef ! TextMessage(
                        s"""{"event":"network_converged","runId":"$runId","networkId":"$networkId","finalRound":${r.finalRound},"consensus":${r.consensus}}""")
                }
            }
            outRef ! TextMessage(s"""{"event":"run_completed","runId":"$runId"}""")
        }
    }
}
