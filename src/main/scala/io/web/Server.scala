package io.web

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods.{DELETE, GET, OPTIONS, POST, PUT}
import akka.http.scaladsl.model.headers.*
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.http.scaladsl.model.{ContentType, ContentTypes, HttpCharsets, HttpEntity, HttpResponse, MediaType, MediaTypes, StatusCodes}
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.{Directive0, Route}
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshaller}
import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.ByteString
import io.web.ws.{ConnectionClosed, Dispatch, WsConnectionActor, WsRegistry}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import core.model.agent.behavior.bias.CognitiveBiases
import core.model.agent.behavior.bias.CognitiveBiases.Bias
import core.model.agent.behavior.silence.SilenceEffects.SilenceEffect
import core.model.agent.behavior.silence.{SilenceEffects, SilenceStrategies}
import core.model.agent.behavior.silence.SilenceStrategies.SilenceStrategy
import core.simulation.actors.{AddNetworks, CancelRun, CoevolutionConfig, RunCustomNetwork}
import core.simulation.config.*
import core.simulation.config.SaveModes.SaveMode
import utils.logging.Logger
import io.db.DatabaseManager
import io.web.auth.ErrorResponses.*
import io.web.auth.FirebaseAuthDirective.{authenticate, requireAdmin}
import utils.datastructures.SnowflakeID
import utils.rng.distributions.Uniform
import spray.json.*
import DefaultJsonProtocol.*

import java.nio.{ByteBuffer, ByteOrder}
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

// ── JSON shapes for /simulations/* requests ──────────────────────────────────

case class AgentTypeReq(silenceStrategy: Int, silenceEffect: Int, count: Int)
case class BiasTypeReq(biasType: Int, count: Int)
case class CoevolutionReq(
    pBreak: Float,
    pCreate: Float,
    rewiringStrategy: Int,
    assortStop: Option[Float] = None,
    fragStop: Option[Boolean] = None
)
case class GeneratedRunReq(
    numberOfNetworks: Int,
    density: Int,
    iterationLimit: Int,
    stopThreshold: Float,
    seed: Option[Long],
    saveMode: Int,
    agentTypes: Seq[AgentTypeReq],
    biasTypes: Seq[BiasTypeReq],
    coevolution: Option[CoevolutionReq] = None,
    persistFrames: Option[Boolean] = None,
    frameRetention: Option[String] = None
)

case class AgentReq(
    name: String,
    belief: Float,
    toleranceRadius: Float,
    toleranceOffset: Float,
    silenceStrategy: Int,
    silenceEffect: Int
)
case class EdgeReq(source: String, target: String, influence: Float, bias: Int)
case class CustomRunReq(
    name: String,
    iterationLimit: Int,
    stopThreshold: Float,
    saveMode: Int,
    agents: Seq[AgentReq],
    edges: Seq[EdgeReq],
    coevolution: Option[CoevolutionReq] = None,
    persistFrames: Option[Boolean] = None,
    frameRetention: Option[String] = None
)

object SimulationJsonProtocol {
    implicit val agentTypeReqFmt: RootJsonFormat[AgentTypeReq]       = jsonFormat3(AgentTypeReq.apply)
    implicit val biasTypeReqFmt: RootJsonFormat[BiasTypeReq]         = jsonFormat2(BiasTypeReq.apply)
    implicit val coevolutionReqFmt: RootJsonFormat[CoevolutionReq]   = jsonFormat5(CoevolutionReq.apply)
    implicit val generatedRunReqFmt: RootJsonFormat[GeneratedRunReq] = jsonFormat11(GeneratedRunReq.apply)
    implicit val agentReqFmt: RootJsonFormat[AgentReq]               = jsonFormat6(AgentReq.apply)
    implicit val edgeReqFmt: RootJsonFormat[EdgeReq]                 = jsonFormat4(EdgeReq.apply)
    implicit val customRunReqFmt: RootJsonFormat[CustomRunReq]       = jsonFormat9(CustomRunReq.apply)
}

// Data containers:
case class CustomRunInfo(
    runID: Long,
    stopThreshold: Float,
    iterationLimit: Int,
    saveMode: SaveMode,
    networkName: String,
    agentBeliefs: Array[Float],
    agentToleranceRadii: Array[Float],
    agentToleranceOffsets: Array[Float],
    agentSilenceStrategy: Array[SilenceStrategy],
    agentSilenceEffect: Array[SilenceEffect],
    agentNames: Array[String],
    indexOffset: Array[Int],
    target: Array[Int],
    influences: Array[Float],
    bias: Array[Bias],
    coevolutionConfig: Option[CoevolutionConfig] = None,
    persistFrames: Boolean = false
)

/**
 * Contains the data to save to the database for custom agents table
 * @param belief Initial beliefs of the agents
 * @param radius Tolerance radius of the agents
 * @param offset Tolerance offset of the agents
 * @param majorityThreshold Majority thresholds of the agents
 * @param confidence Initial confidences of the agents
 * @param silenceStrategy Silence strategies of the agents
 * @param silenceEffect Silence effects of the agents 
 * @param name Names of the agents
 */
case class CustomAgentsData(
    belief: Array[Float],
    radius: Array[Float],
    offset: Array[Float],
    silenceStrategy: Array[SilenceStrategy],
    silenceEffect: Array[SilenceEffect],
    name: Array[String],
    majorityThreshold: Option[mutable.Map[Int, Float]],
    confidence: Option[mutable.Map[Int, Float]],
)

/**
 * Contains the data to save to the database for the custom neighbors table
 * @param source The agents that influence
 * @param target The agents that are influenced
 * @param influence The influences
 * @param bias The cognitive biases the agents react with
 */
case class CustomNeighborsData(
    source: Array[Int],
    target: Array[Int],
    influence: Array[Float],
    bias: Array[Bias],
)

final case class Payload(data: Array[Byte])

object BinaryProtocol {
    implicit def payloadUnmarshaller: FromEntityUnmarshaller[Payload] =
        Unmarshaller.byteStringUnmarshaller
          .mapWithInput { (_, bytes) =>
              Payload(bytes.toArray)
          }
}

object Server {
    
    import BinaryProtocol.*
    
    private var initialized = false
    private var system: Option[ActorSystem] = None
    private var monitor: Option[ActorRef] = None
    
