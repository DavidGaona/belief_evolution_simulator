package core.simulation.actors

import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import core.model.agent.behavior.bias.*
import core.model.agent.behavior.silence.*
import core.simulation.config.RunMode
import io.db.DatabaseManager
import io.persistence.RoundRouter
import io.web.CustomRunInfo
import utils.datastructures.UUIDS
import utils.rng.distributions.CustomDistribution
import utils.timers.CustomMultiTimer

import java.util.UUID
import scala.collection.mutable
import scala.io.Source

// Saving classes
case class AgentStateLoad(
    networkId: UUID,
    agentId: UUID,
    belief: Float,
    toleranceRadius: Float,
    toleranceOffset: Float,
    stateData: Option[Array[Byte]],
    expressionThreshold: Option[Float],
    openMindedness: Option[Int]
)

case class NeighborsLoad(
    networkId: UUID,
    source: UUID,
    target: UUID,
    influence: Float,
    biasType: Byte
)

// Mesagges

case object StartRun // Monitor -> Run
case class BuildingComplete(networkId: UUID) // Network -> Run
case class RunningComplete(networkId: UUID, round: Int, result: Int) // Network -> Run
case class ChangeAgentLimit(numberOfAgents: Int) // Monitor -> Run

// Actor

/**
 * Run Actor
 * This actor corresponds to a single simulation, it coordinates the execution of
 * its networks. Depending on the limit given by the Monitor actor, it can change
 * the number of simultaneous executing networks. Each network goes through the
 * process of building -> Running -> Stopping. After all networks have executed,
 * some useful stats are shown if in Debug mode before terminating the actor and
 * all of its children (networks).
 **/
class Run extends Actor {
    // Collections
    var networks: Array[ActorRef] = null
    var times: Array[Long] = null
    var agentTypeCount: Array[(Byte, Byte, Int)] = null
    var agentBiases: Array[(Byte, Int)] = null
    
    // Local stats
    val percentagePoints = Seq(10, 25, 50, 75, 90)
    var networksConsensus: Int = 0
    var maxRound: Int = Int.MinValue
    var minRound: Int = Int.MaxValue
    var avgRounds: Int = 0
    var networkRunTimes: Array[Long] = null
    var networkBuildTimes: Array[Long] = null
    
    // Timing
    val globalTimers = new CustomMultiTimer
    val buildingTimers = new CustomMultiTimer
    val runningTimers = new CustomMultiTimer
    
    val uuids = UUIDS()
    
    // Batches
    var batches = 0
    var networksPerBatch = 0
    
    // counts
    var networksBuilt = 0
    var numberOfNetworksFinished = 0
    
    //
    var runMetadata: RunMetadata = null
    
    // Load from existing run
    var agentStates: Option[Array[(UUID, Float, Float, Option[Float], Option[Integer], Float,
      Option[Array[Byte]])]] = None
    var BuildMessage: Any = null
    var totalLoaded: Int = 0
    val preFetchThreshold: Float = 0.75
    
    // Run a custom network
    def this (runMetadata: RunMetadata, customRunInfo: CustomRunInfo) = {
        this()
        
        globalTimers.start(s"Total_time")
        
        runMetadata.runId = if (runMetadata.saveMode.savesToDB) DatabaseManager.createRun(
            runMetadata.runMode, runMetadata.saveMode, 1, None, None, 
            runMetadata.stopThreshold, runMetadata.iterationLimit,
            CustomDistribution.toString
        ) else Option(1)
        
        this.runMetadata = runMetadata
        globalTimers.start("Building")
        val networkId: UUID = uuids.v7()
        val network = context.actorOf(
            Props(new Network(networkId, runMetadata, null, null)) // customRunInfo.networkName
        )
        if (runMetadata.saveMode.includesNetworks) {
            DatabaseManager.createNetwork(networkId, customRunInfo.networkName, runMetadata.runId.get, 
                runMetadata.agentsPerNetwork)
        }
        networks = Array(network)
        BuildMessage = BuildCustomNetwork(customRunInfo)
        networkRunTimes = Array.fill[Long](1)(-1L)
        networkBuildTimes = Array.fill[Long](1)(-1L)
        buildingTimers.start(network.path.name)
        calculateBatches()
    }
    
    // Run generated networks
    def this(runMetadata: RunMetadata,
        agentTypeCount: Array[(Byte, Byte, Int)],
        agentBiases: Array[(Byte, Int)]
    ) = {
        this()
        this.runMetadata = runMetadata
        this.agentTypeCount = agentTypeCount
        this.agentBiases = agentBiases
        initializeGeneratedRun()
        BuildMessage = BuildNetwork
    }
    
