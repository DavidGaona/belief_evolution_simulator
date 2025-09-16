package core.simulation.actors

import akka.actor.{Actor, ActorRef, Props}
import akka.util.Timeout
import core.model.agent.behavior.bias.CognitiveBiases.Bias
import core.model.agent.behavior.silence.*
import core.model.agent.behavior.silence.SilenceEffects.SilenceEffect
import core.model.agent.behavior.silence.SilenceStrategies.SilenceStrategy
import core.simulation.config.{AppMode, GlobalState}
import io.db.DatabaseManager
import io.web.Server
import io.persistence.actors.{AgentStaticDataSaver, NeighborSaver}
import io.web.CustomRunInfo
import utils.datastructures.{FenwickTree, UUIDS}
import utils.logging.Logger
import utils.rng.distributions.BimodalDistribution

import java.io.{File, FileWriter, PrintWriter}
import java.nio.ByteBuffer
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.util.Using
// Network

// ============================================================================
// MESSAGES
// ============================================================================
case class BuildCustomNetwork(customRunInfo: CustomRunInfo) // Monitor -> Network
case class AgentUpdated(maxBelief: Float, minBelief: Float, isStable: Boolean) // Agent -> network
case class BuildNetworkFromCSV(neighborsArr: Array[Int], offsetsArr: Array[Int]) // Monitor -> network

case object BuildNetwork // Monitor -> network
case object RunNetwork // Monitor -> network
case object RunFirstRound // Agent -> Network
case object SaveRemainingData // Network -> AgentRoundDataSaver
case object ActorFinished // Agent -> Network

