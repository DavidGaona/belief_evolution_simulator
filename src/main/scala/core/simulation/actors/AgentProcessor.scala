package core.simulation.actors

import akka.actor.{Actor, ActorRef}
import akka.util.Timeout
import core.model.agent.behavior.bias.*
import core.model.agent.behavior.bias.CognitiveBiases.Bias
import core.model.agent.behavior.silence.*
import core.model.agent.behavior.silence.SilenceEffects.SilenceEffect
import core.model.agent.behavior.silence.SilenceStrategies.SilenceStrategy
import core.simulation.*
import core.simulation.config.GlobalState
import io.web.Server
import io.persistence.RoundRouter
import io.persistence.actors.{AgentState, AgentStatesSilent, AgentStatesSpeaking, NeighborStructure, SendNeighbors, SendStaticAgentData}
import io.serialization.binary.Encoder
import utils.logging.Logger
import utils.rng.distributions.*

import java.{lang, util}
import java.nio.{ByteBuffer, ByteOrder}
import java.util.UUID
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.*
import scala.util.Random


// DeGroot based Agent base

// Messages
case object MarkAsCustomRun // Network -> Agent
case object UpdateAgent1R // Network -> Agent
case object UpdateAgent2R // Network -> Agent
case object SnapShotAgent // Network -> Agent

case class FirstUpdate(neighborSaver: ActorRef, staticSaver: ActorRef, agents: Array[ActorRef]) // Network -> Agent


// Data saving messages
case class StaticAgentData(
    id: UUID,
    numberOfNeighbors: Int,
    toleranceRadius: Float,
    tolOffset: Float,
    beliefExpressionThreshold: Option[Float],
    openMindedness: Option[Int],
    causeOfSilence: String,
    effectOfSilence: String,
    beliefUpdateMethod: String,
    name: Option[String] = None
) // Agent -> Agent static data saver SendStaticData


// Containers

// Estimate size
// 16 + 8 + 8 + (4 * 2) + 0.25 + 4 + (4 * m * 2) * 3 + 32 + (4 * 4) + (4 * 4) + 2

/**
 * Agent Processor - Manages simulation logic for a subset of agents in the network. <br>
 * Uses SOA (Structure of Arrays) design where agent data is stored in the Network actor
 * and this processor operates on slices of that data.
 */
