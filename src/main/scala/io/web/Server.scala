package io.web

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods.{GET, OPTIONS, POST}
import akka.http.scaladsl.model.headers.*
import akka.http.scaladsl.model.ws.{BinaryMessage, Message}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.{Directive0, Route}
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshaller}
import akka.stream.Materializer
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, MergeHub, Sink, Source}
import akka.util.ByteString
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import core.model.agent.behavior.silence.SilenceStrategy
import core.simulation.actors.{AddNetworks, RunCustomNetwork}
import core.simulation.config.*
import io.db.DatabaseManager
import utils.rng.distributions.Uniform

import java.nio.{ByteBuffer, ByteOrder}
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

// Data containers:
case class CustomRunInfo(
    channelId: String,
    stopThreshold: Float,
    iterationLimit: Int,
    saveMode: SaveMode,
    networkName: String,
    agentBeliefs: Array[Float],
    agentToleranceRadii: Array[Float],
    agentToleranceOffsets: Array[Float],
    agentSilenceStrategy: Array[Byte],
    agentSilenceEffect: Array[Byte],
    agentNames: Array[String],
    indexOffset: Array[Int],
    target: Array[Int],
    influences: Array[Float],
    bias: Array[Byte]
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
    
    case class UserSyncRequest(firebaseUid: String, email: String, name: String)
    
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
            `Access-Control-Allow-Methods`(POST, GET, OPTIONS),
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
                    optionalHeaderValueByName("Origin") { origin =>
                        // ToDo add origin validation here
                        val channelFlow = createChannelFlow(channelId)
                        handleWebSocketMessages(channelFlow)
                    }
                }
            }
        }
        
        val apiRoute: Route = addCorsHeaders {
            pathPrefix("run") {
                post {
                    entity(as[Payload]) { payload =>
                        val channelId = parseGeneratedRun(payload.data)
                        complete(channelId) 
                    }
                }
            } ~ pathPrefix("custom") {
                post {
                    entity(as[Payload]) { payload =>
                        val channelId = parseCustomRun(payload.data)
                        complete(channelId)
                    }
                }
            } ~ pathPrefix("neighbors") {
                get {
                    entity(as[Payload]) { payload =>
                        val channelId = parseCustomRun(payload.data)
                        complete(channelId)
                    }
                }
            }
        }
        
        val userRoutes: Route = addCorsHeaders {
            pathPrefix("api" / "users") {
                path("sync") {
                    post {
                        entity(as[String]) { jsonString =>
                            parseUserSyncRequest(jsonString) match {
                                case Some(userRequest) =>
                                    DatabaseManager.createOrUpdateUser(
                                        userRequest.firebaseUid,
                                        userRequest.email,
                                        userRequest.name
                                    ) match {
                                        case Some(user) =>
                                            complete(StatusCodes.OK -> s"User synced: ${user.email}")
                                        case None =>
                                            complete(StatusCodes.InternalServerError -> "Failed to sync user")
                                    }
                                case None =>
                                    complete(StatusCodes.BadRequest -> "Invalid user data")
                            }
                        }
                    }
                } ~
                  path("info" / Segment) { firebaseUid =>
                      get {
                          DatabaseManager.getUserByFirebaseUid(firebaseUid) match {
                              case Some(user) =>
                                  complete(StatusCodes.OK -> s"User: ${user.email}, Role: ${user.role}")
                              case None =>
                                  complete(StatusCodes.NotFound -> "User not found")
                          }
                      }
                  } ~
                  path("role" / Segment / Segment) { (firebaseUid, newRole) =>
                      put {
                          if (DatabaseManager.updateUserRole(firebaseUid, newRole)) {
                              complete(StatusCodes.OK -> s"Role updated to: $newRole")
                          } else {
                              complete(StatusCodes.InternalServerError -> "Failed to update role")
                          }
                      }
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
        
        val routes: Route = corsHandler ~ webSocketRoute ~ apiRoute ~ userRoutes ~ homeRoute
        val bindingFuture = Http().newServerAt(serverHost, serverPort).bind(routes)
        
        bindingFuture.onComplete {
            case scala.util.Success(binding) =>
                val address = binding.localAddress
                println(s"Server online at http://${address.getHostString}:${address.getPort}/")
                println(s"API endpoint: http://${address.getHostString}:${address.getPort}/run")
                println(s"Custom API endpoint: http://${address.getHostString}:${address.getPort}/custom")
                println(s"WebSocket endpoint: ws://${address.getHostString}:${address.getPort}/ws")
            case scala.util.Failure(ex) =>
                println(s"Failed to bind server: ${ex.getMessage}")
                system.get.terminate()
        }
        
        initialized = true
    }
    
    private def parseUserSyncRequest(jsonString: String): Option[UserSyncRequest] = {
        try {
            val firebaseUidPattern = """"firebaseUid"\s*:\s*"([^"]+)"""".r
            val emailPattern = """"email"\s*:\s*"([^"]+)"""".r
            val namePattern = """"name"\s*:\s*"([^"]+)"""".r
            
            val firebaseUid = firebaseUidPattern.findFirstMatchIn(jsonString).map(_.group(1)).getOrElse("")
            val email = emailPattern.findFirstMatchIn(jsonString).map(_.group(1)).getOrElse("")
            val name = namePattern.findFirstMatchIn(jsonString).map(_.group(1)).getOrElse("")
            
            if (firebaseUid.nonEmpty) {
                Some(UserSyncRequest(firebaseUid, email, name))
            } else {
                None
            }
        } catch {
            case _: Exception => None
        }
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
    
    // Send data to all WebSocket clients
    def sendSimulationBinaryData(channelId: String, buffer: ByteBuffer): Unit = {
        if (!initialized || system.isEmpty) {
            println("Error: WebSocket server not initialized properly")
            return
        }
        
        channelPublishers.get(channelId) match {
            case Some(publisher) =>
                if (GlobalState.APP_MODE == AppMode.DEBUG_SERVER) logBufferDebugInfo(buffer)
                
                implicit val materializer: Materializer = Materializer(system.get)
                implicit val ec: ExecutionContext = system.get.dispatcher
                val message = BinaryMessage(ByteString(buffer))
                Source.single(message).runWith(publisher)
            case None =>
                println(s"Error: No WebSocket clients connected to channel $channelId")
        }
    }
    
    def sendNeighborBinaryData(channelId: String, buffer: ByteBuffer): Unit = {
        if (!initialized || system.isEmpty) {
            println("Error: WebSocket server not initialized properly")
            return
        }
        channelPublishers.get(channelId) match {
            case Some(publisher) =>
                if (GlobalState.APP_MODE == AppMode.DEBUG_SERVER) logBufferDebugInfo(buffer)
                
                implicit val materializer: Materializer = Materializer(system.get)
                val message = BinaryMessage(ByteString(buffer))
                Source.single(message).runWith(publisher)
        }
    }
    
    private def parseGeneratedRun(data: Array[Byte]): String = {
        val saveMode = data(1)
        val agentTypeCount = data(2)
        val biasTypeCount = data(3)
        val numberOfNetworks = bytesToInt(data, 4)
        val density = bytesToInt(data, 8)
        val iterationLimit = bytesToInt(data, 12)
        val stopThreshold = bytesToFloat(data, 16)
        val seed: Option[Long] = if (bytesToLong(data, 20) == -1) None else Some(bytesToLong(data, 20))
        
        val agentTypes = new Array[(Byte, Byte, Int)](agentTypeCount)
        val confidenceParams: mutable.Map[Int, (Float, Float)] = mutable.Map()
        var curOffset = 28
        for (i <- 0 until agentTypeCount) {
            val count = bytesToInt(data, curOffset)
            val strategyByte = data(curOffset + 4)
            val silenceEffectType = data(curOffset + 5)
            val (silenceStrategyType, additionalOffset) = strategyByte match {
                case SilenceStrategy.THRESHOLD =>
                    val threshold = bytesToFloat(data, curOffset + 6)
                    confidenceParams += (i -> (threshold, -1.0f))
                    (strategyByte, 4)
                    
                case SilenceStrategy.CONFIDENCE =>
                    val confidence = bytesToFloat(data, curOffset + 6)
                    val threshold = bytesToFloat(data, curOffset + 10)
                    confidenceParams += (i -> (threshold, confidence))
                    (strategyByte, 8)
                    
                case _ =>
                    (strategyByte, 0)
            }
            agentTypes(i) = (silenceStrategyType, silenceEffectType, count)
            curOffset += 6 + additionalOffset
        }
        
        val biases = new Array[(Byte, Int)](biasTypeCount)
        
        for (i <- 0 until biasTypeCount) {
            val count = bytesToInt(data, curOffset)
            val biasType = data(curOffset + 4)
            biases(i) = (biasType, count)
            curOffset += 5
        }
        
        val channelId = takeChannel()
        
        monitor.get ! AddNetworks(
            channelId,
            agentTypes,
            biases,
            confidenceParams,
            Uniform,
            codeToSaveMode(saveMode).get,
            numberOfNetworks,
            density,
            iterationLimit,
            seed,
            2.5f,
            stopThreshold
        )
        
        channelId
    }
    
    private def parseCustomRun(data: Array[Byte]): String = {
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
        
        val toleranceRadii = byteArrayToFloatArray(data, offset, numberOfAgents)
        offset += 4 * numberOfAgents
        
        val toleranceOffset = byteArrayToFloatArray(data, offset, numberOfAgents)
        offset += 4 * numberOfAgents
        
        val silenceStrategies = data.slice(offset, offset + numberOfAgents)
        offset += numberOfAgents
        
        val silenceEffects = data.slice(offset, offset + numberOfAgents)
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
        
        val biases = data.slice(offset, offset + numberOfNeighbors)
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
        
        val sortedIndices = source.indices.sortBy(source(_))
        
        val sortedInfluences = sortedIndices.map(influences(_)).toArray
        val sortedBiases = sortedIndices.map(biases(_)).toArray
        val sortedSource = sortedIndices.map(source(_)).toArray
        val sortedTarget = sortedIndices.map(target(_)).toArray
        
        val indexOffset = new Array[Int](numberOfAgents)
        var count = 0
        for (i <- 1 until numberOfNeighbors) {
            if (sortedSource(i - 1) != sortedSource(i)) {
                indexOffset(count) = i - 1
                count += 1
            }
        }
        indexOffset(indexOffset.length - 1) = numberOfNeighbors - 1
        
        val channelId = takeChannel()
        
        val customRunInfo = CustomRunInfo(
            channelId = channelId,
            stopThreshold = stopThreshold,
            iterationLimit = iterationLimit,
            saveMode = codeToSaveMode(saveMode).get,
            networkName = networkName,
            agentBeliefs = initialBeliefs,
            agentToleranceRadii = toleranceRadii,
            agentToleranceOffsets = toleranceOffset,
            agentSilenceStrategy = silenceStrategies,
            agentSilenceEffect = silenceEffects,
            agentNames = agentNames,
            indexOffset = indexOffset,
            target = sortedTarget,
            influences = sortedInfluences,
            bias = sortedBiases
        )
        
        monitor.get ! RunCustomNetwork(customRunInfo)
        
        channelId
    }
    
    private def codeToSaveMode(code: Byte): Option[SaveMode] = {
        code match {
            case 0 => Some(Full)
            case 1 => Some(Standard)
            case 2 | 3 => Some(StandardLight)
            case 4 => Some(Roundless)
            case 5 | 6 => Some(AgentlessTyped)
            case 7 => Some(Agentless)
            case 8 => Some(Performance)
            case 9 => Some(Debug)
            case _ => None
        }
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
        
        println("--- SERVER SEND DEBUG ---")
        println(s"Buffer state: position=$originalPosition, limit=$originalLimit, remaining=${buffer.remaining()}")
        
        // Read header for validation
        buffer.rewind()
        val networkIdMSB = buffer.getLong()
        val networkIdLSB = buffer.getLong()
        val runId = buffer.getInt()
        val numberOfAgents = buffer.getInt()
        val round = buffer.getInt()
        val indexReference = buffer.getInt()
        
        println(s"Header: runId=$runId, agents=$numberOfAgents, round=$round, indexRef=$indexReference")
        
        // Restore original buffer state
        buffer.position(originalPosition)
        buffer.limit(originalLimit)
    }
}