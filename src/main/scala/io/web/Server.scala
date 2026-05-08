package io.web

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods.{DELETE, GET, OPTIONS, POST, PUT}
import akka.http.scaladsl.model.headers.*
import akka.http.scaladsl.model.ws.{BinaryMessage, Message}
import akka.http.scaladsl.model.{ContentType, ContentTypes, HttpCharsets, HttpEntity, HttpResponse, MediaType, MediaTypes, StatusCodes}
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.{Directive0, Route}
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshaller}
import akka.stream.Materializer
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, MergeHub, Sink, Source}
import akka.util.ByteString
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
import io.web.auth.FirebaseAuthDirective.*
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
    coevolution: Option[CoevolutionReq] = None
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
    coevolution: Option[CoevolutionReq] = None
)

object SimulationJsonProtocol {
    implicit val agentTypeReqFmt: RootJsonFormat[AgentTypeReq]       = jsonFormat3(AgentTypeReq.apply)
    implicit val biasTypeReqFmt: RootJsonFormat[BiasTypeReq]         = jsonFormat2(BiasTypeReq.apply)
    implicit val coevolutionReqFmt: RootJsonFormat[CoevolutionReq]   = jsonFormat5(CoevolutionReq.apply)
    implicit val generatedRunReqFmt: RootJsonFormat[GeneratedRunReq] = jsonFormat9(GeneratedRunReq.apply)
    implicit val agentReqFmt: RootJsonFormat[AgentReq]               = jsonFormat6(AgentReq.apply)
    implicit val edgeReqFmt: RootJsonFormat[EdgeReq]                 = jsonFormat4(EdgeReq.apply)
    implicit val customRunReqFmt: RootJsonFormat[CustomRunReq]       = jsonFormat7(CustomRunReq.apply)
}

