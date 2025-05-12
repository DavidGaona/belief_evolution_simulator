package core.simulation.actors

import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import core.model.agent.behavior.bias.*
import core.model.agent.behavior.silence.*
import core.simulation.config.RunMode
import io.db.DatabaseManager
import io.persistence.RoundRouter
import utils.datastructures.UUIDS
import utils.rng.distributions.CustomDistribution
import utils.timers.CustomMultiTimer

import java.util.UUID

enum LoadType:
    case NoLoad
    case NetworkLoad
    case StatelessRunLoad
    case NeighborlessLoad
    case FullRunLoad

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
    biasType: CognitiveBiasType
)

// Mesagges

case object StartRun // Monitor -> Run
case class BuildingComplete(networkId: UUID) // Network -> Run
case class RunningComplete(networkId: UUID, round: Int, result: Int) // Network -> Run
case class ChangeAgentLimit(numberOfAgents: Int) // Monitor -> Run

// Actor ToDo create logs
class Run extends Actor {
    // Collections
    var networks: Array[ActorRef] = null
    var times: Array[Long] = null
    var agentTypeCount: Array[(SilenceStrategyType, SilenceEffectType, Int)] = null
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
    var loadType: LoadType = null
    var totalLoaded: Int = 0
    val preFetchThreshold: Float = 0.75
    
    // Run a specific network
    def this(
        runMetadata: RunMetadata,
        agents: Array[AgentInitialState],
        neighbors: Array[Neighbors],
        name: String
    ) = {
        this()
        
        globalTimers.start(s"Total_time")
        
        runMetadata.runId = if (runMetadata.saveMode.savesToDB) DatabaseManager.createRun(
            runMetadata.runMode,
            runMetadata.saveMode,
            1,
            None,
            None,
            runMetadata.stopThreshold,
            runMetadata.iterationLimit,
            CustomDistribution.toString
            ) else Option(1)
        
        this.runMetadata = runMetadata
        globalTimers.start("Building")
        val networkId: UUID = uuids.v7()
        val network = context.actorOf(Props(new Network(
            networkId,
            runMetadata,
            null,
            null
            )), name)
        if (runMetadata.saveMode.includesNetworks)
            DatabaseManager.createNetwork(networkId, name, runMetadata.runId.get, agents.length)
        networks = Array(network)
        BuildMessage = BuildCustomNetwork(agents, neighbors)
        loadType = LoadType.NoLoad
        buildingTimers.start(network.path.name)
        calculateBatches()
        //network ! BuildCustomNetwork(agents, neighbors)
        
    }
    
    // Run generated networks
    def this(runMetadata: RunMetadata,
        agentTypeCount: Array[(SilenceStrategyType, SilenceEffectType, Int)],
        agentBiases: Array[(Byte, Int)]
    ) = {
        this()
        this.runMetadata = runMetadata
        this.agentTypeCount = agentTypeCount
        this.agentBiases = agentBiases
        initializeGeneratedRun()
        BuildMessage = BuildNetwork
        loadType = LoadType.NoLoad
    }
    
    /* 
        We want to change one of the following 
            Agent Types count (total count stays the same)
            Change Save mode
            Change stop threshold
            Change iteration limit
            Change bias distribution
        All but the first rerun the same exact run but with different stop threshold/mode/iteration limit
        To do this I must reload 
        all the neighbors  
        all agents  
        the initial round
    */
    // Re-run a past run with different parameters
//    def this(runMetadata: RunMetadata,
//        agentTypeCount: Array[(SilenceStrategyType, SilenceEffectType, Int)],
//        agentBiases: Array[(CognitiveBiasType, Float)],
//        runId: Int
//    ) = {
//        this()
//        this.runMetadata = runMetadata
//        this.agentTypeCount = agentTypeCount
//        this.agentBiases = agentBiases
//        initializeGeneratedRun()
//        loadType = LoadType.NetworkLoad
//        BuildMessage = BuildNetworkFromRun(runId)
//    }
//    // Re-run a past network with different parameters
//    def this(runMetadata: RunMetadata,
//        agentTypeCount: Array[(SilenceStrategyType, SilenceEffectType, Int)],
//        agentBiases: Array[(CognitiveBiasType, Float)],
//        networkId: UUID
//    ) = {
//        this()
//        this.runMetadata = runMetadata
//        this.agentTypeCount = agentTypeCount
//        this.agentBiases = agentBiases
//        initializeGeneratedRun()
//        BuildMessage = BuildNetworkFromNetwork(networkId)
//        BuildMessage = LoadType.FullRunLoad
//    }
    
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
            
            if (networksBuilt == runMetadata.numberOfNetworks) {
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
                DatabaseManager.updateTimeField(Left(runMetadata.runId.get), globalTimers.stop("Running"), 
                                                "runs", "run_time")
                RoundRouter.saveRemainingData()
                // ToDo use quick select
                scala.util.Sorting.quickSort(networkBuildTimes)
                scala.util.Sorting.quickSort(networkRunTimes)
                val n = networkRunTimes.length
                println(
                    f"""
                    |----------------------------
                    |Run ${runMetadata.runId.get} with ${
                        runMetadata.runMode match
                            case RunMode.Custom => "Custom network"
                            case _ => f"density ${runMetadata.optionalMetaData.get.density.get}"
                    } and ${runMetadata.numberOfNetworks} networks of ${runMetadata.agentsPerNetwork} agents
                        |Max rounds: $maxRound
                        |Min rounds: $minRound
                        |Avg rounds: ${avgRounds / runMetadata.numberOfNetworks}
                        |Max build time: ${globalTimers.formatDuration(networkBuildTimes.max)}
                        |Min build time: ${globalTimers.formatDuration(networkBuildTimes.min)}
                        |Avg build time: ${globalTimers.formatDuration(networkBuildTimes.sum / n)}
                        |Median build time: ${globalTimers.formatDuration(
                        if (networkBuildTimes.length % 2 == 0) (networkBuildTimes(n / 2 - 1) + networkBuildTimes(n / 2)) / 2
                        else networkBuildTimes(n / 2))
                        }
                        |Max run time: ${globalTimers.formatDuration(networkRunTimes.max)}
                        |Min run time: ${globalTimers.formatDuration(networkRunTimes.min)}
                        |Avg run time: ${globalTimers.formatDuration(networkRunTimes.sum / n)}
                        |Median run time: ${globalTimers.formatDuration(
                        if (networkRunTimes.length % 2 == 0) (networkRunTimes(n / 2 - 1) + networkRunTimes(n / 2)) / 2
                        else networkRunTimes(n / 2))
                        }
                        |Consensus runs: $networksConsensus
                        |Dissensus runs: ${runMetadata.numberOfNetworks - networksConsensus}
                        |----------------------------
                    """.stripMargin
                    )
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
    
    private def fetchBatch(runId: Int, limit: Int, offset: Int): Unit = {
        runMetadata.agentsPerNetwork
    }
}