// ============================================================================
// ACTOR
// ============================================================================
class Network(networkId: UUID, runMetadata: RunMetadata,
    agentTypeCount: Array[(SilenceStrategy, SilenceEffect, Int)],
    agentBiases: Array[(Bias, Int)])
  extends Actor {
    // ============================================================================
    // AGENT DISTRIBUTION AND ACTORS
    // ============================================================================
    val numberOfAgentActors: Int = math.min(32, (runMetadata.agentsPerNetwork + 31) / 32)
    val agentsPerActor: Array[Int] = new Array[Int](numberOfAgentActors);calculateAgentsPerActor()
    val bucketStart: Array[Int] = new Array[Int](numberOfAgentActors);calculateBucketStarts()
    val agents: Array[ActorRef] = Array.ofDim[ActorRef](numberOfAgentActors)
    val agentsIds: Array[UUID] = if (GlobalState.APP_MODE.usesLegacyDB) {
        val arr = Array.ofDim[UUID](runMetadata.agentsPerNetwork)
        val uuids = UUIDS()
        uuids.v7Bulk(arr)
        arr
    }
    else null
    
    // ============================================================================
    // BELIEF AND COMMUNICATION BUFFERS (Double-buffered)
    // ============================================================================
    val beliefBuffer1: Array[Float] = Array.fill(runMetadata.agentsPerNetwork)(-1f)
    val beliefBuffer2: Array[Float] = Array.fill(runMetadata.agentsPerNetwork)(-1f)
    val privateBeliefs: Array[Float] = Array.fill(runMetadata.agentsPerNetwork)(-1f)
    val speakingBuffer1: Array[Byte] = Array.fill(runMetadata.agentsPerNetwork)(1)
    val speakingBuffer2: Array[Byte] = Array.fill(runMetadata.agentsPerNetwork)(1)
    var bufferSwitch: Boolean = true
    
    // ============================================================================
    // STATIC AGENT PROPERTIES AND BEHAVIORS
    // ============================================================================
    val tolRadius: Array[Float] = Array.fill(runMetadata.agentsPerNetwork)(0.1f)
    val tolOffset: Array[Float] = Array.fill(runMetadata.agentsPerNetwork)(0.05f)
    val silenceStrategy: Array[SilenceStrategy] = new Array[SilenceStrategy](runMetadata.agentsPerNetwork)
    val silenceEffect: Array[SilenceEffect] = new Array[SilenceEffect](runMetadata.agentsPerNetwork)
    val hasMemory: Array[Byte] = new Array[Byte](runMetadata.agentsPerNetwork)
    val timesStable: Array[Int] = new Array[Int](runMetadata.agentsPerNetwork)
    
    // ============================================================================
    // CONDITIONAL AGENT PROPERTIES - Only allocated if agent types require them
    // ============================================================================
    private val hasThreshold: Boolean = agentTypeCount != null && agentTypeCount.exists(_._1 == SilenceStrategies.THRESHOLD)
    private val hasConfidence: Boolean = agentTypeCount != null && agentTypeCount.exists(_._1 == SilenceStrategies.CONFIDENCE)
    private val hasPublicBelief: Boolean = agentTypeCount != null && agentTypeCount.exists(_._2 == SilenceEffects.MEMORY)

    // Fix by accessing enum constants directly from CognitiveBiases object
    private val hasConfirmationBias = agentBiases != null && agentBiases.exists(_._1 == core.model.agent.behavior.bias.CognitiveBiases.CONFIRMATION)
    private val hasBackfireBias = agentBiases != null && agentBiases.exists(_._1 == core.model.agent.behavior.bias.CognitiveBiases.BACKFIRE)
    private val hasAuthorityBias = agentBiases != null && agentBiases.exists(_._1 == core.model.agent.behavior.bias.CognitiveBiases.AUTHORITY)
    private val hasInsularBias = agentBiases != null && agentBiases.exists(_._1 == core.model.agent.behavior.bias.CognitiveBiases.INSULAR)
    var threshold: Array[Float] = if (hasThreshold) new Array[Float](runMetadata.agentsPerNetwork) else null
    var confidence: Array[Float] = if (hasConfidence) new Array[Float](runMetadata.agentsPerNetwork) else null
    var confidenceThreshold: Array[Float] = if (hasConfidence) new Array[Float](runMetadata.agentsPerNetwork) else null
    var publicBelief: Array[Float] = if (hasPublicBelief) new Array[Float](runMetadata.agentsPerNetwork) else null
    
    // ============================================================================
    // NETWORK TOPOLOGY (Size = -m^2 - m + 2mn or m(m-1) + (n - m) * 2m)
    // ============================================================================
    var neighborsRefs: Array[Int] = null
    var neighborsWeights: Array[Float] = null
    var neighborBiases: Array[Bias] = null
    val indexOffset: Array[Int] = new Array[Int](runMetadata.agentsPerNetwork)
    
    // ============================================================================
    // SIMULATION STATE AND CONTROL
    // ============================================================================
    var round: Int = 0
    var pendingResponses: Int = 0
    var finishedIterating: Boolean = false
    var minBelief: Float = 2.0f
    var maxBelief: Float = -1.0f
    var shouldUpdate: Boolean = false
    var shouldContinue: Boolean = false
    
    // ============================================================================
    // DATA PERSISTENCE
    // ============================================================================
    var neighborSaver: ActorRef = null
    var agentStaticDataSaver: ActorRef = null
    var finishState: Int = 0
    if (runMetadata.saveMode.includesNeighbors) {
        finishState += 1
        neighborSaver = context.actorOf(Props(
            new NeighborSaver(numberOfAgentActors)
        ), name = s"NeighborSaver${self.path.name}")
    }
    if (runMetadata.saveMode.includesAgents) {
        finishState += 1
        agentStaticDataSaver = context.actorOf(Props(
            new AgentStaticDataSaver(numberOfAgentActors, networkId)
        ), name = s"StaticSaver_${self.path.name}")
    }
    
    // ============================================================================
    // UTILITIES
    // ============================================================================
    val bimodal = new BimodalDistribution(0.25, 0.75)
    var names: Array[String] = null
    implicit val timeout: Timeout = Timeout(600.seconds)
    
    def receive: Receive = building
    
    // Building state
    private def building: Receive = {
        case BuildCustomNetwork(customRunInfo) =>
            
            val arrayLength = beliefBuffer1.length
            Array.copy(customRunInfo.agentBeliefs, 0, privateBeliefs, 0, arrayLength)
            Array.copy(customRunInfo.agentBeliefs, 0, beliefBuffer1, 0, arrayLength)
            Array.copy(customRunInfo.agentToleranceRadii, 0, tolRadius, 0, arrayLength)
            Array.copy(customRunInfo.agentToleranceOffsets, 0, tolOffset, 0, arrayLength)
            names = customRunInfo.agentNames
            
            // Strategies
            Array.copy(customRunInfo.agentBeliefs, 0, beliefBuffer1, 0, arrayLength)
            Array.copy(customRunInfo.agentBeliefs, 0, beliefBuffer1, 0, arrayLength)
            for (i <- 0 until runMetadata.agentsPerNetwork) {
                silenceStrategy(i) = customRunInfo.agentSilenceStrategy(i)
                silenceEffect(i) = customRunInfo.agentSilenceEffect(i)
                hasMemory(i) = if (silenceEffect(i) == SilenceEffects.MEMORY) 1 else 0
            }
            
            // Neighbors
            val neighborsLength = customRunInfo.influences.length
            Array.copy(customRunInfo.indexOffset, 0, indexOffset, 0, arrayLength)
            neighborsRefs = customRunInfo.target
            neighborsWeights = customRunInfo.influences
            neighborBiases = customRunInfo.bias
            
            for (i <- 0 until numberOfAgentActors) {
                val index = i
                agents(i) = context.actorOf(Props(
                    new AgentProcessor(
                        agentsIds, silenceStrategy, silenceEffect, threshold, confidence,
                        confidenceThreshold, runMetadata, beliefBuffer1, beliefBuffer2,
                        speakingBuffer1, speakingBuffer2, privateBeliefs, publicBelief,
                        tolRadius, tolOffset, indexOffset, timesStable,
                        neighborsRefs, neighborsWeights, neighborBiases, hasMemory, None,
                        networkId, agentsPerActor(index), bucketStart(index), names
                    )
                ), s"${self.path.name}_A$i")
                agents(i) ! MarkAsCustomRun
            }
            
            
            context.become(running)
            context.parent ! BuildingComplete(networkId)
        
        case BuildNetworkFromCSV(neighborsArr, offsetsArr) =>
            neighborsRefs = neighborsArr
            Array.copy(offsetsArr, 0, indexOffset, 0, offsetsArr.length)
            neighborsWeights = new Array[Float](neighborsRefs.length)
            neighborBiases = new Array[Bias](neighborsRefs.length)
            
            val agentsRemaining: Array[Int] = agentTypeCount.map(_._3)
            var agentRemainingCount = runMetadata.agentsPerNetwork
            
            var i = 0
            while (i < numberOfAgentActors) {
                // Get the proportion of agents
                val agentTypes = getNextBucketDistribution(agentsRemaining, agentsPerActor(i), agentRemainingCount)
                
                // Set the agent types
                var j = 0
                var total = 0
                while (j < agentTypes.length) {
                    var k = 0
                    while (k < agentTypes(j)) {
                        silenceStrategy(total + bucketStart(i)) = agentTypeCount(j)._1
                        val effect = agentTypeCount(j)._2
                        silenceEffect(total + bucketStart(i)) = effect
                        hasMemory(total + bucketStart(i)) = if (effect == SilenceEffects.MEMORY) 1 else 0
                        k += 1
                        total += 1
                    }
                    j += 1
                }
                agentRemainingCount -= agentsPerActor(i)
                val biasCounts = new mutable.HashMap[Bias, Int]()
                agentBiases.foreach((key, value) => biasCounts.put(key, value))
                // Create the agent actor
                val index = i
                agents(i) = context.actorOf(Props(
                    new AgentProcessor(
                        agentsIds, silenceStrategy, silenceEffect, threshold, confidence,
                        confidenceThreshold, runMetadata, beliefBuffer1, beliefBuffer2,
                        speakingBuffer1, speakingBuffer2, privateBeliefs, publicBelief,
                        tolRadius, tolOffset, indexOffset, timesStable,
                        neighborsRefs, neighborsWeights, neighborBiases, hasMemory,
                        Some(biasCounts), networkId, agentsPerActor(index), bucketStart(index), null
                    )
                ), s"${self.path.name}_A$i")
                i += 1
            }
            
            context.become(running)
            context.parent ! BuildingComplete(networkId)
            
        
        case BuildNetwork =>
            // ============================================================================
            // NETWORK TOPOLOGY INITIALIZATION
            // ============================================================================
            val density = runMetadata.optionalMetaData.get.density.get
            // Size of arrays: -m^2 - m + 2mn <-> m(m-1) + (n - m) * 2m
            val size = (density * (density - 1)) + ((runMetadata.agentsPerNetwork - density) * (2 * density))
            neighborsRefs = new Array[Int](size)
            neighborsWeights = new Array[Float](size)
            neighborBiases = new Array[Bias](size)
            
            val fenwickTree = new FenwickTree(
                runMetadata.agentsPerNetwork,
                density,
                runMetadata.optionalMetaData.get.degreeDistribution.get - 2,
                runMetadata.seed + runMetadata.runID + self.path.name.substring(1).toLong
            )
            
            // ============================================================================
            // AGENT ACTOR CREATION AND TYPE DISTRIBUTION
            // ============================================================================
            val agentsRemaining: Array[Int] = agentTypeCount.map(_._3)
            var agentRemainingCount = runMetadata.agentsPerNetwork
            
            var i = 0
            while (i < numberOfAgentActors) {
                // Distribute agent types proportionally across actors
                val agentTypes = getNextBucketDistribution(agentsRemaining, agentsPerActor(i), agentRemainingCount)
                
                // Assign behavioral strategies to agents in this actor's range
                var j = 0
                var total = 0
                while (j < agentTypes.length) {
                    var k = 0
                    while (k < agentTypes(j)) {
                        silenceStrategy(total + bucketStart(i)) = agentTypeCount(j)._1
                        val effect = agentTypeCount(j)._2
                        silenceEffect(total + bucketStart(i)) = effect
                        hasMemory(total + bucketStart(i)) = if (effect == SilenceEffects.MEMORY) 1 else 0
                        k += 1
                        total += 1
                    }
                    j += 1
                }
                // Create agent actor with bias distribution
                agentRemainingCount -= agentsPerActor(i)
                val biasCounts = new mutable.HashMap[Bias, Int]()
                agentBiases.foreach((key, value) => biasCounts.put(key, value))
                // Create the agent actor
                val index = i
                agents(i) = context.actorOf(Props(
                    new AgentProcessor(
                        agentsIds, silenceStrategy, silenceEffect, threshold, confidence,
                        confidenceThreshold, runMetadata, beliefBuffer1, beliefBuffer2,
                        speakingBuffer1, speakingBuffer2, privateBeliefs, publicBelief,
                        tolRadius, tolOffset, indexOffset, timesStable,
                        neighborsRefs, neighborsWeights, neighborBiases, hasMemory,
                        Some(biasCounts), networkId, agentsPerActor(index), bucketStart(index), null
                    )
                ), s"${self.path.name}_A$i")
                i += 1
            }
            
            // ============================================================================
            // NETWORK CONNECTIVITY GENERATION
            // ============================================================================
            
            // Phase 1: Create dense core (first density+1 agents fully connected)
            var count = 0
            i = 0
            while (i < density + 1) { 
                var j = 0
                while (j < (density + 1)) {
                    if (j != i) {
                        neighborsRefs(count) = j
                        count += 1
                    }
                    j += 1
                }
                indexOffset(i) += density // Each core agent has 'density' neighbors
                i += 1
            }
            
            // Phase 2: Connect remaining agents using Fenwick tree sampling
            while (i < runMetadata.agentsPerNetwork) {
                fenwickTree.pickRandomsInto(neighborsRefs, count)
                var j = count
                while (j < (count + density)) {
                    indexOffset(neighborsRefs(j)) += 1 // Increment atractiveness score for selected neighbors
                    j += 1
                }
                indexOffset(i) = density // Increment own atractiveness score
                count += density
                i += 1
            }
            
            // ============================================================================
            // ADJACENCY LIST CONSTRUCTION (Convert to Compressed Sparse Row(CSR) format)
            // ============================================================================
            
            // Convert indexOffset to cumulative sum (CSR row pointers)
            var prev = indexOffset(0)
            indexOffset(0) = 0
            
            i = 1
            while (i < runMetadata.agentsPerNetwork) {
                val curr = indexOffset(i)
                indexOffset(i) = indexOffset(i - 1) + prev
                prev = curr
                i += 1
            }
            
            // Create bidirectional adjacency using temporary copy
            val temp = new Array[Int](neighborsRefs.length)
            System.arraycopy(neighborsRefs, 0, temp, 0, neighborsRefs.length)
            
            // Fill adjacency list for dense core agents
            i = 0
            count = 0
            while (i < (density + 1)) {
                var j = count
                while (j < (count + density)) {
                    neighborsRefs(indexOffset(temp(j))) = i
                    indexOffset(temp(j)) += 1
                    j += 1
                }
                count += density
                i += 1
            }
            
            // Fill adjacency list for remaining agents (bidirectional edges)
            while (i < runMetadata.agentsPerNetwork) {
                var j = count
                while (j < (density + count)) {
                    neighborsRefs(indexOffset(i)) = temp(j)
                    indexOffset(i) += 1
                    if (i > temp(i)) {
                        neighborsRefs(indexOffset(temp(j))) = i
                        indexOffset(temp(j)) += 1
                    }
                    j += 1
                }
                count += density
                i += 1
            }
            
            // ============================================================================
            // FINALIZATION
            // ============================================================================
            context.become(running)
            context.parent ! BuildingComplete(networkId)
            
        
    }
    
    // Running State
    private def running: Receive = {
        case RunNetwork =>
            // Remove comment to export round data to csv, in big networks the csv can be VERY big
            // exportAgentDataToCSV("rt-pol-evo.csv", round, privateBeliefs, speakingBuffer1)
            pendingResponses = agents.length
            var i = 0
            while (i < agents.length) {
                agents(i) ! FirstUpdate(neighborSaver, agentStaticDataSaver, agents)
                i += 1
            }
        
        case RunFirstRound =>
            pendingResponses -= 1
            if (pendingResponses == 0) {
                logAgentRoundState()
                round += 1
                sendNeighbors()
                runRound()
                pendingResponses = agents.length
            }
        
        case AgentUpdated(maxActorBelief, minActorBelief, isStable) =>
            pendingResponses -= 1
            // If isStable true then we don't continue as we are stable meaning no agent belief changes
            if (!isStable) shouldContinue = true
            maxBelief = math.max(maxBelief, maxActorBelief)
            minBelief = math.min(minBelief, minActorBelief)
            if (pendingResponses == 0) {
                // Remove comment to export round data to csv, in big networks the csv can be VERY big
                // if (bufferSwitch) {
                //    exportAgentDataToCSV("rt-pol-evo.csv", round, privateBeliefs, speakingBuffer1)
                // } else {
                //    exportAgentDataToCSV("rt-pol-evo.csv", round, privateBeliefs, speakingBuffer2)
                // }
                logAgentRoundState()
                Logger.log(s"Round: $round, Max: $maxBelief, Min: $minBelief")
                if ((maxBelief - minBelief) < runMetadata.stopThreshold) {
                    Logger.log(s"Consensus! \nFinal round: $round\n" +
                      s"Belief diff: of ${maxBelief - minBelief} ($maxBelief - $minBelief)")
                    context.parent ! RunningComplete(networkId, round, 1)
                    if (runMetadata.saveMode.includesNetworks) DatabaseManager.updateNetworkFinalRound(networkId, round, true)
                    if (runMetadata.saveMode.includesLastRound) agents.foreach { agent => agent ! SnapShotAgent }
                    
                    if (finishState == 0) context.stop(self)
                    finishedIterating = true
                }
                else if (round == runMetadata.iterationLimit || !shouldContinue) {
                    Logger.log(s"Dissensus \nFinal round: $round\n" +
                      s"Belief diff: of ${maxBelief - minBelief} ($maxBelief - $minBelief)")
                    context.parent ! RunningComplete(networkId, round, 0)
                    if (runMetadata.saveMode.includesNetworks) DatabaseManager.updateNetworkFinalRound(networkId, round, false)
                    if (runMetadata.saveMode.includesLastRound) agents.foreach { agent => agent ! SnapShotAgent }
                    if (finishState == 0) context.stop(self)
                    finishedIterating = true
                } else {
                    round += 1
                    runRound()
                    minBelief = 2.0
                    maxBelief = -1.0
                }
                pendingResponses = agents.length
            }
        
        case ActorFinished =>
            finishState -= 1
            if (finishState == 0 && finishedIterating) {
                context.stop(self)
            }
    }
    
    private def runRound(): Unit = {
        var i = 0
        val msg = if (bufferSwitch) UpdateAgent1R else UpdateAgent2R
        while (i < agents.length) {
            agents(i) ! msg
            i += 1
        }
        shouldContinue = false
        bufferSwitch = !bufferSwitch
    }
    
    
    // Helper function Functions:
    
    private def getNextBucketDistribution(agentsRemaining: Array[Int], bucketSize: Int, totalAgentsRemaining: Int): Array[Int] = {
        val result = new Array[Int](agentsRemaining.length)
        val floatPart = new Array[Double](agentsRemaining.length)
        var i = 0
        while (i < agentsRemaining.length) {
            val fullResult = (agentsRemaining(i).toLong * bucketSize).toDouble / totalAgentsRemaining
            val intPart = math.floor(fullResult).toInt
            val decimalPart = fullResult - intPart
            result(i) = intPart
            agentsRemaining(i) -= intPart
            floatPart(i) = decimalPart
            i += 1
        }
        
        val remainder = floatPart.zipWithIndex.sortBy(-_._1)
        val missing = math.round(floatPart.sum).toInt
        i = 0
        while (i < missing) {
            result(remainder(i)._2) += 1
            agentsRemaining(remainder(i)._2) -= 1
            i += 1
        }
        
        result
    }
    
    @inline private def calculateAgentsPerActor(): Unit = {
        var i = 0
        var remainingToAssign = runMetadata.agentsPerNetwork
        while (0 < remainingToAssign) {
            agentsPerActor(i) += math.min(32, remainingToAssign)
            remainingToAssign -= 32
            i = (i + 1) % numberOfAgentActors
        }
    }
    
    @inline private def calculateBucketStarts(): Unit = {
        for (i <- 1 until numberOfAgentActors)
            bucketStart(i) = agentsPerActor(i - 1) + bucketStart(i - 1)
    }
    
    @inline def getAgentActor(index: Int): ActorRef = {
        var i = 0
        while (i < bucketStart.length - 1) {
            if (index < bucketStart(i + 1)) return agents(i)
            i += 1
        }
        agents(i)
    }
    
    /**
     * Logs the state of agent rounds during execution if the application is in DEBUG_VERBOSE mode.
     * This includes the current round number, private beliefs, public belief buffers, and speaking buffers.
     * The method outputs detailed logging for debugging purposes to help track agent state transitions per round.
     *
     * @return Unit
     */
    inline private def logAgentRoundState(): Unit = {
        if (GlobalState.APP_MODE.hasSimulationLogs) {
            Logger.logSimulation(s"Round: $round")
            Logger.logSimulation(privateBeliefs.mkString("Private(", ", ", ")"))
            if (bufferSwitch) Logger.logSimulation(beliefBuffer2.mkString("Public(", ", ", ")"))
            else Logger.logSimulation(beliefBuffer1.mkString("Public(", ", ", ")"))
            if (bufferSwitch) Logger.logSimulation(speakingBuffer2.mkString("Speaking(", ", ", ")"))
            else Logger.logSimulation(speakingBuffer1.mkString("Speaking(", ", ", ")"))
            Logger.logSimulation("")
        }
    }
    
    /**
     * Gather neighbor data to then send via web API.
     *
     * '''Buffer Layout:'''
     *
     * '''HEADER (24 bytes total):'''
     *  - networkId_msb: 8 bytes (Long) - Most significant bits of network UUID
     *  - networkId_lsb: 8 bytes (Long) - Least significant bits of network UUID
     *  - runId: 8 bytes (Long) - Unique identifier for this simulation run
     *  - numberOfAgents: 4 bytes (Int) - Count of agents in the simulation
     *  - numberOfNeighbors: 4 bytes (Int) - Count of neighbor relationships
     *
     * '''BODY (variable bytes):'''
     *  - indexOffset: (numberOfAgents * 4) bytes (Int[]) - Agent index offsets
     *  - neighborsRefs: (numberOfNeighbors * 4) bytes (Int[]) - Neighbor reference indices
     *  - neighborsWeights: (numberOfNeighbors * 4) bytes (Float[]) - Connection weights
     *  - neighborBiases: (numberOfNeighbors * 1) bytes (Bias[]/Byte[]) - Cognitive bias types
     *
     * @note Total buffer size: 24 + (numberOfAgents * 4) + (numberOfNeighbors * 9) bytes
     */
    private def sendNeighbors(): Unit = {
        val numberOfAgents = indexOffset.length
        val numberOfNeighbors = neighborsRefs.length
        val buffer = ByteBuffer.allocate(24 + (numberOfAgents * 4) + (numberOfNeighbors * 8))
        
        // HEADER (24 bytes total)
        buffer.putLong(networkId.getMostSignificantBits)
        buffer.putLong(networkId.getLeastSignificantBits)
        buffer.putLong(runMetadata.runID)
        buffer.putInt(indexOffset.length)
        buffer.putInt(neighborsRefs.length)
        
        // BODY (variable bytes)
        buffer.asIntBuffer().put(indexOffset)
        buffer.asIntBuffer().put(neighborsRefs)
        buffer.asFloatBuffer().put(neighborsWeights)
        buffer.put(neighborBiases.asInstanceOf[Array[Byte]])
        
        Server.sendNeighborBinaryData(runMetadata.channelId, buffer)
    }
    
    /**
     * Exports agent data to CSV file, appending if file exists
     *
     * @param filePath       Path to the CSV file
     * @param round          Current round number
     * @param privateBeliefs Array of agent beliefs
     * @param speakingBuffer Array of agent speaking status
     */
    def exportAgentDataToCSV(
        filePath: String,
        round: Int,
        privateBeliefs: Array[Float],
        speakingBuffer: Array[Byte]
    ): Unit = {
        
        val file = new File(filePath)
        val fileExists = file.exists()
        
        try {
            Logger.log(s"Exporting data for round $round to $filePath (${privateBeliefs.length} agents)")
            
            Using(new PrintWriter(new FileWriter(file, true))) { writer =>
                if (!fileExists || file.length() == 0) {
                    Logger.log(s"Creating new CSV file with headers: $filePath")
                    writer.println("id,round,belief,speaking")
                }
                
                var agentId = 0
                while (agentId < privateBeliefs.length) {
                    writer.println(s"$agentId,$round,${privateBeliefs(agentId)},${speakingBuffer(agentId)}")
                    agentId += 1
                }
                
                Logger.log(s"Successfully exported round $round data to $filePath")
            }
            
        } catch {
            case e: java.io.IOException =>
                Logger.logError(s"Failed to write to CSV file '$filePath': ${e.getMessage}")
            case e: java.lang.ArrayIndexOutOfBoundsException =>
                Logger.logError(s"Array index error while processing agent data: ${e.getMessage}")
            case e: Exception =>
                Logger.logError(s"Unexpected error while exporting agent data to '$filePath': ${e.getMessage}")
        }
    }
    
    /**
     * Exports neighbor data to CSV file, overwriting if file exists
     */
    def exportToNeighborsToCSV(): Unit = {
        val filePath = "agent_influences.csv"
        try {
            Using(new PrintWriter(filePath)) { writer =>
                Logger.log(s"Exporting neighbor data to $filePath (${neighborsRefs.length} neighbors)")
                writer.println("Source,Target,Influence")
                
                var i = 0
                var j = 0
                while (i < runMetadata.agentsPerNetwork) {
                    while (j < indexOffset(i)) {
                        writer.println(s"Agent${neighborsRefs(j)},Agent$i,${neighborsWeights(j)}")
                        j += 1
                    }
                    i += 1
                }
            }
        } catch {
            case e: java.io.IOException =>
                Logger.logError(s"Failed to write neighbor data to '$filePath': ${e.getMessage}")
            case e: java.lang.ArrayIndexOutOfBoundsException =>
                Logger.logError(s"Array index error while processing neighbor data - check data integrity: ${e.getMessage}")
            case e: Exception =>
                Logger.logError(s"Unexpected error while exporting neighbor data to '$filePath': ${e.getMessage}")
        }
    }
}