    // Run generated network from CSV file
    def this(runMetadata: RunMetadata,
        path: String,
        agentTypeCount: Array[(Byte, Byte, Int)],
        agentBiases: Array[(Byte, Int)]
    ) = {
        this()
        this.runMetadata = runMetadata
        this.agentTypeCount = agentTypeCount
        this.agentBiases = agentBiases
        initializeGeneratedRun()
        val (neighbors, offsets) = parseNetworkBiCSV(path)
        BuildMessage = BuildNetworkFromCSV(neighbors, offsets)
    }
    
    private def initializeGeneratedRun(): Unit = {
        globalTimers.start(s"Total_time")
        runMetadata.runId = if (runMetadata.saveMode.savesToDB) DatabaseManager.createRun(
            runMetadata.runMode,
            runMetadata.saveMode,
            runMetadata.numberOfNetworks,
            runMetadata.optionalMetaData.get.density,
            runMetadata.optionalMetaData.get.degreeDistribution,
            runMetadata.stopThreshold,
            runMetadata.iterationLimit,
            runMetadata.distribution.toString
            ) else Option(1)
        networks = Array.fill[ActorRef](runMetadata.numberOfNetworks)(null)
        networkRunTimes = Array.fill[Long](runMetadata.numberOfNetworks)(-1L)
        networkBuildTimes = Array.fill[Long](runMetadata.numberOfNetworks)(-1L)
    }
    
    // Message handling
    def receive: Receive = {
        case StartRun =>
            calculateBatches()
            
        case BuildingComplete(networkId) =>
            val network = sender()
            val networkName = network.path.name
            val index = networksBuilt
            networksBuilt += 1
            if (networksBuilt == 1) globalTimers.start("Running")
            
            if (runMetadata.saveMode.includesNetworks) {
                networkBuildTimes(index) = buildingTimers.stop(networkName, msg = " building", printDuration = false)
                
                DatabaseManager.updateTimeField(
                    Right(networkId),
                    networkBuildTimes(index),
                    "networks", "build_time"
                )
            } else {
                networkBuildTimes(index) = buildingTimers.stop(networkName, msg = " building")
            }
            
            if (runMetadata.saveMode.savesToDB && networksBuilt == runMetadata.numberOfNetworks) {
                DatabaseManager.updateTimeField(Left(runMetadata.runId.get), globalTimers.stop("Building"), "runs",
                    "build_time")
            }
            
            runningTimers.start(networkName)
            network ! RunNetwork
        
        case RunningComplete(networkId, round, result) =>
            // Run statistics
            networksConsensus += result
            maxRound = math.max(maxRound, round)
            minRound = math.min(minRound, round)
            avgRounds += round
            
            val network = sender()
            val networkName = network.path.name
            val index = numberOfNetworksFinished
            numberOfNetworksFinished += 1
            
            val currentPercentage = (numberOfNetworksFinished.toDouble / runMetadata.numberOfNetworks * 100).toInt
            val hasReported = currentPercentage != ((numberOfNetworksFinished - 1).toDouble / runMetadata.numberOfNetworks * 100).toInt
            
            if (percentagePoints.contains(currentPercentage) && hasReported) {
                println(s"Run ${runMetadata.runId.get} $numberOfNetworksFinished($currentPercentage%) Complete")
            }
            
            if (runMetadata.saveMode.includesNetworks) {
                networkRunTimes(index) = runningTimers.stop(networkName, msg = " running", printDuration = false)
                DatabaseManager.updateTimeField(Right(networkId), networkRunTimes(index), "networks", "run_time")
            } else {
                networkRunTimes(index) = runningTimers.stop(networkName, msg = " running")
            }
            
            if (numberOfNetworksFinished < runMetadata.numberOfNetworks) {
                if ((numberOfNetworksFinished + networksPerBatch) <= runMetadata.numberOfNetworks)
                    buildNetwork(numberOfNetworksFinished + networksPerBatch - 1)
            } else {
                if (runMetadata.saveMode.savesToDB) {
                    DatabaseManager.updateTimeField(Left(runMetadata.runId.get), globalTimers.stop("Running"),
                        "runs", "run_time")
                }
                RoundRouter.saveRemainingData()
                // Show only on debug mode
                if (!runMetadata.saveMode.savesToDB) {
                    scala.util.Sorting.quickSort(networkBuildTimes)
                    scala.util.Sorting.quickSort(networkRunTimes)
                    val n = networkRunTimes.length
                    println(
                        f"""
                           |----------------------------
                           |Run ${runMetadata.runId.get} with ${
                            runMetadata.runMode match
                                case RunMode.CUSTOM => "Custom network"
                                case RunMode.CSV => "CSV network"
                                case _ => f"density ${runMetadata.optionalMetaData.get.density.get}"
                        } and ${runMetadata.numberOfNetworks} networks of ${runMetadata.agentsPerNetwork} agents
                           |Max rounds: $maxRound
                           |Min rounds: $minRound
                           |Avg rounds: ${avgRounds / runMetadata.numberOfNetworks}
                           |Max build time: ${globalTimers.formatDuration(networkBuildTimes.max)}
                           |Min build time: ${globalTimers.formatDuration(networkBuildTimes.min)}
                           |Avg build time: ${globalTimers.formatDuration(networkBuildTimes.sum / n)}
                           |Median build time: ${
                            globalTimers.formatDuration(
                                if (networkBuildTimes.length % 2 == 0) (networkBuildTimes(n / 2 - 1) + networkBuildTimes(n / 2)) / 2
                                else networkBuildTimes(n / 2))
                        }
                           |Max run time: ${globalTimers.formatDuration(networkRunTimes.max)}
                           |Min run time: ${globalTimers.formatDuration(networkRunTimes.min)}
                           |Avg run time: ${globalTimers.formatDuration(networkRunTimes.sum / n)}
                           |Median run time: ${
                            globalTimers.formatDuration(
                                if (networkRunTimes.length % 2 == 0) (networkRunTimes(n / 2 - 1) + networkRunTimes(n / 2)) / 2
                                else networkRunTimes(n / 2))
                        }
                           |Consensus runs: $networksConsensus
                           |Dissensus runs: ${runMetadata.numberOfNetworks - networksConsensus}
                           |----------------------------
                                        """.stripMargin
                    )
                }
                context.parent ! RunComplete
            }
            
            // Clean up network agents
            network ! PoisonPill
//            if (runMetadata.saveMode.includesNetworks) {
//
//            }
        
        case ChangeAgentLimit(newAgentLimit: Int) =>
            
    }
    
