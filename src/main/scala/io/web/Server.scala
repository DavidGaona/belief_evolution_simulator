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
import core.model.agent.behavior.silence.{SilenceEffectType, SilenceStrategyType}
import core.simulation.actors.{AddNetworks, RunCustomNetwork}
import core.simulation.config.*
import utils.rng.distributions.Uniform

import java.nio.{ByteBuffer, ByteOrder}
import scala.collection.mutable
import scala.concurrent.ExecutionContextExecutor

// Data containers:
case class CustomRunInfo(
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
    private var messagePublisher: Option[Sink[Message, Any]] = None
    
    def initialize(actorSystem: ActorSystem, monitor: ActorRef): Unit = {
        if (initialized) return
        
        system = Some(actorSystem)
        this.monitor = Some(monitor)
        
        implicit val sys: ActorSystem = actorSystem
        implicit val executionContext: ExecutionContextExecutor = actorSystem.dispatcher
        implicit val materializer: Materializer = Materializer(actorSystem)
        
        // Create a hub for broadcasting messages to all connected clients
        val (sink, source) = MergeHub.source[Message]
          .toMat(BroadcastHub.sink[Message])(Keep.both)
          .run()
        
        messagePublisher = Some(sink)
        
        // Create a WebSocket flow that will handle our WebSocket connections
        val websocketFlow = Flow.fromSinkAndSourceMat(
            Sink.ignore, // Ignore incoming messages from clients
            source // Broadcast our messages to all clients
        )(Keep.right)
        
        // Enhanced CORS headers
        val corsResponseHeaders = List(
            `Access-Control-Allow-Origin`.*,
            `Access-Control-Allow-Methods`(POST, GET, OPTIONS),
            `Access-Control-Allow-Headers`("Content-Type", "Authorization", "X-Requested-With", "Accept", "Origin"),
            `Access-Control-Max-Age`(86400), // 24 hours preflight cache
            `Access-Control-Allow-Credentials`(false)
        )
        
        // Enhanced CORS directive that applies to all routes
        def addCorsHeaders: Directive0 = {
            respondWithHeaders(corsResponseHeaders)
        }
        
        // Universal CORS preflight handler
        val corsHandler: Route = addCorsHeaders {
            options {
                complete(HttpResponse(StatusCodes.OK))
            }
        }
        
        val webSocketRoute: Route = addCorsHeaders {
            path("ws") {
                get {
                    optionalHeaderValueByName("Origin") { origin =>
                        // ToDo add origin validation here
                        handleWebSocketMessages(websocketFlow)
                    }
                }
            }
        }
        
        val apiRoute: Route = addCorsHeaders {
            pathPrefix("run") {
                post {
                    entity(as[Payload]) { payload =>
                        runGeneratedRun(payload.data)
                        complete(s"Received payload successfully")
                    }
                }
            } ~
              pathPrefix("custom") {
                  post {
                      entity(as[Payload]) { payload =>
                          parseCustomRun(payload.data)
                          complete(s"Received payload successfully")
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
        
        val routes: Route = corsHandler ~ webSocketRoute ~ apiRoute ~ homeRoute
        val bindingFuture = Http().newServerAt("0.0.0.0", 8080).bind(routes)
        
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
    
    // Method to send data to all WebSocket clients
    def sendSimulationBinaryData(buffer: ByteBuffer): Unit = {
        if (!initialized || messagePublisher.isEmpty || system.isEmpty) {
            println("Error: WebSocket server not initialized properly")
            return
        }
        
        implicit val materializer: Materializer = Materializer(system.get)
        
        // Create a copy of the buffer to ensure thread safety
        val bytes = new Array[Byte](buffer.remaining())
        buffer.get(bytes)
        buffer.position(buffer.position() - bytes.length) // Reset position
        
        // Create a binary message from the buffer
        val message = BinaryMessage(ByteString(bytes))
        
        // Send the message to all clients
        Source.single(message).runWith(messagePublisher.get)
    }
    
    // Remaining methods from Api class
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
    
    private def byteArrToString(bytes: Array[Byte], start: Int, length: Int): String = {
        new String(bytes, start, length, "UTF-8")
    }
    
    private def runGeneratedRun(data: Array[Byte]): Unit = {
        val saveMode = data(1)
        val agentTypeCount = data(2)
        val biasTypeCount = data(3)
        val numberOfNetworks = bytesToInt(data, 4)
        val density = bytesToInt(data, 8)
        val iterationLimit = bytesToInt(data, 12)
        val stopThreshold = bytesToFloat(data, 16)
        val seed: Option[Long] = if (bytesToLong(data, 20) == -1) None else Some(bytesToLong(data, 20))
        
        val agentTypes = new Array[(SilenceStrategyType, SilenceEffectType, Int)](agentTypeCount)
        var curOffset = 28
        for (i <- 0 until agentTypeCount) {
            val count = bytesToInt(data, curOffset)
            val strategyByte = data(curOffset + 4)
            val silenceEffectType = SilenceEffectType.fromByte(data(curOffset + 5))
            val (silenceStrategyType, additionalOffset) = strategyByte match {
                case 2 =>
                    val threshold = bytesToFloat(data, curOffset + 6)
                    (SilenceStrategyType.fromByte(strategyByte, thresholdValue = threshold), 4)
                    
                case 3 =>
                    val confidence = bytesToFloat(data, curOffset + 6)
                    val update = bytesToInt(data, curOffset + 10)
                    (SilenceStrategyType.fromByte(strategyByte, confidenceValue = confidence, updateValue = update), 8)
                    
                case _ =>
                    (SilenceStrategyType.fromByte(strategyByte), 0)
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
        
        monitor.get ! AddNetworks(
        agentTypes,
        biases,
        Uniform,
        codeToSaveMode(saveMode).get,
        None,
        numberOfNetworks,
        density,
        iterationLimit,
        seed,
        2.5f,
        stopThreshold
        )
    }
    
    private def parseCustomRun(data: Array[Byte]): Unit = {
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
        println(s"initialBeliefs: ${(0 until numberOfAgents).map(initialBeliefs).mkString("[", ", ", "]")}")
        offset += 4 * numberOfAgents
        
        val toleranceRadii = byteArrayToFloatArray(data, offset, numberOfAgents)
        println(s"toleranceRadii: ${(0 until numberOfAgents).map(toleranceRadii).mkString("[", ", ", "]")}")
        offset += 4 * numberOfAgents
        
        val toleranceOffset = byteArrayToFloatArray(data, offset, numberOfAgents)
        println(s"toleranceOffset: ${(0 until numberOfAgents).map(toleranceOffset).mkString("[", ", ", "]")}")
        offset += 4 * numberOfAgents
        
        val silenceStrategies = data.slice(offset, offset + numberOfAgents)
        println(s"silenceStrategies: ${silenceStrategies.mkString("[", ", ", "]")}")
        offset += numberOfAgents
        
        val silenceEffects = data.slice(offset, offset + numberOfAgents)
        println(s"silenceEffects: ${silenceEffects.mkString("[", ", ", "]")}")
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
        println(s"All source: ${source.mkString("[", ", ", "]")}")
        println(s"Indexes:")
        
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
        
        println(indexOffset.mkString("Index offset: (", ", ", ")"))
        println(sortedTarget.mkString("Targets: (", ", ", ")"))
        println(sortedInfluences.mkString("Influences: (", ", ", ")"))
        println(sortedBiases.mkString("Biases: (", ", ", ")"))
        
        val customRunInfo = CustomRunInfo(
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
        
        println(s"Final offset: $offset, Total data length: ${data.length}")
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
}