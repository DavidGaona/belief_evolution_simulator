import akka.actor.{Actor, ActorRef, Props}

import scala.collection.mutable.ArrayBuffer

import java.util.UUID

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import tech.ant8e.uuid4cats.UUIDv7

// Monitor
object UUIDGenerator {
    private val generator = UUIDv7.generator[IO]
    
    def generateUUID(): IO[java.util.UUID] = for {
        gen <- generator
        uuid <- gen.uuid
    } yield uuid
}

// Messages
case class CreateNetwork // Monitor -> Network
(
  numberOfAgents: Int,
  density: Int,
  stopThreshold: Float,
  degreeDistributionParameter: Float,
  distribution: Distribution
)

case class BuildCustomNetwork(agents: Array[AgentInitialState]) // Monitor -> network
case object BuildNetwork // Monitor -> network
case object RunNetwork // Monitor -> network
case object StartAnalysis // Monitor -> network
case object RunBatch // Monitor -> self

// Actor
class Monitor extends Actor {
    // Networks
    var networks = ArrayBuffer[ActorRef]()
    var runId: Option[Int] = Some(-1)
    
    // Timing
    val globalTimers = new CustomMultiTimer
    val buildingTimers = new CustomMultiTimer
    val runningTimers = new CustomMultiTimer
    val analysisTimers = new CustomMultiTimer
    
    // Data saving
    val agentLimit = 100000
    
    // Batches
    var batches = 0
    var networksPerBatch = 0
    var numberOfNetworks = 0
    var curBatch = 0
    var numberOfNetworksFinished = 0
    var networksBuilt = 0
    var networksAnalyzed = 0
    
    // Router
    val totalCores = Runtime.getRuntime.availableProcessors()
    val saveThreshold = 1500000
    RoundDataRouters.createRouters(context, totalCores, saveThreshold)
    
    
    // Testing performance end
    
    def receive: Receive = {
        case AddSpecificNetwork(agents, stopThreshold, iterationLimit, name) =>
            runId = DatabaseManager.createRun(
                1, None, None, stopThreshold, iterationLimit, CustomDistribution.toString, None, None
            )
            globalTimers.start(s"Total_time")
            
            val networkId: UUID = UUIDGenerator.generateUUID().unsafeRunSync() 
            val network = context.actorOf(Props(new Network(
                networkId,
                agents.length,
                stopThreshold = stopThreshold,
                monitor = self,
                iterationLimit = iterationLimit,
                agentTypeCount = None)), name)
            DatabaseManager.createNetwork(networkId, name, runId.get, agents.length)
            buildingTimers.start(network.path.name)
            networks += network
            globalTimers.start("Building")
            network ! BuildCustomNetwork(agents)
            batches = 1
            networksPerBatch = 1
            numberOfNetworks = 1
            
        case AddNetworks(numberOfNetworks, numberOfAgents, density, degreeDistribution, stopThreshold, distribution,
        iterationLimit, agentTypeCount) =>
            runId = DatabaseManager.createRun(
                numberOfNetworks, Some(density), Some(degreeDistribution), stopThreshold, iterationLimit,
                distribution.toString, None, None
            )
            
            globalTimers.start(s"Total_time")
            val (batches, networksPerBatch) = calculateBatches(numberOfNetworks, numberOfAgents)
            this.batches = batches
            this.networksPerBatch = math.min(networksPerBatch, numberOfNetworks)
            this.numberOfNetworks = numberOfNetworks
            globalTimers.start("Building")
            for (i <- 0 until numberOfNetworks) {
                val networkId: UUID = UUIDGenerator.generateUUID().unsafeRunSync()
                networks += context.actorOf(Props(new Network(
                    networkId,
                    numberOfAgents,
                    density,
                    degreeDistribution,
                    stopThreshold,
                    distribution,
                    self,
                    iterationLimit,
                    Some(agentTypeCount)
                )), s"N${i + 1}")
                DatabaseManager.createNetwork(networkId, s"N${i + 1}", runId.get, numberOfAgents)
            }
            self ! RunBatch
        
        case RunBatch =>
            for (i <- numberOfNetworksFinished until networksPerBatch + numberOfNetworksFinished) {
                if (i < networks.size)
                    buildingTimers.start(networks(i).path.name)
                    networks(i) ! BuildNetwork
            }
            curBatch += 1
        
        case BuildingComplete(networkId) =>
            val network = sender()
            val networkName = network.path.name
            networksBuilt += 1
            if (networksBuilt == 1) globalTimers.start("Running")
            DatabaseManager.updateTimeField(Right(networkId), buildingTimers.stop(networkName), "networks","build_time")
            if (networksBuilt == numberOfNetworks)
                DatabaseManager.updateTimeField(Left(runId.get), globalTimers.stop("Building"), "runs", "build_time")
                
            runningTimers.start(networkName)
            
            network ! RunNetwork
        
        case RunningComplete(networkId) =>
            val network = sender()
            val networkName = network.path.name
            numberOfNetworksFinished += 1
            DatabaseManager.updateTimeField(Right(networkId), runningTimers.stop(networkName), "networks","run_time")
            if (numberOfNetworksFinished == numberOfNetworks) {
                DatabaseManager.updateTimeField(Left(runId.get), globalTimers.stop("Running"), "runs", "run_time")
                networks.foreach(network =>
                    analysisTimers.start(networkName)
                    network ! StartAnalysis
                )
                println("Saving to DB...")
                RoundDataRouters.saveRemainingData()
            } else if (numberOfNetworksFinished % networksPerBatch == 0) {
                self ! RunBatch
            }
            
        case AnalysisComplete =>
            val network = sender()
            val networkName = network.path.name
            analysisTimers.stop(networkName)
            globalTimers.stop(s"Run")
            
    }
    
    /*
    * ToDo make the algorithm to determine the batch size a better one
    * */
    private def calculateBatches(numberOfNetworks: Int, numberOfAgents: Int): (Int, Int) = {
        if (numberOfAgents >= agentLimit) {
            (numberOfNetworks, 1)
        } else {
            val networksPerBatch = agentLimit / numberOfAgents
            val batches = math.ceil(numberOfNetworks.toDouble / networksPerBatch).toInt
            (batches, networksPerBatch)
        }
    }
}