    def initialize(actorSystem: ActorSystem, monitor: ActorRef): Unit = {
        if (initialized) return
        
        system = Some(actorSystem)
        this.monitor = Some(monitor)
        
        implicit val systenImplicit: ActorSystem = actorSystem
        implicit val executionContext: ExecutionContextExecutor = actorSystem.dispatcher
        implicit val materializer: Materializer = Materializer(actorSystem)
        
        val serverHost = sys.env.getOrElse("SERVER_HOST", "0.0.0.0")
        val serverPort = sys.env.getOrElse("SERVER_PORT", "8080").toInt

        val corsResponseHeaders = List(
            `Access-Control-Allow-Origin`.*,
            `Access-Control-Allow-Methods`(POST, GET, PUT, DELETE, OPTIONS),
            `Access-Control-Allow-Headers`("Content-Type", "Authorization", "X-Requested-With", "Accept", "Origin"),
            `Access-Control-Max-Age`(86400),
            `Access-Control-Allow-Credentials`(false)
        )
        
        def addCorsHeaders: Directive0 = {
            respondWithHeaders(corsResponseHeaders)
        }
        
        val corsHandler: Route = addCorsHeaders {
            options {
                complete(HttpResponse(StatusCodes.OK))
            }
        }
        
        val webSocketRoute: Route = addCorsHeaders {
            path("ws") {
                get {
                    val (outRef, outSource) = Source.actorRef[Message](
                        completionMatcher = PartialFunction.empty,
                        failureMatcher    = PartialFunction.empty,
                        bufferSize        = 256,
                        overflowStrategy  = OverflowStrategy.dropHead
                    ).preMaterialize()
                    val connActor = system.get.actorOf(
                        akka.actor.Props(new WsConnectionActor(outRef)))
                    val inSink = Sink.actorRef[Message](
                        connActor, ConnectionClosed, _ => ConnectionClosed)
                    handleWebSocketMessages(Flow.fromSinkAndSource(inSink, outSource))
                }
            }
        }
        
        val apiRoute: Route = addCorsHeaders {
            pathPrefix("run") {
                post {
                    withDeprecation("/simulations/generated") {
                        optionalHeaderValueByName("User-Agent") { ua =>
                            optionalHeaderValueByName("X-Forwarded-For") { xff =>
                                logLegacy("/run", ua, xff)
                                parameters(
                                    "pBreak".as[Float].?, "pCreate".as[Float].?, "rewiring".as[Int].?,
                                    "assortStop".as[Float].?, "fragStop".as[Boolean].?
                                ) { (pBreak, pCreate, rewiring, assortStop, fragStop) =>
                                    entity(as[Payload]) { payload =>
                                        val config = (pBreak, pCreate, rewiring) match {
                                            case (Some(b), Some(c), Some(r)) => Some(CoevolutionConfig(
                                                b, c, r,
                                                assortStop.getOrElse(1.1f),
                                                fragStop.getOrElse(false)
                                            ))
                                            case _ => None
                                        }
                                        val runIdStr = parseGeneratedRun(payload.data, config)
                                        complete(runIdStr)
                                    }
                                }
                            }
                        }
                    }
                }
            } ~ pathPrefix("custom") {
                post {
                    withDeprecation("/simulations/custom") {
                        optionalHeaderValueByName("User-Agent") { ua =>
                            optionalHeaderValueByName("X-Forwarded-For") { xff =>
                                logLegacy("/custom", ua, xff)
                                parameters(
                                    "pBreak".as[Float].?, "pCreate".as[Float].?, "rewiring".as[Int].?,
                                    "assortStop".as[Float].?, "fragStop".as[Boolean].?
                                ) { (pBreak, pCreate, rewiring, assortStop, fragStop) =>
                                    entity(as[Payload]) { payload =>
                                        val config = (pBreak, pCreate, rewiring) match {
                                            case (Some(b), Some(c), Some(r)) => Some(CoevolutionConfig(
                                                b, c, r,
                                                assortStop.getOrElse(1.1f),
                                                fragStop.getOrElse(false)
                                            ))
                                            case _ => None
                                        }
                                        val runId = parseCustomRun(payload.data, None, config = config)
                                        complete(runId.toString)
                                    }
                                }
                            }
                        }
                    }
                }
            } ~ pathPrefix("neighbors") {
                get {
                    withDeprecation("/simulations/custom") {
                        optionalHeaderValueByName("User-Agent") { ua =>
                            optionalHeaderValueByName("X-Forwarded-For") { xff =>
                                logLegacy("/neighbors", ua, xff)
                                entity(as[Payload]) { payload =>
                                    complete(parseCustomRun(payload.data).toString)
                                }
                            }
                        }
                    }
                }
            }
        }
        
        val userRoutes: Route = addCorsHeaders {
            pathPrefix("api" / "users") {
                // POST /api/users/sync — authenticated; uid comes from token, not body
                path("sync") {
                    post {
                        authenticate { authUser =>
                            val limits = DatabaseManager.getUsageLimits(authUser.roles)
                            val responseJson = s"""{
                              |  "uid": "${authUser.uid}",
                              |  "email": "${authUser.email}",
                              |  "name": "${authUser.name}",
                              |  "photo": ${authUser.photo.map(p => s""""$p"""").getOrElse("null")},
                              |  "roles": [${authUser.roles.map(r => s""""$r"""").mkString(",")}],
                              |  "usageLimits": {
                              |    "maxAgents": ${if (limits.maxAgents == Int.MaxValue) "null" else limits.maxAgents},
                              |    "maxIterations": ${if (limits.maxIterations == Int.MaxValue) "null" else limits.maxIterations},
                              |    "densityFactor": ${limits.densityFactor}
                              |  },
                              |  "deactivated": false
                              |}""".stripMargin
                            complete(StatusCodes.OK ->
                                HttpEntity(ContentTypes.`application/json`, responseJson))
                        }
                    }
                } ~
                // GET /api/users/info/{uid} — owner or admin only
                path("info" / Segment) { targetUid =>
                    get {
                        authenticate { authUser =>
                            if (authUser.uid == targetUid || authUser.isAdmin) {
                                DatabaseManager.getUserByFirebaseUid(targetUid) match {
                                    case Some(user) =>
                                        val rolesJson = user.roles.map(r => s""""$r"""").mkString(",")
                                        complete(StatusCodes.OK -> HttpEntity(ContentTypes.`application/json`,
                                            s"""{"uid":"${user.firebaseUid}","email":"${user.email}","name":"${user.name}","roles":[$rolesJson],"deactivated":${user.deactivated}}"""))
                                    case None =>
                                        complete(StatusCodes.NotFound ->
                                            errorJson("not_found", "User not found"))
                                }
                            } else {
                                complete(StatusCodes.Forbidden ->
                                    errorJson("forbidden", "Cannot access another user's info"))
                            }
                        }
                    }
                } ~
                // PUT /api/users/role/{uid}/{role} — admin only
                path("role" / Segment / Segment) { (targetUid, newRole) =>
                    put {
                        requireAdmin { _ =>
                            val validRoles = Set("Administrator", "Researcher", "BaseUser", "Guest")
                            if (!validRoles.contains(newRole)) {
                                complete(StatusCodes.BadRequest ->
                                    errorJson("invalid_body", s"Invalid role: $newRole"))
                            } else if (DatabaseManager.addUserRole(targetUid, newRole)) {
                                complete(StatusCodes.OK -> HttpEntity(ContentTypes.`application/json`,
                                    s"""{"uid":"$targetUid","role":"$newRole","action":"added"}"""))
                            } else {
                                complete(StatusCodes.InternalServerError ->
                                    errorJson("internal_error", "Failed to update role"))
                            }
                        }
                    }
                }
            }
        }
        
        // Periodic cleanup every 60 seconds
        import scala.concurrent.duration.*
        actorSystem.scheduler.scheduleWithFixedDelay(60.seconds, 60.seconds)(() => {
            SimulationCache.cleanup()
            WsRegistry.cleanup()
        })(actorSystem.dispatcher)

        // Ephemeral frame cleanup runs less often (storage cost, not correctness).
        // Grace window: 60 minutes after a run leaves 'running'.
        actorSystem.scheduler.scheduleWithFixedDelay(5.minutes, 30.minutes)(() => {
            val deleted = DatabaseManager.cleanupEphemeralFrames(60)
            if (deleted > 0) Logger.log(s"cleanupEphemeralFrames removed $deleted rows")
        })(actorSystem.dispatcher)

        import SimulationJsonProtocol.*

        def simCreatedJson(runId: Long, networkCount: Int): String =
            s"""{
               |  "runId": "$runId",
               |  "status": "running",
               |  "networkCount": $networkCount,
               |  "wsUrl": "/ws"
               |}""".stripMargin

        def runSummaryJson(r: DatabaseManager.RunSummary): String =
            s"""{
               |  "id": "${r.id}",
               |  "type": "${r.runType}",
               |  "name": ${r.name.map(n => s""""$n"""").getOrElse("null")},
               |  "networkCount": ${r.networkCount},
               |  "iterationLimit": ${r.iterationLimit},
               |  "stopThreshold": ${r.stopThreshold},
               |  "createdAt": "${r.createdAt}",
               |  "status": "${r.status}"
               |}""".stripMargin

        def jsonOk(body: String): HttpResponse =
            HttpResponse(StatusCodes.OK,
                entity = HttpEntity(ContentTypes.`application/json`, body))

        // Resolves frameRetention: only Administrators can request "persistent".
        // Returns Right(retention) on success, Left(errorResponse) on rejection.
        def resolveFrameRetention(requested: Option[String], isAdmin: Boolean): Either[HttpResponse, String] =
            requested.map(_.toLowerCase) match {
                case None | Some("ephemeral") => Right("ephemeral")
                case Some("persistent") if isAdmin => Right("persistent")
                case Some("persistent") =>
                    Left(HttpResponse(StatusCodes.Forbidden,
                        entity = errorJson("forbidden", "frameRetention=persistent requires Administrator role")))
                case Some(other) =>
                    Left(HttpResponse(StatusCodes.BadRequest,
                        entity = errorJson("invalid_body", s"Invalid frameRetention: $other")))
            }

        val simulationRoutes: Route = addCorsHeaders {
            pathPrefix("simulations") {
                // POST /simulations/generated  — JSON body
                path("generated") {
                    post {
                        authenticate { authUser =>
                            entity(as[GeneratedRunReq]) { req =>
                                val limits = DatabaseManager.getUsageLimits(authUser.roles)
                                val agentsPerNetwork = req.agentTypes.map(_.count).sum
                                if (agentsPerNetwork > limits.maxAgents)
                                    complete(StatusCodes.UnprocessableEntity ->
                                        usageLimitJson("Agent count exceeds your limit", limits.maxAgents, agentsPerNetwork))
                                else if (req.iterationLimit > limits.maxIterations)
                                    complete(StatusCodes.UnprocessableEntity ->
                                        usageLimitJson("Iteration limit exceeds your limit", limits.maxIterations, req.iterationLimit))
                                else resolveFrameRetention(req.frameRetention, authUser.isAdmin) match {
                                    case Left(resp) => complete(resp)
                                    case Right(retention) =>
                                        val persistFrames = req.persistFrames.getOrElse(false)
                                        val effectiveDensity = (req.density * limits.densityFactor).toInt
                                        val seed = req.seed.getOrElse(-1L) match {
                                            case -1L => System.currentTimeMillis() ^ System.nanoTime()
                                            case s   => s
                                        }
                                        val agentTypesArr = req.agentTypes.map { a =>
                                            (SilenceStrategies.fromByte(a.silenceStrategy.toByte),
                                             SilenceEffects.fromByte(a.silenceEffect.toByte),
                                             a.count)
                                        }.toArray
                                        val biasArr = req.biasTypes.map { b =>
                                            (CognitiveBiases.fromByte(b.biasType.toByte), b.count)
                                        }.toArray
                                        val runId = executeGeneratedRun(
                                            seed, req.saveMode.toByte, req.numberOfNetworks,
                                            effectiveDensity, req.iterationLimit, req.stopThreshold,
                                            agentTypesArr, biasArr,
                                            agentsPerNetwork,
                                            userId = Some(authUser.dbUserId),
                                            coevolutionConfig = req.coevolution.map(c => CoevolutionConfig(
                                                c.pBreak, c.pCreate, c.rewiringStrategy,
                                                c.assortStop.getOrElse(1.1f),
                                                c.fragStop.getOrElse(false)
                                            )),
                                            persistFrames = persistFrames,
                                            frameRetention = retention
                                        )
                                        complete(jsonOk(simCreatedJson(runId, req.numberOfNetworks)))
                                }
                            }
                        }
                    }
                } ~
                // POST /simulations/custom — binary or JSON based on Content-Type
                path("custom") {
                    post {
                        authenticate { authUser =>
                            extractRequest { req =>
                                val ct = req.entity.contentType.mediaType.toString
                                if (ct.contains("octet-stream")) {
                                    // Binary payload has no persistFrames flag — defaults to false/ephemeral.
                                    entity(as[Payload]) { payload =>
                                        val runId = parseCustomRun(payload.data, Some(authUser.dbUserId))
                                        complete(jsonOk(simCreatedJson(runId, 1)))
                                    }
                                } else {
                                    entity(as[CustomRunReq]) { runReq =>
                                        val limits = DatabaseManager.getUsageLimits(authUser.roles)
                                        val agentCount = runReq.agents.size
                                        if (agentCount > limits.maxAgents)
                                            complete(StatusCodes.UnprocessableEntity ->
                                                usageLimitJson("Agent count exceeds your limit", limits.maxAgents, agentCount))
                                        else if (runReq.iterationLimit > limits.maxIterations)
                                            complete(StatusCodes.UnprocessableEntity ->
                                                usageLimitJson("Iteration limit exceeds your limit", limits.maxIterations, runReq.iterationLimit))
                                        else resolveFrameRetention(runReq.frameRetention, authUser.isAdmin) match {
                                            case Left(resp) => complete(resp)
                                            case Right(retention) =>
                                                val coevCfg = runReq.coevolution.map(c => CoevolutionConfig(
                                                    c.pBreak, c.pCreate, c.rewiringStrategy,
                                                    c.assortStop.getOrElse(1.1f),
                                                    c.fragStop.getOrElse(false)
                                                ))
                                                val runId = executeCustomRunFromJson(
                                                    runReq, Some(authUser.dbUserId),
                                                    coevolutionConfig = coevCfg,
                                                    persistFrames = runReq.persistFrames.getOrElse(false),
                                                    frameRetention = retention
                                                )
                                                complete(jsonOk(simCreatedJson(runId, 1)))
                                        }
                                    }
                                }
                            }
                        }
                    }
                } ~
                // GET /simulations/mine — paginated list for authenticated user
                path("mine") {
                    get {
                        authenticate { authUser =>
                            parameters("limit".as[Int].withDefault(20), "offset".as[Int].withDefault(0)) { (limit, offset) =>
                                val runs = DatabaseManager.getRunsForUser(authUser.dbUserId, limit, offset)
                                val items = runs.map(runSummaryJson).mkString(",")
                                complete(jsonOk(s"""{"runs":[$items],"limit":$limit,"offset":$offset}"""))
                            }
                        }
                    }
                } ~
                // GET /simulations — admin: all runs
                pathEndOrSingleSlash {
                    get {
                        requireAdmin { _ =>
                            parameters("limit".as[Int].withDefault(20), "offset".as[Int].withDefault(0)) { (limit, offset) =>
                                val runs = DatabaseManager.getAllRuns(limit, offset)
                                val items = runs.map(runSummaryJson).mkString(",")
                                complete(jsonOk(s"""{"runs":[$items],"limit":$limit,"offset":$offset}"""))
                            }
                        }
                    }
                } ~
                // Routes by runId
                pathPrefix(LongNumber) { runId =>
                    // GET /simulations/{runId}
                    pathEndOrSingleSlash {
                        get {
                            authenticate { authUser =>
                                DatabaseManager.getRunSummary(runId) match {
                                    case None => complete(StatusCodes.NotFound ->
                                        errorJson("not_found", "Run not found"))
                                    case Some(run) =>
                                        if (run.userId.contains(authUser.dbUserId) || authUser.isAdmin)
                                            complete(jsonOk(runSummaryJson(run)))
                                        else
                                            complete(StatusCodes.Forbidden ->
                                                errorJson("forbidden", "Access denied"))
                                }
                            }
                        }
                    } ~
                    // DELETE /simulations/{runId}
                    delete {
                        authenticate { authUser =>
                            DatabaseManager.getRunOwner(runId) match {
                                case None => complete(StatusCodes.NotFound ->
                                    errorJson("not_found", "Run not found"))
                                case Some(ownerId) =>
                                    if (ownerId == authUser.dbUserId || authUser.isAdmin) {
                                        DatabaseManager.setRunStatus(runId, "cancelled")
                                        monitor ! CancelRun(runId)
                                        complete(jsonOk(s"""{"runId":"$runId","cancelled":true}"""))
                                    } else {
                                        complete(StatusCodes.Forbidden ->
                                            errorJson("forbidden", "Access denied"))
                                    }
                            }
                        }
                    } ~
                    // GET /simulations/{runId}/networks
                    path("networks") {
                        get {
                            authenticate { authUser =>
                                DatabaseManager.getRunOwner(runId) match {
                                    case None => complete(StatusCodes.NotFound ->
                                        errorJson("not_found", "Run not found"))
                                    case Some(ownerId) if ownerId != authUser.dbUserId && !authUser.isAdmin =>
                                        complete(StatusCodes.Forbidden ->
                                            errorJson("forbidden", "Access denied"))
                                    case _ =>
                                        val ids = SimulationCache.getNetworkIds(runId)
                                        val items = ids.map(id => s""""$id"""").mkString(",")
                                        complete(jsonOk(s"""{"runId":"$runId","networks":[$items]}"""))
                                }
                            }
                        }
                    } ~
                    // GET /simulations/{runId}/networks/{networkId}/topology
                    // GET /simulations/{runId}/networks/{networkId}/results
                    pathPrefix("networks" / Segment) { networkId =>
                        authenticate { authUser =>
                            DatabaseManager.getRunOwner(runId) match {
                                case None => complete(StatusCodes.NotFound ->
                                    errorJson("not_found", "Run not found"))
                                case Some(ownerId) if ownerId != authUser.dbUserId && !authUser.isAdmin =>
                                    complete(StatusCodes.Forbidden ->
                                        errorJson("forbidden", "Access denied"))
                                case _ =>
                                    path("topology") {
                                        get {
                                            parameters(
                                                "agentOffset".as[Int].withDefault(0),
                                                "agentLimit".as[Int].withDefault(500),
                                                "edgeOffset".as[Int].withDefault(0),
                                                "edgeLimit".as[Int].withDefault(1000)
                                            ) { (aOff, aLim, eOff, eLim) =>
                                                SimulationCache.getTopology(runId, networkId) match {
                                                    case None => complete(StatusCodes.Accepted ->
                                                        errorJson("not_ready", "Topology not yet available"))
                                                    case Some(snap) =>
                                                        complete(jsonOk(topologyPageJson(snap, runId, networkId, aOff, aLim, eOff, eLim)))
                                                }
                                            }
                                        }
                                    } ~
                                    path("results") {
                                        get {
                                            parameters(
                                                "offset".as[Int].withDefault(0),
                                                "limit".as[Int].withDefault(500)
                                            ) { (off, lim) =>
                                                SimulationCache.getResults(runId, networkId) match {
                                                    case None => complete(StatusCodes.Accepted ->
                                                        errorJson("not_ready", "Results not yet available — run may still be in progress"))
                                                    case Some(snap) =>
                                                        complete(jsonOk(resultsPageJson(snap, runId, networkId, off, lim)))
                                                }
                                            }
                                        }
                                    } ~
                                    path("topology" / "final") {
                                        get {
                                            parameters(
                                                "agentOffset".as[Int].withDefault(0),
                                                "agentLimit".as[Int].withDefault(500),
                                                "edgeOffset".as[Int].withDefault(0),
                                                "edgeLimit".as[Int].withDefault(1000)
                                            ) { (aOff, aLim, eOff, eLim) =>
                                                SimulationCache.getFinalTopology(runId, networkId) match {
                                                    case None => complete(StatusCodes.NotFound ->
                                                        errorJson("not_ready", "Final topology not yet available — run may still be in progress"))
                                                    case Some(snap) =>
                                                        complete(jsonOk(topologyPageJson(snap, runId, networkId, aOff, aLim, eOff, eLim)))
                                                }
                                            }
                                        }
                                    } ~
                                    // GET /simulations/{runId}/networks/{networkId}/frames
                                    //   ?round=N           single round
                                    //   ?from=A&to=B       range (capped at 1000 rounds)
                                    //   ?round=last        most recent persisted round
                                    // Body is the WS on-the-wire format (concatenated slices).
                                    path("frames") {
                                        get {
                                            parameters(
                                                "round".?,
                                                "from".as[Int].?,
                                                "to".as[Int].?
                                            ) { (roundParam, fromOpt, toOpt) =>
                                                val networkUuid = try Some(java.util.UUID.fromString(networkId))
                                                                  catch { case _: IllegalArgumentException => None }
                                                networkUuid match {
                                                    case None => complete(StatusCodes.BadRequest ->
                                                        errorJson("invalid_body", "Invalid networkId UUID"))
                                                    case Some(nid) =>
                                                        val range: Either[String, (Int, Int)] = roundParam match {
                                                            case Some("last") =>
                                                                DatabaseManager.getLastFrameRound(nid) match {
                                                                    case Some(r) => Right((r, r))
                                                                    case None    => Left("no_frames")
                                                                }
                                                            case Some(r) =>
                                                                try { val v = r.toInt; Right((v, v)) }
                                                                catch { case _: NumberFormatException => Left("invalid_round") }
                                                            case None =>
                                                                (fromOpt, toOpt) match {
                                                                    case (Some(f), Some(t)) if f <= t => Right((f, t))
                                                                    case _ => Left("missing_range")
                                                                }
                                                        }
                                                        range match {
                                                            case Left("no_frames") =>
                                                                complete(StatusCodes.NotFound ->
                                                                    errorJson("frames_not_persisted",
                                                                        "No frames stored for this network (persistFrames was false, or ephemeral frames already expired)"))
                                                            case Left("invalid_round") =>
                                                                complete(StatusCodes.BadRequest ->
                                                                    errorJson("invalid_body", "round must be an integer or 'last'"))
                                                            case Left(_) =>
                                                                complete(StatusCodes.BadRequest ->
                                                                    errorJson("invalid_body", "Provide ?round=N|last or ?from=A&to=B"))
                                                            case Right((fromR, toR)) =>
                                                                val span = toR - fromR + 1
                                                                if (span > 1000)
                                                                    complete(StatusCodes.BadRequest ->
                                                                        errorJson("range_too_large", "Frame range capped at 1000 rounds per request"))
                                                                else {
                                                                    val bytes = DatabaseManager.getFramesInRange(nid, fromR, toR)
                                                                    if (bytes.isEmpty)
                                                                        complete(StatusCodes.NotFound ->
                                                                            errorJson("frames_not_persisted",
                                                                                "No frames stored in this range (persistFrames was false, or ephemeral frames already expired)"))
                                                                    else
                                                                        complete(HttpResponse(StatusCodes.OK,
                                                                            entity = HttpEntity(ContentTypes.`application/octet-stream`, bytes)))
                                                                }
                                                        }
                                                }
                                            }
                                        }
                                    }
                            }
                        }
                    } ~
                    // POST /simulations/{runId}/ws-ticket — issue a new ticket for reconnections
                    path("ws-ticket") {
                        post {
                            authenticate { authUser =>
                                DatabaseManager.getRunOwner(runId) match {
                                    case None => complete(StatusCodes.NotFound ->
                                        errorJson("not_found", "Run not found"))
                                    case Some(ownerId) =>
                                        if (ownerId == authUser.dbUserId || authUser.isAdmin) {
                                            val ticket = TicketStore.issue(runId, authUser.uid)
                                            complete(jsonOk(s"""{"wsTicket":"$ticket"}"""))
                                        } else {
                                            complete(StatusCodes.Forbidden ->
                                                errorJson("forbidden", "Access denied"))
                                        }
                                }
                            }
                        }
                    }
                }
            }
        }

        val openapiYaml: String = scala.io.Source.fromResource("openapi.yaml").mkString

        val docsRoute: Route = addCorsHeaders {
            path("openapi.yaml") {
                get {
                    complete(HttpEntity(
                        ContentType(MediaType.applicationWithFixedCharset("x-yaml", HttpCharsets.`UTF-8`)),
                        openapiYaml
                    ))
                }
            } ~
            path("docs") {
                get {
                    complete(HttpEntity(ContentTypes.`text/html(UTF-8)`,
                        s"""<!DOCTYPE html>
                           |<html>
                           |<head>
                           |  <title>BES API Docs</title>
                           |  <meta charset="utf-8"/>
                           |  <meta name="viewport" content="width=device-width, initial-scale=1">
                           |  <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css"/>
                           |</head>
                           |<body>
                           |<div id="swagger-ui"></div>
                           |<script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
                           |<script>
                           |SwaggerUIBundle({
                           |  url: "/openapi.yaml",
                           |  dom_id: '#swagger-ui',
                           |  deepLinking: true,
                           |  presets: [SwaggerUIBundle.presets.apis, SwaggerUIBundle.SwaggerUIStandalonePreset],
                           |  layout: "BaseLayout"
                           |})
                           |</script>
                           |</body>
                           |</html>""".stripMargin))
                }
            }
        }

        val homeRoute: Route = addCorsHeaders {
            pathEndOrSingleSlash {
                get {
                    complete(HttpEntity(ContentTypes.`text/html(UTF-8)`,
                        """
                          |<html>
                          |  <head>
                          |    <title>Simulation Server</title>
                          |  </head>
                          |  <body>
                          |    <h1>Simulation Server</h1>
                          |    <p>API endpoint: POST /run</p>
                          |    <p>Custom API endpoint: POST /custom</p>
                          |    <p>WebSocket endpoint: ws://localhost:8080/ws</p>
                          |  </body>
                          |</html>
                            """.stripMargin))
                }
            }
        }
        
        val routes: Route = corsHandler ~ webSocketRoute ~ apiRoute ~ userRoutes ~ simulationRoutes ~ docsRoute ~ homeRoute
        val bindingFuture = Http().newServerAt(serverHost, serverPort).bind(routes)
        
        bindingFuture.onComplete {
            case scala.util.Success(binding) =>
                val address = binding.localAddress
                Logger.log(s"Server online at http://${address.getHostString}:${address.getPort}/")
                Logger.log(s"API endpoint: http://${address.getHostString}:${address.getPort}/run")
                Logger.log(s"Custom API endpoint: http://${address.getHostString}:${address.getPort}/custom")
                Logger.log(s"WebSocket endpoint: ws://${address.getHostString}:${address.getPort}/ws")
            case scala.util.Failure(ex) =>
                Logger.log(s"Failed to bind server: ${ex.getMessage}")
                system.get.terminate()
        }
        
        initialized = true
    }
    
    /**
     * Broadcasts binary simulation data to all WebSocket clients connected to a specific channel.
     *
     * Receives the binary packet created by AgentProcessor.sendRoundToWebSocketServer() and distributes
     * it to all connected WebSocket clients for that simulation run. This enables real-time visualization
     * of agent states and beliefs during simulation execution. Uses Akka Streams for efficient
     * message delivery to multiple concurrent clients.
     *
     * '''Message Flow:'''
     * {{{
     * AgentProcessor ──▶ Server (this) ──▶ WebSocket Clients (Browser/Frontend)
     * }}}
     *
     * '''Binary Data Layout:'''
     * {{{
     * ┌──────────────────────────────────────────────────────────────────────┐
     * │ INPUT: ByteBuffer from AgentProcessor                                │
     * ├──────────────────────────────────────────────────────────────────────┤
     * │ HEADER (36 bytes)   │ networkId + runID + numOfAgents + round + range│
     * │ BELIEF DATA (n*8)   │ Public + Private beliefs for n agents          │
     * │ SPEAKING DATA (n*1) │ Speaking state (0/1) for n agents              │
     * ├──────────────────────────────────────────────────────────────────────┤
     * │ OUTPUT: BinaryMessage via WebSocket                                  │
     * └──────────────────────────────────────────────────────────────────────┘
     * }}}
     *
     * '''Error Handling:'''
     *  - Server not initialized: Logs error and returns early
     *  - Channel not found: Logs error when no active WebSocket connections exist
     *  - Network failures: Handled by Akka Streams internally
     *
     * '''Performance Notes:'''
     *  - Uses Akka Streams for backpressure handling
     *  - ByteString wraps buffer for zero-copy transmission
     *  - Single Source broadcasts to multiple clients efficiently
     *  - Debug logging only enabled when APP_MODE.hasServerLogs is true
     *
     * @param runId Unique identifier for the simulation run
     * @param buffer Binary packet containing agent states (created by AgentProcessor.sendRoundToWebSocketServer)
     */
    def sendSimulationBinaryData(runId: Long, buffer: ByteBuffer): Unit = {
        if (!initialized) return
        if (GlobalState.APP_MODE.hasServerLogs) logBufferDebugInfo(buffer)
        WsRegistry.dispatchBinary(runId, ByteString(buffer))
    }
    
    /**
     * Broadcasts neighbor topology data to all WebSocket clients connected to a specific channel.
     *
     * Receives the binary packet created by Network.sendNeighbors() and distributes it to all
     * connected WebSocket clients for that simulation run. This enables real-time visualization
     * of the agent network topology and relationships. Uses Akka Streams for efficient message
     * delivery to multiple concurrent clients.
     *
     * '''Message Flow:'''
     * {{{
     * Network.sendNeighbors() ──▶ Server (this) ──▶ WebSocket Clients (Browser/Frontend)
     * }}}
     *
     * '''Binary Data Layout:'''
     * {{{
     * ┌─────────────────────────────────────────────────────────────────────────────┐
     * │ INPUT: ByteBuffer from Network.sendNeighbors() (little-endian)              │
     * ├─────────────────────────────────────────────────────────────────────────────┤
     * │ HEADER (32 bytes)      │ networkId(16) + runId(8) + numAgents(4) + numN(4)│
     * │ INDEX OFFSETS (n*4)    │ Agent index mapping for n agents (CSR format)   │
     * │ NEIGHBOR REFS (m*4)    │ Neighbor reference indices for m connections    │
     * │ NEIGHBOR WEIGHTS (m*4) │ Connection weights for m neighbor pairs         │
     * │ NEIGHBOR BIASES (m*1)  │ Cognitive bias types for m connections          │
     * ├─────────────────────────────────────────────────────────────────────────────┤
     * │ OUTPUT: BinaryMessage via WebSocket                                         │
     * └─────────────────────────────────────────────────────────────────────────────┘
     * }}}
     *
     * '''Error Handling:'''
     *  - Server not initialized: Logs error and returns early
     *  - Channel not found: Silent failure (no active WebSocket connections)
     *  - Network failures: Handled by Akka Streams internally
     *
     * '''Performance Notes:'''
     *  - Uses Akka Streams for backpressure handling
     *  - ByteString wraps buffer for zero-copy transmission
     *  - Single Source broadcasts to multiple clients efficiently
     *  - Debug logging only enabled when APP_MODE.hasServerLogs is true
     *
     * @param runId Unique identifier for the simulation run
     * @param buffer Binary packet containing network topology data (created by Network.sendNeighbors)
     */
    def sendNeighborBinaryData(runId: Long, buffer: ByteBuffer): Unit = {
        if (!initialized) return
        WsRegistry.dispatchBinary(runId, ByteString(buffer))
    }

    def sendControlEvent(runId: Long, json: String): Unit = {
        if (!initialized) return
        WsRegistry.dispatchText(runId, json)
    }

    private def topologyPageJson(snap: TopologySnapshot, runId: Long, networkId: String,
        aOff: Int, aLim: Int, eOff: Int, eLim: Int): String = {
        val aEnd = math.min(aOff + aLim, snap.agentCount)
        val agents = (math.min(aOff, snap.agentCount) until aEnd).map { i =>
            val name = if (snap.names != null) s""""${snap.names(i)}"""" else "null"
            s"""{"index":$i,"name":$name,"initialBelief":${snap.initialBeliefs(i)},"toleranceRadius":${snap.tolRadius(i)},"toleranceOffset":${snap.tolOffset(i)},"silenceStrategy":${snap.silenceStrategies(i) & 0xFF},"silenceEffect":${snap.silenceEffects(i) & 0xFF}}"""
        }.mkString(",")
        val eStart = math.min(eOff, snap.edgeCount)
        val eEnd   = math.min(eOff + eLim, snap.edgeCount)
        val edges  = edgesPageJson(snap, eStart, eEnd)
        s"""{"runId":"$runId","networkId":"$networkId","agentCount":${snap.agentCount},"edgeCount":${snap.edgeCount},"agentOffset":$aOff,"agentLimit":$aLim,"edgeOffset":$eOff,"edgeLimit":$eLim,"agents":[$agents],"edges":[$edges]}"""
    }

    private def edgesPageJson(snap: TopologySnapshot, eStart: Int, eEnd: Int): String = {
        if (eStart >= eEnd || snap.edgeCount == 0) return ""
        val sb  = new StringBuilder
        var src = 0
        while (src < snap.agentCount - 1 && snap.indexOffset(src) <= eStart) src += 1
        var first = true
        for (j <- eStart until eEnd) {
            while (src < snap.agentCount - 1 && snap.indexOffset(src) <= j) src += 1
            if (!first) sb.append(',')
            first = false
            sb.append(s"""{"source":$src,"target":${snap.neighborsRefs(j)},"influence":${snap.neighborsWeights(j)},"bias":${snap.neighborBiases(j) & 0xFF}}""")
        }
        sb.toString
    }

    private def resultsPageJson(snap: ResultsSnapshot, runId: Long, networkId: String, off: Int, lim: Int): String = {
        val start = math.min(off, snap.agentCount)
        val end   = math.min(off + lim, snap.agentCount)
        val agents = (start until end).map { i =>
            val name = if (snap.names != null) s""""${snap.names(i)}"""" else "null"
            s"""{"index":$i,"name":$name,"finalBelief":${snap.finalBeliefs(i)},"publicBelief":${snap.publicBeliefs(i)}}"""
        }.mkString(",")
        s"""{"runId":"$runId","networkId":"$networkId","finalRound":${snap.finalRound},"consensus":${snap.consensus},"agentCount":${snap.agentCount},"offset":$off,"limit":$lim,"agents":[$agents]}"""
    }

    private val deprecationSunset = "Tue, 01 Sep 2026 00:00:00 GMT"

    private def withDeprecation(successor: String): Directive0 =
        respondWithHeaders(
            RawHeader("Deprecation", "true"),
            RawHeader("Sunset", deprecationSunset),
            RawHeader("Link", s"<$successor>; rel=\"successor-version\""),
            RawHeader("Warning", s"""299 - "Deprecated API. Migrate to $successor." """)
        )

    private def logLegacy(endpoint: String, userAgent: Option[String], ip: Option[String]): Unit =
        if (!GlobalState.APP_MODE.skipDatabase) {
            import scala.concurrent.Future
            implicit val ec: ExecutionContextExecutor = system.get.dispatcher
            Future(DatabaseManager.logLegacyUsage(endpoint, None, userAgent, ip))
        }

    private def executeGeneratedRun(
        seed: Long, saveMode: Byte, numberOfNetworks: Int, density: Int,
        iterationLimit: Int, stopThreshold: Float,
        agentTypes: Array[(SilenceStrategy, SilenceEffect, Int)],
        biases: Array[(Bias, Int)],
        agentsPerNetwork: Int,
        confidenceParams: mutable.Map[Int, (Float, Float)] = mutable.Map(),
        userId: Option[Int] = None,
        coevolutionConfig: Option[CoevolutionConfig] = None,
        persistFrames: Boolean = false,
        frameRetention: String = "ephemeral"
    ): Long = {
        var runID = SnowflakeID.generateId()
        val convertedSaveMode = if (GlobalState.APP_MODE.skipDatabase || !GlobalState.APP_MODE.usesLegacyDB)
            SaveModes.DEBUG
        else
            SaveModes.codeToSaveMode(saveMode)

        if (GlobalState.APP_MODE.usesLegacyDB && convertedSaveMode.savesToDB) {
            runID = DatabaseManager.createRun(
                RunMode.GENERATED, saveMode, numberOfNetworks, Some(density), Some(2.5f),
                stopThreshold, iterationLimit, "uniform"
            ).get
        } else if (!GlobalState.APP_MODE.skipDatabase) {
            DatabaseManager.saveGeneratedRun(
                id = runID,
                seed = seed,
                density = density,
                iterationLimit = iterationLimit,
                totalNetworks = numberOfNetworks,
                agentsPerNetwork = agentsPerNetwork,
                stopThreshold = stopThreshold,
                agentTypeDistributions = agentTypes,
                cognitiveBiasDistributions = biases,
                userId = userId,
                frameRetention = frameRetention
            )
        }

        monitor.get ! AddNetworks(
            runID, agentTypes, biases, confidenceParams,
            Uniform, convertedSaveMode, numberOfNetworks, density,
            iterationLimit, seed, 2.5f, stopThreshold,
            coevolutionConfig,
            persistFrames
        )

        runID
    }

    private def parseGeneratedRun(data: Array[Byte], config: Option[CoevolutionConfig] = None): String = {
        val saveMode = data(1)
        val agentTypeCount = data(2)
        val biasTypeCount = data(3)
        val numberOfNetworks = bytesToInt(data, 4)
        val density = bytesToInt(data, 8)
        val iterationLimit = bytesToInt(data, 12)
        val stopThreshold = bytesToFloat(data, 16)
        val seed: Long = if (bytesToLong(data, 20) == -1) {
            System.currentTimeMillis() ^ System.nanoTime()
        } else {
            bytesToLong(data, 20)
        }

        val agentTypes = new Array[(SilenceStrategy, SilenceEffect, Int)](agentTypeCount)
        val confidenceParams: mutable.Map[Int, (Float, Float)] = mutable.Map()
        var curOffset = 28
        var agentsPerNetwork = 0
        for (i <- 0 until agentTypeCount) {
            val count = bytesToInt(data, curOffset)
            val silenceStrategyType = SilenceStrategies.fromByte(data(curOffset + 4))
            val silenceEffectType = SilenceEffects.fromByte(data(curOffset + 5))
            agentTypes(i) = (silenceStrategyType, silenceEffectType, count)
            curOffset += 6
            agentsPerNetwork += count
        }

        val biases = new Array[(Bias, Int)](biasTypeCount)
        for (i <- 0 until biasTypeCount) {
            val count = bytesToInt(data, curOffset)
            val biasType: Bias = CognitiveBiases.fromByte(data(curOffset + 4))
            biases(i) = (biasType, count)
            curOffset += 5
        }

        executeGeneratedRun(
            seed, saveMode, numberOfNetworks, density, iterationLimit, stopThreshold,
            agentTypes, biases, agentsPerNetwork, confidenceParams, userId = None,
            coevolutionConfig = config
        ).toString
    }
    
    private def parseCustomRun(
        data: Array[Byte],
        userId: Option[Int] = None,
        persistFrames: Boolean = false,
        frameRetention: String = "ephemeral",
        config: Option[CoevolutionConfig] = None
    ): Long = {
        // Header
        val stopThreshold = bytesToFloat(data, 0)
        val iterationLimit = bytesToInt(data, 4)
        val saveMode = data(8)
        var offset = 9
        val networkNameLength = data(9)
        val networkName = byteArrToString(data, 10, networkNameLength)
        offset = 10 + networkNameLength
        
        while (offset % 4 != 0) offset += 1
        
        // Agent section
        val numberOfAgents = bytesToInt(data, offset)
        offset += 4
        
        val initialBeliefs = byteArrayToFloatArray(data, offset, numberOfAgents)
        offset += 4 * numberOfAgents
        
        val toleranceRadius = byteArrayToFloatArray(data, offset, numberOfAgents)
        offset += 4 * numberOfAgents
        
        val toleranceOffset = byteArrayToFloatArray(data, offset, numberOfAgents)
        offset += 4 * numberOfAgents
        
        val silenceStrategies = data.slice(offset, offset + numberOfAgents).asInstanceOf[Array[SilenceStrategy]]
        offset += numberOfAgents
        
        val silenceEffects = data.slice(offset, offset + numberOfAgents).asInstanceOf[Array[SilenceEffect]]
        offset += numberOfAgents
        
        // Read the agent names
        val agentNames = new Array[String](numberOfAgents)
        val agentIndexes = new mutable.HashMap[String, Int]()
        for (i <- 0 until numberOfAgents) {
            val strByteLength = data(offset)
            offset += 1
            agentNames(i) = byteArrToString(data, offset, strByteLength)
            offset += strByteLength
            agentIndexes(agentNames(i)) = i
        }
        
        // Align to 4 bytes
        while (offset % 4 != 0) offset += 1
        
        // Target Agent Offset
        val indexOffset = byteArrayToIntArray(data, offset, numberOfAgents)
        offset += 4 * numberOfAgents
        
        // Neighbor section
        val numberOfNeighbors = bytesToInt(data, offset)
        offset += 4
        
        val target = byteArrayToIntArray(data, offset, numberOfNeighbors)
        offset += 4 * numberOfNeighbors
        
        val influences = byteArrayToFloatArray(data, offset, numberOfNeighbors)
        offset += 4 * numberOfNeighbors
        
        val bias = data.slice(offset, offset + numberOfNeighbors).asInstanceOf[Array[Bias]]
        
        // Sorting the index offset
        val sourceIndex = new Array[Int](numberOfNeighbors)
        var agentIndex = 0
        for (i <- 0 until numberOfNeighbors) {
            if (agentIndex < indexOffset.length - 1 && i >= indexOffset(agentIndex + 1)) {
                agentIndex += 1
            }
            sourceIndex(i) = agentIndex
        }
        
        val sortedIndices = sourceIndex.indices.sortBy(i => (sourceIndex(i), target(i)))
        val sortedSource = sortedIndices.map(sourceIndex).toArray
        val sortedTarget = sortedIndices.map(target).toArray
        val sortedInfluences = sortedIndices.map(influences).toArray
        val sortedBiases = sortedIndices.map(bias).toArray
        
        var runID = SnowflakeID.generateId()
        val convertedSaveMode = if (GlobalState.APP_MODE.skipDatabase) SaveModes.DEBUG
        else SaveModes.codeToSaveMode(saveMode)
        
        if (SaveModes.savesToDB(convertedSaveMode)) {
            runID = DatabaseManager.createRun(
                RunMode.CUSTOM, saveMode, 1, None, None,
                stopThreshold, iterationLimit,
                "uniform"
            ).get
        } else if (!GlobalState.APP_MODE.skipDatabase) {
            DatabaseManager.saveCustomRun(
                id = runID,
                iterationLimit = iterationLimit,
                stopThreshold = stopThreshold,
                runName = networkName,
                customAgentsData = CustomAgentsData(
                    initialBeliefs,
                    toleranceRadius,
                    toleranceOffset,
                    silenceStrategies,
                    silenceEffects,
                    agentNames,
                    None, None
                ),
                customNeighborsData = CustomNeighborsData(
                    sortedSource,
                    sortedTarget,
                    sortedInfluences,
                    sortedBiases
                ),
                userId = userId,
                frameRetention = frameRetention
            )
        }


        val customRunInfo = CustomRunInfo(
            runID = runID,
            stopThreshold = stopThreshold,
            iterationLimit = iterationLimit,
            saveMode = convertedSaveMode,
            networkName = networkName,
            agentBeliefs = initialBeliefs,
            agentToleranceRadii = toleranceRadius,
            agentToleranceOffsets = toleranceOffset,
            agentSilenceStrategy = silenceStrategies,
            agentSilenceEffect = silenceEffects,
            agentNames = agentNames,
            indexOffset = indexOffset,
            target = sortedTarget,
            influences = sortedInfluences,
            bias = sortedBiases,
            coevolutionConfig = config,
            persistFrames = persistFrames
        )
        
        monitor.get ! RunCustomNetwork(customRunInfo)

        runID
    }

    private def executeCustomRunFromJson(
        req: CustomRunReq,
        userId: Option[Int],
        coevolutionConfig: Option[CoevolutionConfig] = None,
        persistFrames: Boolean = false,
        frameRetention: String = "ephemeral"
    ): Long = {
        val agentIndexes = req.agents.zipWithIndex.map { case (a, i) => a.name -> i }.toMap
        val agentNames         = req.agents.map(_.name).toArray
        val initialBeliefs     = req.agents.map(_.belief).toArray
        val toleranceRadius    = req.agents.map(_.toleranceRadius).toArray
        val toleranceOffset    = req.agents.map(_.toleranceOffset).toArray
        val silenceStrategies  = req.agents.map(a => SilenceStrategies.fromByte(a.silenceStrategy.toByte)).toArray
        val silenceEffects     = req.agents.map(a => SilenceEffects.fromByte(a.silenceEffect.toByte)).toArray

        val srcArr  = req.edges.map(e => agentIndexes(e.source)).toArray
        val tgtArr  = req.edges.map(e => agentIndexes(e.target)).toArray
        val infArr  = req.edges.map(_.influence).toArray
        val biasArr = req.edges.map(e => CognitiveBiases.fromByte(e.bias.toByte)).toArray

        val sortedIdx    = srcArr.indices.sortBy(srcArr(_))
        val sortedSrc    = sortedIdx.map(srcArr(_)).toArray
        val sortedTgt    = sortedIdx.map(tgtArr(_)).toArray
        val sortedInf    = sortedIdx.map(infArr(_)).toArray
        val sortedBiases = sortedIdx.map(biasArr(_)).toArray

        val nAgents    = req.agents.size
        val nNeighbors = req.edges.size
        val indexOffset = new Array[Int](nAgents)
        var count = 0
        for (i <- sortedSrc.indices) {
            if (i != 0 && sortedSrc(i - 1) != sortedSrc(i)) {
                indexOffset(count) = i
                count += 1
            }
        }
        if (nNeighbors > 0) indexOffset(indexOffset.length - 1) = nNeighbors

        var runID = SnowflakeID.generateId()
        val saveMode = req.saveMode.toByte
        val convertedSaveMode = if (GlobalState.APP_MODE.skipDatabase || !GlobalState.APP_MODE.usesLegacyDB)
                                    SaveModes.DEBUG
                                else SaveModes.codeToSaveMode(saveMode)

        if (GlobalState.APP_MODE.usesLegacyDB && SaveModes.savesToDB(convertedSaveMode)) {
            runID = DatabaseManager.createRun(
                RunMode.CUSTOM, saveMode, 1, None, None,
                req.stopThreshold, req.iterationLimit, "uniform"
            ).get
        } else if (!GlobalState.APP_MODE.skipDatabase) {
            DatabaseManager.saveCustomRun(
                id                = runID,
                iterationLimit    = req.iterationLimit,
                stopThreshold     = req.stopThreshold,
                runName           = req.name,
                customAgentsData  = CustomAgentsData(initialBeliefs, toleranceRadius, toleranceOffset,
                                      silenceStrategies, silenceEffects, agentNames, None, None),
                customNeighborsData = CustomNeighborsData(sortedSrc, sortedTgt, sortedInf, sortedBiases),
                userId = userId,
                frameRetention = frameRetention
            )
        }

        monitor.get ! RunCustomNetwork(CustomRunInfo(
            runID                = runID,
            stopThreshold        = req.stopThreshold,
            iterationLimit       = req.iterationLimit,
            saveMode             = convertedSaveMode,
            networkName          = req.name,
            agentBeliefs         = initialBeliefs,
            agentToleranceRadii  = toleranceRadius,
            agentToleranceOffsets= toleranceOffset,
            agentSilenceStrategy = silenceStrategies,
            agentSilenceEffect   = silenceEffects,
            agentNames           = agentNames,
            indexOffset          = indexOffset,
            target               = sortedTgt,
            influences           = sortedInf,
            bias                 = sortedBiases,
            coevolutionConfig    = coevolutionConfig,
            persistFrames        = persistFrames
        ))
        runID
    }

    // Utility methods for data transformation
    private def bytesToInt(bytes: Array[Byte], offset: Int): Int = {
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
    }
    
    private def bytesToFloat(bytes: Array[Byte], offset: Int): Float = {
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat()
    }
    
    private def bytesToLong(bytes: Array[Byte], offset: Int): Long = {
        ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).getLong()
    }
    
    private def byteArrayToFloatArray(source: Array[Byte], offset: Int, length: Int): Array[Float] = {
        val dest = new Array[Float](length)
        ByteBuffer.wrap(source, offset, length * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(dest)
        dest
    }
    
    private def byteArrayToIntArray(source: Array[Byte], offset: Int, length: Int): Array[Int] = {
        val dest = new Array[Int](length)
        ByteBuffer.wrap(source, offset, length * 4).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(dest)
        dest
    }
    
    /*
     * Converts a byte string representation in UTF-8 encoding to String
     */
    private def byteArrToString(bytes: Array[Byte], start: Int, length: Int): String = {
        new String(bytes, start, length, "UTF-8")
    }
    
    // Logging section methods only for server debug purposes
    private def logBufferDebugInfo(buffer: ByteBuffer): Unit = {
        val originalPosition = buffer.position()
        val originalLimit = buffer.limit()
        
        Logger.logServer("--- SERVER SEND DEBUG ---")
        Logger.logServer(s"Buffer state: position=$originalPosition, limit=$originalLimit, remaining=${buffer.remaining()}")
        
        // Read header for validation
        buffer.rewind()
        val networkIdMSB = buffer.getLong()
        val networkIdLSB = buffer.getLong()
        val runId = buffer.getInt()
        val numberOfAgents = buffer.getInt()
        val round = buffer.getInt()
        val indexReference = buffer.getInt()
        
        Logger.logServer(s"Header: runId=$runId, agents=$numberOfAgents, round=$round, indexRef=$indexReference")
        
        // Restore original buffer state
        buffer.position(originalPosition)
        buffer.limit(originalLimit)
    }
}