    // ToDo coordinate for global agent limit
    private def calculateBatches(): Unit = {
        if (runMetadata.agentsPerNetwork >= runMetadata.agentLimit) {
            batches = runMetadata.numberOfNetworks
            networksPerBatch = 1
        } else {
            networksPerBatch = runMetadata.agentLimit / runMetadata.agentsPerNetwork
            networksPerBatch = math.min(networksPerBatch, runMetadata.numberOfNetworks)
            batches = math.ceil(runMetadata.numberOfNetworks.toDouble / networksPerBatch).toInt
        }
        buildNetworkBatch()
    }
    
    private def buildNetworkBatch(): Unit = {
        if (networksBuilt == 0) globalTimers.start("Building")
        var i = networksBuilt
        while (i < networksPerBatch) {
            buildNetwork(i)
            i += 1
        }
    }
    
    @inline
    private def buildNetwork(index: Int = networksBuilt): Unit = {
        val networkId = uuids.v7()
        networks(index) = context.actorOf(Props(new Network(
            networkId,
            runMetadata,
            agentTypeCount,
            agentBiases
            )), s"N${index + 1}")
        if (runMetadata.saveMode.includesNetworks) {
            DatabaseManager.createNetwork(networkId, s"N${index + 1}", runMetadata.runId.get,
                                          runMetadata.agentsPerNetwork)
        }
        
        buildingTimers.start(networks(index).path.name)
        networks(index) ! BuildMessage
    }
    