// Data containers:
case class CustomRunInfo(
    runID: Long,
    channelId: String,
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
    coevolutionConfig: Option[CoevolutionConfig] = None
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
    
    private val channelPublishers = mutable.Map[String, Sink[Message, Any]]()
    private val channelSources = mutable.Map[String, Source[Message, Any]]()
    private var channels: Long = 0L
    private val runChannelMap = mutable.Map[Long, String]()
    
    def initialize(actorSystem: ActorSystem, monitor: ActorRef): Unit = {
        if (initialized) return
        
        system = Some(actorSystem)
        this.monitor = Some(monitor)
        
        implicit val systenImplicit: ActorSystem = actorSystem
        implicit val executionContext: ExecutionContextExecutor = actorSystem.dispatcher
        implicit val materializer: Materializer = Materializer(actorSystem)
        
        val serverHost = sys.env.getOrElse("SERVER_HOST", "0.0.0.0")
        val serverPort = sys.env.getOrElse("SERVER_PORT", "8080").toInt
        
        val (sink, source) = MergeHub.source[Message]
          .toMat(BroadcastHub.sink[Message])(Keep.both)
          .run()
        
        val websocketFlow = Flow.fromSinkAndSourceMat(
            Sink.ignore, 
            source
        )(Keep.right)
        
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
            path("ws" / Segment) { channelId =>
                get {
                    withDeprecation("/simulations/{runId}/stream") {
                        optionalHeaderValueByName("User-Agent") { ua =>
                            optionalHeaderValueByName("X-Forwarded-For") { xff =>
                                logLegacy(s"/ws/$channelId", ua, xff)
                                optionalHeaderValueByName("Origin") { _ =>
                                    val channelFlow = createChannelFlow(channelId)
                                    handleWebSocketMessages(channelFlow)
                                }
                            }
                        }
                    }
                }
            } ~
            path("simulations" / LongNumber / "stream") { runId =>
                get {
                    parameter("ticket") { ticket =>
                        TicketStore.consume(ticket, runId) match {
                            case None =>
                                complete(StatusCodes.Forbidden ->
                                    errorJson("forbidden", "Invalid or expired ticket"))
                            case Some(_) =>
                                runChannelMap.get(runId) match {
                                    case None =>
                                        complete(StatusCodes.NotFound ->
                                            errorJson("not_found", "Run not active"))
                                    case Some(channelId) =>
                                        val channelFlow = createChannelFlow(channelId)
                                        handleWebSocketMessages(channelFlow)
                                }
                        }
                    }
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
                                        val channelId = parseGeneratedRun(payload.data, config)
                                        complete(channelId)
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
                                        val (_, channelId) = parseCustomRun(payload.data, config)
                                        complete(channelId)
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
                                    val (_, channelId) = parseCustomRun(payload.data)
                                    complete(channelId)
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
            TicketStore.cleanup()
            SimulationCache.cleanup()
        })(actorSystem.dispatcher)

        import SimulationJsonProtocol.*

        def simCreatedJson(runId: Long, networkCount: Int, channelId: String, uid: String): String = {
            val ticket = TicketStore.issue(runId, uid)
            s"""{
               |  "runId": "$runId",
               |  "status": "running",
               |  "networkCount": $networkCount,
               |  "channelId": "$channelId",
               |  "wsTicket": "$ticket",
               |  "wsUrl": "/simulations/$runId/stream"
               |}""".stripMargin
        }

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
                                else {
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
                                    val (runId, channelId) = executeGeneratedRun(
                                        seed, req.saveMode.toByte, req.numberOfNetworks,
                                        effectiveDensity, req.iterationLimit, req.stopThreshold,
                                        agentTypesArr, biasArr,
                                        agentsPerNetwork,
                                        userId = Some(authUser.dbUserId),
                                        coevolutionConfig = req.coevolution.map(c => CoevolutionConfig(
                                            c.pBreak, c.pCreate, c.rewiringStrategy,
                                            c.assortStop.getOrElse(1.1f),
                                            c.fragStop.getOrElse(false)
                                        ))
                                    )
                                    complete(jsonOk(simCreatedJson(runId, req.numberOfNetworks, channelId, authUser.uid)))
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
                                    entity(as[Payload]) { payload =>
                                        val (runId, channelId) = parseCustomRun(payload.data, userId = Some(authUser.dbUserId))
                                        complete(jsonOk(simCreatedJson(runId, 1, channelId, authUser.uid)))
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
                                        else {
                                            val coevCfg = runReq.coevolution.map(c => CoevolutionConfig(
                                                c.pBreak, c.pCreate, c.rewiringStrategy,
                                                c.assortStop.getOrElse(1.1f),
                                                c.fragStop.getOrElse(false)
                                            ))
                                            val (runId, channelId) = executeCustomRunFromJson(
                                                runReq, Some(authUser.dbUserId), coevCfg)
                                            complete(jsonOk(simCreatedJson(runId, 1, channelId, authUser.uid)))
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
    
    private def createChannelFlow(channelId: String): Flow[Message, Message, Any] = {
        implicit val systenImplicit: ActorSystem = system.get
        implicit val materializer: Materializer = Materializer(system.get)
        
        val (sink, source) = channelPublishers.get(channelId) match {
            case Some(existingSink) =>
                (existingSink, channelSources(channelId))
            case None =>
                val (newSink, newSource) = MergeHub.source[Message]
                  .toMat(BroadcastHub.sink[Message])(Keep.both)
                  .run()
                
                channelPublishers(channelId) = newSink
                channelSources(channelId) = newSource
                (newSink, newSource)
         }
        
        Flow.fromSinkAndSourceMat(
            Sink.ignore,
            source
        )(Keep.right)
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
     * @param channelId Unique identifier for the simulation run (maps to WebSocket connections)
     * @param buffer Binary packet containing agent states (created by AgentProcessor.sendRoundToWebSocketServer)
     */
    def sendSimulationBinaryData(channelId: String, buffer: ByteBuffer): Unit = {
        if (!initialized || system.isEmpty) {
            Logger.logError("Error: WebSocket server not initialized properly")
            return
        }
        
        channelPublishers.get(channelId) match {
            case Some(publisher) =>
                if (GlobalState.APP_MODE.hasServerLogs) logBufferDebugInfo(buffer)
                
                implicit val materializer: Materializer = Materializer(system.get)
                implicit val ec: ExecutionContext = system.get.dispatcher
                val message = BinaryMessage(ByteString(buffer))
                Source.single(message).runWith(publisher)
            case None =>
                Logger.logError(s"Error: No WebSocket clients connected to channel $channelId")
        }
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
     * │ INPUT: ByteBuffer from Network.sendNeighbors()                              │
     * ├──────────────────────────────────────────────────────────────────────────┤
     * │ HEADER (24 bytes)      │ networkId + runID + numberOfAgents + neighbors  │
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
     * @param channelId Unique identifier for the simulation run (maps to WebSocket connections)
     * @param buffer Binary packet containing network topology data (created by Network.sendNeighbors)
     */
    def sendNeighborBinaryData(channelId: String, buffer: ByteBuffer): Unit = {
        if (!initialized || system.isEmpty) {
            Logger.logError("Error: WebSocket server not initialized properly")
            return
        }
        channelPublishers.get(channelId) match {
            case Some(publisher) =>
                if (GlobalState.APP_MODE.hasServerLogs) logBufferDebugInfo(buffer)
                
                implicit val materializer: Materializer = Materializer(system.get)
                val message = BinaryMessage(ByteString(buffer))
                Source.single(message).runWith(publisher)
            case None =>
                Logger.logError(s"Error: No WebSocket clients connected to channel $channelId")
        }
    }
    
    def sendControlEvent(channelId: String, json: String): Unit = {
        if (!initialized || system.isEmpty) return
        channelPublishers.get(channelId) match {
            case Some(publisher) =>
                implicit val materializer: Materializer = Materializer(system.get)
                Source.single(akka.http.scaladsl.model.ws.TextMessage(json)).runWith(publisher)
            case None =>
                Logger.logError(s"sendControlEvent: no channel $channelId")
        }
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
        coevolutionConfig: Option[CoevolutionConfig] = None
    ): (Long, String) = {
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
                userId = userId
            )
        }

        val channelId = takeChannel()
        runChannelMap(runID) = channelId

        monitor.get ! AddNetworks(
            runID, channelId, agentTypes, biases, confidenceParams,
            Uniform, convertedSaveMode, numberOfNetworks, density,
            iterationLimit, seed, 2.5f, stopThreshold,
            coevolutionConfig
        )

        (runID, channelId)
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

        val (_, channelId) = executeGeneratedRun(
            seed, saveMode, numberOfNetworks, density, iterationLimit, stopThreshold,
            agentTypes, biases, agentsPerNetwork, confidenceParams,
            coevolutionConfig = config
        )
        channelId
    }
    
    private def parseCustomRun(data: Array[Byte], config: Option[CoevolutionConfig] = None, userId: Option[Int] = None): (Long, String) = {
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
            agentIndexes.put(agentNames(i), i)
            offset += strByteLength
        }
        
        // Align to 4 bytes
        while (offset % 4 != 0) offset += 1
        
        // Neighbor section
        val numberOfNeighbors = bytesToInt(data, offset)
        
        offset += 4
        
        val influences = byteArrayToFloatArray(data, offset, numberOfNeighbors)
        offset += 4 * numberOfNeighbors
        
        val biases = data.slice(offset, offset + numberOfNeighbors).asInstanceOf[Array[Bias]]
        offset += numberOfNeighbors
        
        val source = new Array[Int](numberOfNeighbors)
        for (i <- 0 until numberOfNeighbors) {
            val strByteLength = data(offset)
            offset += 1
            source(i) = agentIndexes(byteArrToString(data, offset, strByteLength))
            offset += strByteLength
        }
        
        val target = new Array[Int](numberOfNeighbors)
        for (i <- 0 until numberOfNeighbors) {
            val strByteLength = data(offset)
            offset += 1
            target(i) = agentIndexes(byteArrToString(data, offset, strByteLength))
            offset += strByteLength
        }
        
        // Optional data
        val majorityThreshold: mutable.Map[Int, Float] = mutable.Map()
        val confidences: mutable.Map[Int, Float] = mutable.Map()
        while (offset < data.length) {
            val possibleConfidence = bytesToFloat(data, offset + 8)
            possibleConfidence match {
                case 2.0 =>
                    majorityThreshold(bytesToInt(data, offset)) = bytesToFloat(data, offset + 4)
                case _ =>
                    majorityThreshold(bytesToInt(data, offset)) = bytesToFloat(data, offset + 4)
                    confidences(bytesToInt(data, offset)) = possibleConfidence
            }
            offset += 12
        }
        
        // Preparing data
        val sortedIndices = source.indices.sortBy(source(_))
        
        val sortedInfluences = sortedIndices.map(influences(_)).toArray
        val sortedBiases = sortedIndices.map(biases(_)).toArray
        val sortedSource = sortedIndices.map(source(_)).toArray
        val sortedTarget = sortedIndices.map(target(_)).toArray
        
        val indexOffset = new Array[Int](numberOfAgents)
        var count = 0
        for (i <- 0 until numberOfNeighbors) {
            if ((i != 0) && sortedSource(i - 1) != sortedSource(i)) {
                indexOffset(count) = i
                count += 1
            }
        }
        indexOffset(indexOffset.length - 1) = numberOfNeighbors
        
        var runID = SnowflakeID.generateId()
        val convertedSaveMode = if (GlobalState.APP_MODE.skipDatabase) SaveModes.DEBUG
        else SaveModes.codeToSaveMode(saveMode)
        
        if (SaveModes.savesToDB(convertedSaveMode)) {
            runID = DatabaseManager.createRun(
                RunMode.GENERATED, saveMode, 1, None, None,
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
                    if (majorityThreshold.isEmpty) None else Some(majorityThreshold),
                    if (confidences.isEmpty) None else Some(confidences),
                ),
                customNeighborsData = CustomNeighborsData(
                    sortedSource,
                    sortedTarget,
                    sortedInfluences,
                    sortedBiases
                ),
                userId = userId
            )
        }


        val channelId = takeChannel()

        val customRunInfo = CustomRunInfo(
            runID = runID,
            channelId = channelId,
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
            coevolutionConfig = config
        )
        
        monitor.get ! RunCustomNetwork(customRunInfo)

        (runID, channelId)
    }

    private def executeCustomRunFromJson(req: CustomRunReq, userId: Option[Int],
        coevolutionConfig: Option[CoevolutionConfig] = None): (Long, String) = {
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
                RunMode.GENERATED, saveMode, 1, None, None,
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
                userId = userId
            )
        }

        val channelId = takeChannel()
        runChannelMap(runID) = channelId
        monitor.get ! RunCustomNetwork(CustomRunInfo(
            runID                = runID,
            channelId            = channelId,
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
            coevolutionConfig    = coevolutionConfig
        ))
        (runID, channelId)
    }

    // Bit operation methods for channels
    private def takeChannel(): String = {
        val index = java.lang.Long.numberOfTrailingZeros(~channels).toString
        channels = channels | (channels + 1)
        createChannelFlow(index)
        index
    }
    
    def freeChannel(index: Int): Unit = {
        channels = channels & ~(1L << index)
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