class AgentProcessor(
    ids: Array[UUID], silenceStrategy: Array[SilenceStrategy], silenceEffect: Array[SilenceEffect],
    threshold: Array[Float], confidence: Array[Float], confidenceThreshold: Array[Float],
    runMetadata: RunMetadata, beliefBuffer1: Array[Float], beliefBuffer2: Array[Float],
    speakingBuffer1: Array[Byte], speakingBuffer2: Array[Byte], belief: Array[Float],
    publicBelief: Array[Float], tolRadius: Array[Float], tolOffset: Array[Float],
    indexOffset: Array[Int], timesStable: Array[Int], neighborsRefs: Array[Int],
    neighborsWeights: Array[Float], neighborBiases: Array[Bias],
    hasMemory: Array[Byte], neighborsBiasesToAssign: Option[mutable.HashMap[Bias, Int]],
    networkId: UUID, numberOfAgents: Int, startsAt: Int, names: Array[String]
)
  extends Actor {
    val stabilityThreshold = runMetadata.stopThreshold / 100
    implicit val timeout: Timeout = Timeout(600.seconds) // 8 bytes
    
    var round: Int = 0
    var hasUpdatedInfluences: Boolean = false
    var isGenerated: Boolean = true
    var bufferSwitch: Boolean = true // true = buffers 1, false = buffers 2
    
    var inFavor: Int = 0
    var against: Int = 0

    val vectorSpecies: VectorSpecies[lang.Float] = FloatVector.SPECIES_PREFERRED
    val speciesLength = vectorSpecies.length()
    var canSIMD: mutable.Map[Int, Bias] = mutable.Map[Int, Bias]()
    
    val networkNumber: Long = context.parent.path.name.substring(1).toLong
    val random = new Random(runMetadata.seed + runMetadata.runID + startsAt.toLong + networkNumber)
    
    val encoder: Encoder = Encoder()
    val buffer = ByteBuffer.allocate(4 + (numberOfAgents * 9) + 36)
    
    def receive: Receive = {
        case MarkAsCustomRun =>
            hasUpdatedInfluences = true
            isGenerated = false
        
        case FirstUpdate(neighborSaver, agentStaticDataSaver, agents) =>
            val neighborActors = new ArrayBuffer[NeighborStructure](numberOfAgents * 2)
            val agentsStaticStates = new Array[StaticAgentData](numberOfAgents)
            var i = startsAt
            while (i < (startsAt + numberOfAgents)) {
                beliefBuffer1(i) = belief(i)
                if (silenceEffect(i) == SilenceEffects.MEMORY) publicBelief(i) = belief(i)
                
                if (silenceStrategy(i) == SilenceStrategies.THRESHOLD) threshold(i) = 0.1f
                
                if (silenceStrategy(i) == SilenceStrategies.CONFIDENCE) {
                    confidence(i) = random.nextFloat()
                    confidenceThreshold(i) = 0.1f
                }
                
                if (!hasUpdatedInfluences) generateInfluencesAndBiases()
                
                // Add null check before accessing ids array
                if (runMetadata.saveMode.includesAgents && ids != null) {
                    agentsStaticStates(i) = StaticAgentData(
                        id = ids(i),
                        numberOfNeighbors = neighborsSize(i),
                        toleranceRadius = tolRadius(i),
                        tolOffset = tolOffset(i),
                        beliefExpressionThreshold = None,
                        openMindedness = None,
                        causeOfSilence = silenceStrategy(i).toString,
                        effectOfSilence = silenceEffect(i).toString,
                        beliefUpdateMethod = "DeGroot",
                        name = if (isGenerated) None else Option(names(i))
                    )
                }
                
                //Logger.log(s"Old radius: ${tolRadius(i)}, old offset(lower): ${tolOffset(i)}")
                val radius = tolRadius(i)
                tolRadius(i) = radius + tolOffset(i) // Become upper
                tolOffset(i) = tolOffset(i) - radius // Become lower
                //Logger.log(s"New radius: ${tolRadius(i)}, new offset(lower): ${tolOffset(i)}")
                
                // Add null check before accessing ids array
                if (runMetadata.saveMode.includesNeighbors && ids != null) {
                    var j = indexOffset(math.max(0, i - 1))
                    while (j < indexOffset(i)) {
                        neighborActors.addOne(NeighborStructure(
                            ids(i),
                            ids(neighborsRefs(j)),
                            neighborsWeights(j),
                            neighborBiases(j)
                        ))
                        j += 1
                    }
                }
                i += 1
            }
            
            // Only send database messages if ids array is not null
            if (runMetadata.saveMode.includesFirstRound) snapshotAgentState(true, null, speakingBuffer1)
            if (runMetadata.saveMode.includesNeighbors && ids != null) neighborSaver ! SendNeighbors(neighborActors)
            if (runMetadata.saveMode.includesAgents && ids != null) agentStaticDataSaver ! SendStaticAgentData(agentsStaticStates)
            
            sendRoundToWebSocketServer(beliefBuffer1, speakingBuffer1)
            context.parent ! RunFirstRound
        
        case UpdateAgent1R =>
            // Update belief 1 = read buffers, 2 = write buffers
            bufferSwitch = true
            updateBuffers(beliefBuffer1, beliefBuffer2, speakingBuffer1, speakingBuffer2)
        
        case UpdateAgent2R =>
            // Update belief 2 = read buffers, 1 = write buffers
            bufferSwitch = false
            updateBuffers(beliefBuffer2, beliefBuffer1, speakingBuffer2, speakingBuffer1)
        
        case SnapShotAgent =>
            if (bufferSwitch) snapshotAgentState(true, null, speakingBuffer2)
            else snapshotAgentState(true, null, speakingBuffer1)
            context.parent ! ActorFinished
            context.stop(self)
        
    }
    
    // ============================================================================
    // CORE BELIEF UPDATE ALGORITHM - SIMD-optimized DeGroot dynamics
    // ============================================================================
    private def updateBuffers(
        readBeliefBuffer: Array[Float],
        writeBeliefBuffer: Array[Float],
        readSpeakingBuffer: Array[Byte],
        writeSpeakingBuffer: Array[Byte]
    ): Unit = {
        var maxBelief = -1f
        var minBelief = 2f
        var existsStableAgent = true
        var i = startsAt
        var sum0, sum1, sum2, sum3 = 0f
        
        // SIMD vector constants for vectorized operations
        val zeroVector = FloatVector.zero(vectorSpecies)
        val oneVector = FloatVector.broadcast(vectorSpecies, 1.0f)
        val negOneVector = FloatVector.broadcast(vectorSpecies, -1.0f)
        
        // Update by groups
        while (i < (startsAt + numberOfAgents)) {
            sum0 = 0f; sum1 = 0f; sum2 = 0f; sum3 = 0f
            inFavor = 0
            against = 0
            // Note that currently tolRadius(i) = original_radius(i) + originalTolOffset(i)
            //                     tolOffset(i) = originalTolOffset(i) - original_radius(i)
            val upper = tolRadius(i)
            val lower = tolOffset(i)
            val initialBelief = belief(i)
            var j = if (i > 0) indexOffset(i - 1) else 0
            val currentBias = neighborBiases(j)
            val skipSIMD = canSIMD.contains(i) // Skips SIMD if agents have neighbors with multiple different biases
            val endLoopSIMD = indexOffset(i) - speciesLength
            
            // ========================================================================
            // VECTORIZED PROCESSING - Process 16/8/4 neighbors at once using SIMD
            // ========================================================================
            while (j < endLoopSIMD && !skipSIMD) {
                var k = 0
                var maskBits = 0L
                while (k < speciesLength) {
                    val id0 = neighborsRefs(j + k)
                    val id1 = neighborsRefs(j + k + 1)
                    val id2 = neighborsRefs(j + k + 2)
                    val id3 = neighborsRefs(j + k + 3)

                    maskBits |= (readSpeakingBuffer(id0) & 1L | hasMemory(id0)) << k |
                      ((readSpeakingBuffer(id1) & 1L | hasMemory(id1)) << (k + 1)) |
                      ((readSpeakingBuffer(id2) & 1L | hasMemory(id2)) << (k + 2)) |
                      ((readSpeakingBuffer(id3) & 1L | hasMemory(id3)) << (k + 3))

                    k += 4
                }

                val speakingMask: VectorMask[lang.Float] = VectorMask.fromLong(vectorSpecies, maskBits)
                val beliefDiffVector = FloatVector.fromArray(vectorSpecies, readBeliefBuffer, 0, neighborsRefs, j).sub(initialBelief)
                
                // Apply cognitive bias using vectorized operations
                val biasResultVector = currentBias match {
                    case CognitiveBiases.DEGROOT =>
                        beliefDiffVector
                        
                    case CognitiveBiases.CONFIRMATION =>
                        beliefDiffVector.sub(
                            beliefDiffVector.mul(beliefDiffVector.abs()).mul(CognitiveBiases.INV_EPSILON_PLUS_ONE)
                        )
                        
                    case CognitiveBiases.BACKFIRE =>
                        beliefDiffVector.mul(beliefDiffVector).mul(beliefDiffVector).neg()
                        
                    case CognitiveBiases.AUTHORITY =>
                        val positiveMask = beliefDiffVector.compare(VectorOperators.GT, 0.0f)
                        val negativeMask = beliefDiffVector.compare(VectorOperators.LT, 0.0f)
                        zeroVector.blend(oneVector, positiveMask).blend(negOneVector, negativeMask)
                        //beliefDiffVector.div(beliefDiffVector.abs())

                    // Insular optimize to not move (neighborBiases(j) = 4) on this one it would be even better to just
                    // skip them as this means that there is no belief update
                    // case CognitiveBiases.INSULAR =>
                }
                
                // Congruence check: is belief difference within tolerance bounds?
                val diffMinusLower = biasResultVector.sub(lower)
                val upperMinusDiff = FloatVector.broadcast(vectorSpecies, upper).sub(biasResultVector)
                val lowerMask = diffMinusLower.compare(VectorOperators.GE, 0f)
                val upperMask = upperMinusDiff.compare(VectorOperators.GE, 0f)
                val congruentMask = lowerMask.and(upperMask)
                val inFavorMask = congruentMask.and(speakingMask)
                val againstMask = congruentMask.not().and(speakingMask)
                
                against += againstMask.trueCount()
                inFavor += inFavorMask.trueCount()

                // Process neighbor influences
                val influenceVector = FloatVector.fromArray(vectorSpecies, neighborsWeights, j)
                belief(i) += influenceVector.mul(biasResultVector).reduceLanes(VectorOperators.ADD, speakingMask)

                j += vectorSpecies.length()
            }
            
            //print(s"\nAgent$i")
            // ========================================================================
            // UNROLLED SCALAR PROCESSING - Process 4 neighbors at once
            // ========================================================================
            val endUnrolledLoop = indexOffset(i) - 3
            while (j < endUnrolledLoop) {
                val neighborIndex0 = neighborsRefs(j)
                val neighborIndex1 = neighborsRefs(j + 1)
                val neighborIndex2 = neighborsRefs(j + 2)
                val neighborIndex3 = neighborsRefs(j + 3)
                
                val speaking0: Int = readSpeakingBuffer(neighborIndex0) | hasMemory(neighborIndex0)
                val speaking1: Int = readSpeakingBuffer(neighborIndex1) | hasMemory(neighborIndex1)
                val speaking2: Int = readSpeakingBuffer(neighborIndex2) | hasMemory(neighborIndex2)
                val speaking3: Int = readSpeakingBuffer(neighborIndex3) | hasMemory(neighborIndex3)
                
                val b0: Float = readBeliefBuffer(neighborIndex0) - initialBelief
                val b1: Float = readBeliefBuffer(neighborIndex1) - initialBelief
                val b2: Float = readBeliefBuffer(neighborIndex2) - initialBelief
                val b3: Float = readBeliefBuffer(neighborIndex3) - initialBelief
                
                congruent(speaking0, b0, upper, lower)
                congruent(speaking1, b1, upper, lower)
                congruent(speaking2, b2, upper, lower)
                congruent(speaking3, b3, upper, lower)
                
                val bias0: Float = neighborBiases(j).apply(b0)
                val bias1: Float = neighborBiases(j + 1).apply(b1)
                val bias2: Float = neighborBiases(j + 2).apply(b2)
                val bias3: Float = neighborBiases(j + 3).apply(b3)
                
                // Sum calculations S * Bias(Bj - Bi) * I_j
                sum0 += speaking0 * bias0 * neighborsWeights(j)
                sum1 += speaking1 * bias1 * neighborsWeights(j + 1)
                sum2 += speaking2 * bias2 * neighborsWeights(j + 2)
                sum3 += speaking3 * bias3 * neighborsWeights(j + 3)
                
                j += 4
            }
            
            //print(s"(${round + 1}) = ${belief(i)} ")
            // ========================================================================
            // REMAINING NEIGHBORS - Process remaining neighbors one by one
            // ========================================================================
            while (j < indexOffset(i)) {
                val neighborIndex = neighborsRefs(j)
                val speaking: Int = readSpeakingBuffer(neighborIndex) | hasMemory(neighborIndex)
                val beliefDifference: Float = readBeliefBuffer(neighborIndex) - initialBelief
                congruent(speaking, beliefDifference, upper, lower)
                val bias: Float = neighborBiases(j).apply(beliefDifference)
                //print(s"+ ${speaking * bias * neighborsWeights(j)} ")
                belief(i) += speaking * bias * neighborsWeights(j)
                
                j += 1
            }
            
            // ========================================================================
            // BELIEF UPDATE FINALIZATION
            // ========================================================================
            belief(i) += sum0 + sum1 + sum2 + sum3
            belief(i) = math.min(1.0f, math.max(0.0f, belief(i))) // Clamp to [0,1]
            //print(s"= ${belief(i)}\n")
            
            // Determine speaking behavior and public belief expression
            val isSpeaking = silenceStrategy(i).shouldSpeak(i, inFavor, against, threshold, confidence, confidenceThreshold)
            writeSpeakingBuffer(i) = isSpeaking
            writeBeliefBuffer(i) = silenceEffect(i).getPublicValue(i, belief(i), isSpeaking, publicBelief)
            
            // Stability tracking for convergence detection
            val isStable = math.abs(belief(i) - initialBelief) < stabilityThreshold
            if (isStable) timesStable(i) += 1 else timesStable(i) = 0
            
            maxBelief = math.max(maxBelief, writeBeliefBuffer(i))
            minBelief = math.min(minBelief, writeBeliefBuffer(i))
            existsStableAgent = existsStableAgent && (isStable && timesStable(i) > 1)
            i += 1
        }
        
        //println("\n---------------------------------------------------\n")
        // ========================================================================
        // ROUND FINALIZATION
        // ========================================================================
        round += 1
        if (runMetadata.saveMode.includesRounds) snapshotAgentState(false, readBeliefBuffer, writeSpeakingBuffer)
        if (!GlobalState.APP_MODE.skipWS) sendRoundToWebSocketServer(writeBeliefBuffer, writeSpeakingBuffer)
        context.parent ! AgentUpdated(maxBelief, minBelief, existsStableAgent)
    }
    
    // ============================================================================
    // HELPER FUNCTIONS - Performance-critical utilities
    // ============================================================================
    
    /**
     * Checks if the belief of the neighbor is congruent with its own belief
     *
     * @param speaking Whether the neighbor is speaking or not (0 silent, 1 speaking)
     * @param beliefDifference Belief difference between neighbor and actor (Neighbor belief - Agent Belief)
     * @param upper Upper limit (tolerance offset + tolerance radius)
     * @param lower Lower limit (tolerance offset - tolerance radius)
     */
    private inline final def congruent(speaking: Int, beliefDifference: Float, upper: Float, lower: Float): Unit = {
        if(beliefDifference >= lower && beliefDifference <= upper) {
            inFavor += speaking
        } else {
            against += speaking
        }
    }
    
    @inline
    private def neighborsSize(i: Int): Int = {
        if (i == 0) indexOffset(i)
        else indexOffset(i) - indexOffset(i - 1)
    }
    
    // ============================================================================
    // DATA PERSISTENCE - Snapshot and WebSocket communication
    // ============================================================================
    private def snapshotAgentState(forceSnapshot: Boolean, pastBeliefs: Array[Float],
        speakingState: Array[Byte]): Unit = {
        if (ids == null) return
        
        val roundDataSpeaking: ArrayBuffer[AgentState] = new ArrayBuffer[AgentState](numberOfAgents * 3 / 2)
        val roundDataSilent: ArrayBuffer[AgentState] = new ArrayBuffer[AgentState](numberOfAgents / 4)
        var i = startsAt
        while (i < (startsAt + numberOfAgents)) {
            if (forceSnapshot || pastBeliefs == null || math.abs(belief(i) - pastBeliefs(i)) != 0) {
                if (silenceEffect(i) == SilenceEffects.MEMORY) encoder.encodeFloat("publicBelief", publicBelief(i))
                
                // Replace threshold.contains(i) with proper check for THRESHOLD strategy
                if (threshold != null && silenceStrategy(i) == SilenceStrategies.THRESHOLD) 
                    encoder.encodeFloat("threshold", threshold(i))
                
                // Replace confidence.contains(i) with proper check for CONFIDENCE strategy
                if (confidence != null && silenceStrategy(i) == SilenceStrategies.CONFIDENCE) {
                    val unboundedConfidence = confidence(i)
                    val confThreshold = confidenceThreshold(i)
                    encoder.encodeFloat("confidenceThreshold", confThreshold)
                    encoder.encodeFloat("confidence", (2f / (1f + Math.exp(-unboundedConfidence).toFloat)) - 1)
                }
                
                if (speakingState(i) == 1) {
                    roundDataSpeaking.addOne(AgentState(ids(i), belief(i), encoder.getBytes))
                } else {
                    roundDataSilent.addOne(AgentState(ids(i), belief(i), encoder.getBytes))
                }
                encoder.reset()
            }
            i += 1
        }
        RoundRouter.getRoute ! AgentStatesSpeaking(roundDataSpeaking, round)
        RoundRouter.getRoute ! AgentStatesSilent(roundDataSilent, round)
    }
    
    /**
     * Sends real-time simulation data to WebSocket clients in an efficient binary format.
     *
     * Creates a compact binary packet containing agent states for this processor's agent subset.
     * The binary format is designed for minimal bandwidth usage and fast parsing on the client side.
     *
     * Binary Packet Structure (Little Endian):
     * ┌─────────────────────────────────────────────────────────────────────────────┐ <br>
     * │ HEADER (36 bytes)                                                           │ <br>
     * ├─────────────────────────┬───────────────────────────────────────────────────┤ <br>
     * │ networkId (16 bytes)    │ UUID split into mostSigBits + leastSigBits        │ <br>
     * │ runID (8 bytes)         │ Simulation run identifier                         │ <br>
     * │ numberOfAgents (4 bytes)│ Number of agents in this processor's subset       │ <br>
     * │ round (4 bytes)         │ Current simulation round number                   │ <br>
     * │ startsAt (4 bytes)      │ Starting index of this processor's agent range    │ <br>
     * ├─────────────────────────┼───────────────────────────────────────────────────┤ <br>
     * │ BELIEF DATA (numberOfAgents * 8 bytes)                                      │ <br>
     * ├─────────────────────────┼───────────────────────────────────────────────────┤ <br>
     * │ publicBeliefs (4 bytes) │ Public belief values [0.0-1.0] for each agent     │ <br>
     * │ privateBeliefs (4 bytes)│ Private belief values [0.0-1.0] for each agent    │ <br>
     * │ ... (repeated for each agent)                                               │ <br>
     * ├─────────────────────────┼───────────────────────────────────────────────────┤ <br>
     * │ SPEAKING DATA (numberOfAgents bytes)                                        │ <br>
     * ├─────────────────────────┼───────────────────────────────────────────────────┤ <br>
     * │ speakingState (1 byte)  │ 0 = silent, 1 = speaking for each agent           │ <br>
     * │ ... (repeated for each agent)                                               │ <br>
     * └─────────────────────────────────────────────────────────────────────────────┘
     *
     * Total packet size: 36 + (numberOfAgents * 9) bytes
     *
     * @param beliefBuffer Public belief values for all agents in the network (this processor reads its subset)
     * @param speakingBuffer Speaking state for all agents (0=silent, 1=speaking, this processor reads its subset)
     */
    private def sendRoundToWebSocketServer(beliefBuffer: Array[Float], speakingBuffer: Array[Byte]): Unit = {
        buffer.clear()
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        // PACKET ID (1 byte)
        buffer.put(0x02.toByte) // 0x02 for Round Data Packet
        buffer.put(0.toByte)          // Padding
        buffer.put(0.toByte)          // Padding
        buffer.put(0.toByte)          // Padding
        
        buffer.putLong(networkId.getMostSignificantBits)
        buffer.putLong(networkId.getLeastSignificantBits)
        buffer.putLong(runMetadata.runID)
        buffer.putInt(numberOfAgents)
        buffer.putInt(round)
        buffer.putInt(startsAt)
        
        val floatView = buffer.asFloatBuffer()
        floatView.put(beliefBuffer, startsAt, numberOfAgents)
        floatView.put(belief, startsAt, numberOfAgents)
        
        buffer.position(buffer.position() + (numberOfAgents * 8))
        buffer.put(speakingBuffer, startsAt, numberOfAgents)
        
        buffer.flip()
        
        Server.sendSimulationBinaryData(runMetadata.channelId, buffer)
    }
    
    // ============================================================================
    // NETWORK GENERATION - Influence weights and bias assignment
    // ============================================================================
    private def generateInfluencesAndBiases(): Unit = {
        val indexes = random.shuffle(neighborsBiasesToAssign.get.keys.toArray)
        var currentIndex: Int = 0
        
        var i = startsAt
        var j = if (i != 0) indexOffset(i - 1) else 0
        while (i < (startsAt + numberOfAgents)) {
            val randomNumbers = Array.fill(neighborsSize(i) + 1)(random.nextFloat())
            val sum = randomNumbers.sum
            var k = 0
            while (j < indexOffset(i)) {
                neighborsWeights(j) = randomNumbers(k) / sum
                neighborBiases(j) = indexes(currentIndex)
                j += 1
                k += 1
                neighborsBiasesToAssign.get(indexes(currentIndex)) -= 1
                if (neighborsBiasesToAssign.get(indexes(currentIndex)) == 0) {
                    currentIndex += 1
                    if (currentIndex < indexes.length) {
                        canSIMD(i) = indexes(currentIndex)
                    }
                }
            }
            i += 1
            j = indexOffset(i - 1)
        }
        
        hasUpdatedInfluences = true
    }
    
    // ============================================================================
    // INITIALIZATION - Agent belief distribution setup
    // ============================================================================
    override def preStart(): Unit = {
        runMetadata.distribution match {
            case Uniform =>
                var i = startsAt
                while (i < (startsAt + numberOfAgents)) {
                    belief(i) = random.nextFloat()
                    i += 1
                }
            
            case Normal(mean, std) =>
            // ToDo Implement initialization for the Normal distribution
            
            case Exponential(lambda) =>
            // ToDo Implement initialization for the Exponential distribution
            
            case BiModal(peak1, peak2, lower, upper) =>
            
            case _ =>
            
        }
    }
}
