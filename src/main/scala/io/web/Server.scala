package io.web

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods.{GET, OPTIONS, POST}
import akka.http.scaladsl.model.ws.{BinaryMessage, Message}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.model.headers.*
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshaller}
import akka.stream.Materializer
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, MergeHub, Sink, Source}
import akka.util.ByteString
import core.model.agent.behavior.bias.{CognitiveBiasType, CognitiveBiases}
import core.model.agent.behavior.silence.{SilenceEffectType, SilenceStrategyType}
import core.simulation.actors.AddNetworks
import core.simulation.config.*
import utils.rng.distributions.Uniform

import java.nio.{ByteBuffer, ByteOrder}
import scala.concurrent.ExecutionContextExecutor

final case class Payload(data: Array[Byte])

object BinaryProtocol {
    implicit def payloadUnmarshaller: FromEntityUnmarshaller[Payload] =
        Unmarshaller.byteStringUnmarshaller
          .mapWithInput { (_, bytes) =>
              Payload(bytes.toArray)
          }
}

object Server {
    import BinaryProtocol._
    
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
        
        // Store the sink for later use
        messagePublisher = Some(sink)
        
        // Create a WebSocket flow that will handle our WebSocket connections
        val websocketFlow = Flow.fromSinkAndSourceMat(
            Sink.ignore, // Ignore incoming messages from clients
            source       // Broadcast our messages to all clients
        )(Keep.right)
        
        // CORS headers
        val corsResponseHeaders = List(
            `Access-Control-Allow-Origin`.*,
            `Access-Control-Allow-Methods`(POST, GET, OPTIONS),
            `Access-Control-Allow-Headers`("Content-Type", "Authorization", "X-Requested-With")
        )
        
        // CORS handler
        val corsHandler: Route = options {
            complete(HttpResponse(StatusCodes.OK).withHeaders(corsResponseHeaders))
        }
        
        // WebSocket route
        val webSocketRoute: Route =
            path("ws") {
                get {
                    handleWebSocketMessages(websocketFlow)
                }
            }
        
        // API route
        val apiRoute: Route = respondWithHeaders(corsResponseHeaders) {
            post {
                pathPrefix("run") {
                    entity(as[Payload]) { payload =>
                        val data = payload.data
                        val runType = data(0)
                        runType match {
                            case 0 => runGeneratedRun(data)
                            case _ => runGeneratedRun(data)
                        }
                        
                        complete(s"Received payload successfully")
                    }
                }
            }
        }
        
        // Home page route
        val homeRoute: Route =
            pathEndOrSingleSlash {
                get {
                    complete(HttpEntity(ContentTypes.`text/html(UTF-8)`,
                        """
                          |<html>
                          |  <body>
                          |    <h1>Simulation Server</h1>
                          |    <p>API endpoint: POST /run</p>
                          |    <p>WebSocket endpoint: ws://localhost:8080/ws</p>
                          |  </body>
                          |</html>
                        """.stripMargin))
                }
            }
        
        // Combine all routes with CORS
        val routes: Route = corsHandler ~ webSocketRoute ~ apiRoute ~ homeRoute
        
        // Bind to port - using one port for both services
        val bindingFuture = Http().newServerAt("0.0.0.0", 8080).bind(routes)
        
        bindingFuture.onComplete {
            case scala.util.Success(binding) =>
                val address = binding.localAddress
                println(s"Server online at http://${address.getHostString}:${address.getPort}/")
                println(s"API endpoint: http://${address.getHostString}:${address.getPort}/run")
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
    @inline
    private def bytesToInt(bytes: Array[Byte], offset: Int): Int = {
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
    }
    
    @inline
    private def bytesToFloat(bytes: Array[Byte], offset: Int): Float = {
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat()
    }
    
    @inline
    private def bytesToLong(bytes: Array[Byte], offset: Int): Long = {
        ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).getLong()
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