    private def parseNetworkCSV(path: String): (Array[Int], Array[Int]) = {
        val source = Source.fromFile(path)
        val lines = source.getLines().toArray
        source.close()
        
        val equivalentIndex = mutable.Map[Int, Int]()
        val outgoingCount = mutable.Map[Int, Int]()
        var numberOfNeighbors = 0
        var nextIndex = 0

        var i = 1
        while (i < lines.length) {
            val line = lines(i).trim
            if (line.nonEmpty) {
                val parts = line.split(",")
                val sourceId = parts(0).toInt
                val targetId = parts(1).toInt
                
                if (!equivalentIndex.contains(sourceId)) {
                    equivalentIndex(sourceId) = nextIndex
                    outgoingCount(nextIndex) = 0
                    nextIndex += 1
                }
                
                if (!equivalentIndex.contains(targetId)) {
                    equivalentIndex(targetId) = nextIndex
                    outgoingCount(nextIndex) = 0
                    nextIndex += 1
                }
                
                // Count outgoing edge for source
                val sourceInternalId = equivalentIndex(sourceId)
                outgoingCount(sourceInternalId) = outgoingCount(sourceInternalId) + 1
                numberOfNeighbors += 1
            }
            i += 1
        }
        
        val numNodes = nextIndex
        val neighborsArr = new Array[Int](numberOfNeighbors)
        val offsetsArr = new Array[Int](numNodes)
        
        var currentOffset = 0
        var nodeId = 0
        while (nodeId < numNodes) {
            currentOffset += outgoingCount(nodeId)
            offsetsArr(nodeId) = currentOffset - 1
            nodeId += 1
        }
        
        val currentPos = new Array[Int](numNodes)
        nodeId = 0
        while (nodeId < numNodes) {
            currentPos(nodeId) = if (nodeId == 0) 0 else offsetsArr(nodeId - 1) + 1
            nodeId += 1
        }
        
        i = 1
        while (i < lines.length) {
            val line = lines(i).trim
            if (line.nonEmpty) {
                val parts = line.split(",")
                val sourceId = parts(0).toInt
                val targetId = parts(1).toInt
                
                val sourceInternalId = equivalentIndex(sourceId)
                val targetInternalId = equivalentIndex(targetId)
                
                neighborsArr(currentPos(sourceInternalId)) = targetInternalId
                currentPos(sourceInternalId) += 1
            }
            i += 1
        }
        
        (neighborsArr, offsetsArr)
    }
    
    private def parseNetworkBiCSV(path: String): (Array[Int], Array[Int]) = {
        val source = Source.fromFile(path)
        val lines = source.getLines().toArray
        source.close()
        
        val equivalentIndex = mutable.Map[Int, Int]()
        val outgoingCount = mutable.Map[Int, Int]()
        var numberOfNeighbors = 0
        var nextIndex = 0
        
        var i = 1
        while (i < lines.length) {
            val line = lines(i).trim
            if (line.nonEmpty) {
                val parts = line.split(",")
                val sourceId = parts(0).toInt
                val targetId = parts(1).toInt
                
                if (!equivalentIndex.contains(sourceId)) {
                    equivalentIndex(sourceId) = nextIndex
                    outgoingCount(nextIndex) = 0
                    nextIndex += 1
                }
                
                if (!equivalentIndex.contains(targetId)) {
                    equivalentIndex(targetId) = nextIndex
                    outgoingCount(nextIndex) = 0
                    nextIndex += 1
                }
                
                val sourceInternalId = equivalentIndex(sourceId)
                val targetInternalId = equivalentIndex(targetId)
                
                outgoingCount(sourceInternalId) = outgoingCount(sourceInternalId) + 1
                outgoingCount(targetInternalId) = outgoingCount(targetInternalId) + 1
                
                numberOfNeighbors += 2
            }
            i += 1
        }
        
        val numNodes = nextIndex
        val neighborsArr = new Array[Int](numberOfNeighbors)
        val offsetsArr = new Array[Int](numNodes)
        
        var currentOffset = 0
        var nodeId = 0
        while (nodeId < numNodes) {
            currentOffset += outgoingCount(nodeId)
            offsetsArr(nodeId) = currentOffset - 1
            nodeId += 1
        }
        
        val currentPos = new Array[Int](numNodes)
        nodeId = 0
        while (nodeId < numNodes) {
            currentPos(nodeId) = if (nodeId == 0) 0 else offsetsArr(nodeId - 1) + 1
            nodeId += 1
        }
        
        i = 1
        while (i < lines.length) {
            val line = lines(i).trim
            if (line.nonEmpty) {
                val parts = line.split(",")
                val sourceId = parts(0).toInt
                val targetId = parts(1).toInt
                
                val sourceInternalId = equivalentIndex(sourceId)
                val targetInternalId = equivalentIndex(targetId)
                
                neighborsArr(currentPos(sourceInternalId)) = targetInternalId
                currentPos(sourceInternalId) += 1
                
                neighborsArr(currentPos(targetInternalId)) = sourceInternalId
                currentPos(targetInternalId) += 1
            }
            i += 1
        }
        
        (neighborsArr, offsetsArr)
    }
    
    private def fetchBatch(runId: Int, limit: Int, offset: Int): Unit = {
        runMetadata.agentsPerNetwork
    }
}
