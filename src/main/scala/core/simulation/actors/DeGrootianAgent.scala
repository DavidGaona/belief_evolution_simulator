package core.simulation.actors

import akka.actor.{Actor, ActorRef}
import akka.util.Timeout
import core.model.agent.behavior.bias.*
import core.model.agent.behavior.silence.*
import core.simulation.*
import io.web.Server
import io.persistence.RoundRouter
import io.persistence.actors.{AgentState, AgentStatesSilent, AgentStatesSpeaking, NeighborStructure, SendNeighbors, SendStaticAgentData}
import io.serialization.binary.Encoder
import utils.datastructures.ArrayListInt
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
case class SetInitialState(
    belief: Float,
    toleranceRadius: Float,
    toleranceOffset: Float,
    agentIndex: Int
) // Network -> Agent

case object AddNames // Network -> Agent

case object MarkAsCustomRun // Network -> Agent

case object UpdateAgent1R // Network -> Agent

case object UpdateAgent2R // Network -> Agent

case object SnapShotAgent // Network -> Agent

case class FirstUpdate(neighborSaver: ActorRef, staticSaver: ActorRef, agents: Array[ActorRef]) // Network -> Agent

case class SendBelief(belief: Float) extends AnyVal // Agent -> self

case class SendInfluenceReduced(belief: Float, influenceRedux: Float) // Agent -> self

case object Silent // Agent -> self

case class AddNeighbors(neighbors: Array[ArrayListInt]) // Network -> agent


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

// ToDos
// Optimize silence array and silence strategy to use ranges
// Optimize loop indexes to start at startsAt and end at startsAt + numberOfAgents

// New Gen Agent
class Agent(
    ids: Array[UUID], silenceStrategy: Array[SilenceStrategy], silenceEffect: Array[SilenceEffect],
    runMetadata: RunMetadata, beliefBuffer1: Array[Float], beliefBuffer2: Array[Float],
    speakingBuffer1: Array[Byte], speakingBuffer2: Array[Byte], belief: Array[Float],
    tolRadius: Array[Float], tolOffset: Array[Float], indexOffset: Array[Int], timesStable: Array[Int],
    neighborsRefs: Array[Int], neighborsWeights: Array[Float], neighborBiases: Array[Byte], hasMemory: Array[Byte],
    neighborsBiasesToAssign: Option[mutable.HashMap[Byte, Int]],
    networkId: UUID, numberOfAgents: Int, startsAt: Int, names: Array[String]
)
  extends Actor {
    // Scalar fields
    val encoder: Encoder = Encoder()
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
    val INV_EPSILON_PLUS_ONE = 1 / (1f + 0.001f)
    //val vectorSpecies = FloatVector.SPECIES_512
    var marks = mutable.Map[Int, Byte]()
    val random = new Random(runMetadata.seed + startsAt)
    val buffer = ByteBuffer.allocate((numberOfAgents * 9) + 32)
    
    def receive: Receive = {
        case MarkAsCustomRun =>
            hasUpdatedInfluences = true
            isGenerated = false
        
        case SetInitialState(initialBelief, toleranceRadius, toleranceOffset, agentIndex) =>
            belief(agentIndex) = initialBelief
            tolRadius(agentIndex) = toleranceRadius
            tolOffset(agentIndex) = toleranceOffset
        
        case FirstUpdate(neighborSaver, agentStaticDataSaver, agents) =>
            val neighborActors = new ArrayBuffer[NeighborStructure](numberOfAgents * 2)
            val agentsStaticStates = new Array[StaticAgentData](numberOfAgents)
            var i = startsAt
            while (i < (startsAt + numberOfAgents)) {
                beliefBuffer1(i) = belief(i)
                silenceEffect(i) match {
                    case m: MemoryEffect => m.initialize(belief(i))
                    case _ =>
                }
                if (!hasUpdatedInfluences) generateInfluencesAndBiases()
                if (runMetadata.saveMode.includesAgents) {
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
                val radius = tolRadius(i)
                tolRadius(i) = math.min(1f, radius + tolOffset(i)) // Become upper
                tolOffset(i) = math.max(0f, tolOffset(i) - radius) // Become lower
                // println(s"New radius: ${tolRadius(i)}, new offset(lower): ${tolOffset(i)}")
                if (runMetadata.saveMode.includesNeighbors) {
                    var j = indexOffset(math.max(0, i - 1))
                    while (j < indexOffset(i)) {
                        neighborActors.addOne(NeighborStructure(
                            ids(i),
                            ids(neighborsRefs(j)),
                            neighborsWeights(j),
                            CognitiveBiases.toBiasType(neighborBiases(j))
                        ))
                        j += 1
                    }
                }
                i += 1
            }
            if (runMetadata.saveMode.includesFirstRound) snapshotAgentState(true, null, speakingBuffer1)
            if (runMetadata.saveMode.includesNeighbors) neighborSaver ! SendNeighbors(neighborActors)
            if (runMetadata.saveMode.includesAgents) agentStaticDataSaver ! SendStaticAgentData(agentsStaticStates)
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
        
        // Some commonly used vectors
        val zeroVector = FloatVector.zero(vectorSpecies)
        val oneVector = FloatVector.broadcast(vectorSpecies, 1.0f)
        val negOneVector = FloatVector.broadcast(vectorSpecies, -1.0f)
        //println("\n")
        
        // Update by groups
        while (i < (startsAt + numberOfAgents)) {
            // Zero out the sums
            sum0 = 0f;
            sum1 = 0f;
            sum2 = 0f;
            sum3 = 0f; //sum = FloatVector.zero(species)
            inFavor = 0
            against = 0
            // Note that currently tolRadius(i) = originalRadius(i) + originalOffset(i)
            // and tolOffset(i) = originalOffset(i) - originalRadius(i)
            val upper = tolRadius(i)
            val lower = tolOffset(i)
            val initialBelief = belief(i)
            var j = if (i > 0) indexOffset(i - 1) else 0
            val currentBias = neighborBiases(j)
            val skipSIMD = marks.contains(i)
            //val msb = 31 - Integer.numberOfLeadingZeros(indexOffset(i) - j)
            //val vectorSpecies = floatSpeciesTable(Math.min(4, msb))
            val endLoopSIMD = indexOffset(i) - 15

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

                val biasResultVector = currentBias match {
                    // DeGroot (neighborBiases(j) = 0)
                    case 0 =>
                        beliefDiffVector

                    // Confirmation bias (neighborBiases(j) = 1)
                    case 1 =>
                        beliefDiffVector.sub(
                            beliefDiffVector.mul(beliefDiffVector.abs()).mul(INV_EPSILON_PLUS_ONE)
                        )

                    // Backfire effect (neighborBiases(j) = 2)
                    case 2 =>
                        beliefDiffVector.mul(beliefDiffVector).mul(beliefDiffVector).neg()

                    // Authority (neighborBiases(j) = 3)
                    case 3 =>
                        val positiveMask = beliefDiffVector.compare(VectorOperators.GT, 0.0f)
                        val negativeMask = beliefDiffVector.compare(VectorOperators.LT, 0.0f)
                        zeroVector.blend(oneVector, positiveMask).blend(negOneVector, negativeMask)
                        //beliefDiffVector.div(beliefDiffVector.abs())

                    // Insular optimize to not move (neighborBiases(j) = 4) on this one it would be even better to just
                    // skip them as this means that there is no belief update
                    // case 4 =>
                }

                // Congruence check using masks
                val diffMinusLower = biasResultVector.sub(lower)
                val upperMinusDiff = FloatVector.broadcast(vectorSpecies, upper).sub(biasResultVector)

                // Create congruence mask: (diffMinusLower >= 0) & (upperMinusDiff >= 0)
                val lowerMask = diffMinusLower.compare(VectorOperators.GE, 0f)
                val upperMask = upperMinusDiff.compare(VectorOperators.GE, 0f)
                val congruentMask = lowerMask.and(upperMask)

                // Apply speaking mask to congruent mask for in favor and against
                val inFavorMask = congruentMask.and(speakingMask)
                val againstMask = congruentMask.not().and(speakingMask)

                against += againstMask.trueCount()
                inFavor += inFavorMask.trueCount()

                // Process neighbor influences
                val influenceVector = FloatVector.fromArray(vectorSpecies, neighborsWeights, j)
                belief(i) += influenceVector.mul(biasResultVector).reduceLanes(VectorOperators.ADD, speakingMask)

                j += vectorSpecies.length()
            }
            
            //println(s"\nAgent$i ")
            val endUnrolledLoop = indexOffset(i) - 3
            while (j < endUnrolledLoop) {
                val speaking0: Int = readSpeakingBuffer(neighborsRefs(j)) | hasMemory(neighborsRefs(j))
                val speaking1: Int = readSpeakingBuffer(neighborsRefs(j + 1)) | hasMemory(neighborsRefs(j + 1))
                val speaking2: Int = readSpeakingBuffer(neighborsRefs(j + 2)) | hasMemory(neighborsRefs(j + 2))
                val speaking3: Int = readSpeakingBuffer(neighborsRefs(j + 3)) | hasMemory(neighborsRefs(j + 3))
                
                val b0: Float = readBeliefBuffer(neighborsRefs(j)) - initialBelief
                val b1: Float = readBeliefBuffer(neighborsRefs(j + 1)) - initialBelief
                val b2: Float = readBeliefBuffer(neighborsRefs(j + 2)) - initialBelief
                val b3: Float = readBeliefBuffer(neighborsRefs(j + 3)) - initialBelief
                
                congruent(speaking0, b0, upper, lower)
                congruent(speaking1, b1, upper, lower)
                congruent(speaking2, b2, upper, lower)
                congruent(speaking3, b3, upper, lower)
                
                val bias0: Float = CognitiveBiases.applyBias(neighborBiases(j), b0)
                val bias1: Float = CognitiveBiases.applyBias(neighborBiases(j + 1), b1)
                val bias2: Float = CognitiveBiases.applyBias(neighborBiases(j + 2), b2)
                val bias3: Float = CognitiveBiases.applyBias(neighborBiases(j + 3), b3)
                
                // Sum calculations S * Bias(Bj - Bi) * I_j
                sum0 += speaking0 * bias0 * neighborsWeights(j)
                sum1 += speaking1 * bias1 * neighborsWeights(j + 1)
                sum2 += speaking2 * bias2 * neighborsWeights(j + 2)
                sum3 += speaking3 * bias3 * neighborsWeights(j + 3)
                
                j += 4
            }
            
            //print(s"B(${round + 1}) = ${belief(i)} ")
            while (j < indexOffset(i)) {
                //println(s"$j, ${indexOffset(i)}")
                val speaking: Int = readSpeakingBuffer(neighborsRefs(j)) | hasMemory(neighborsRefs(j))
                val b: Float = readBeliefBuffer(neighborsRefs(j)) - initialBelief
                congruent(speaking, b, upper, lower)
                val bias: Float = CognitiveBiases.applyBias(neighborBiases(j), b)
                //print(s"+ ${speaking * bias * neighborsWeights(j)} ")
                belief(i) += speaking * bias * neighborsWeights(j)
                
                j += 1
            }
            
            belief(i) += sum0 + sum1 + sum2 + sum3
            belief(i) = math.min(1.0f, math.max(0.0f, belief(i)))
            //print(s"= ${belief(i)}\n")
            
            // Handle speaking and belief buffer updates
            // println(s"For: $inFavor, Against: $against")
            val isSpeaking = silenceStrategy(i).determineSilence(inFavor, against)
            writeSpeakingBuffer(i) = isSpeaking
            writeBeliefBuffer(i) = silenceEffect(i).getPublicValue(belief(i), isSpeaking == 1)
            
            // Stability check
            val isStable = math.abs(belief(i) - initialBelief) < stabilityThreshold // 0000001f
            if (isStable) timesStable(i) += 1
            else timesStable(i) = 0
            
            maxBelief = math.max(maxBelief, writeBeliefBuffer(i))
            minBelief = math.min(minBelief, writeBeliefBuffer(i))
            existsStableAgent = existsStableAgent && (isStable && timesStable(i) > 1)
            i += 1
        }
        
        //println("\n")
        round += 1
        if (runMetadata.saveMode.includesRounds) snapshotAgentState(false, readBeliefBuffer, writeSpeakingBuffer)
        sendRoundToWebSocketServer(writeBeliefBuffer, writeSpeakingBuffer)
        context.parent ! AgentUpdated(maxBelief, minBelief, existsStableAgent)
    }
    
    private inline final def congruent(speaking: Int, beliefDifference: Float, upper: Float, lower: Float): Unit = {
        // (D - L | U - D) >>> 31, mask yields 1 for against 0 for in favor
        val mask = (java.lang.Float.floatToRawIntBits(beliefDifference - lower) |
          java.lang.Float.floatToRawIntBits(upper - beliefDifference)) >>> 31
        
        // println(s"Mask: $mask, status: $speaking, upper: $upper, lower: $lower, diff: $beliefDifference")
        
        against += mask & speaking
        inFavor += (mask ^ 1) & speaking
    }
    
    //
    private def snapshotAgentState(forceSnapshot: Boolean, pastBeliefs: Array[Float],
        speakingState: Array[Byte]): Unit = {
        val roundDataSpeaking: ArrayBuffer[AgentState] = new ArrayBuffer[AgentState](numberOfAgents * 3 / 2)
        val roundDataSilent: ArrayBuffer[AgentState] = new ArrayBuffer[AgentState](numberOfAgents / 4)
        var i = startsAt
        while (i < (startsAt + numberOfAgents)) {
            if (forceSnapshot || math.abs(belief(i) - pastBeliefs(i)) != 0) {
                silenceEffect(i).encodeOptionalValues(encoder)
                silenceStrategy(i).encodeOptionalValues(encoder)
                if (speakingState(i) == 1) {
                    roundDataSpeaking.addOne(AgentState(ids(i), belief(i), encoder.getBytes))
                } else {
                    roundDataSilent.addOne(AgentState(ids(i), belief(i), encoder.getBytes))
                }
                //println(encoder.getBytes.array().mkString(s"${i + startsAt} Array(", ", ", ")"))
                encoder.reset()
            }
            i += 1
        }
        RoundRouter.getRoute ! AgentStatesSpeaking(roundDataSpeaking, round)
        RoundRouter.getRoute ! AgentStatesSilent(roundDataSilent, round)
    }
    
    private def sendRoundToWebSocketServer(beliefBuffer: Array[Float], speakingBuffer: Array[Byte]): Unit = {
        buffer.clear()
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        buffer.putLong(networkId.getMostSignificantBits)
        buffer.putLong(networkId.getLeastSignificantBits)
        buffer.putInt(runMetadata.runId.get)
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
    
    @inline
    private def neighborsSize(i: Int): Int = {
        if (i == 0) indexOffset(i)
        else indexOffset(i) - indexOffset(i - 1)
    }
    
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
                        //marks += (j, indexes(currentIndex))
                        marks += (i, indexes(currentIndex))
                    }
                }
            }
            i += 1
            j = indexOffset(i - 1)
        }
        
        hasUpdatedInfluences = true
//        println(s"seed: ${runMetadata.seed}")
//        println(indexOffset.mkString("offset(", ", ", ")"))
//        println(neighborsRefs.mkString("Neighbors(", ", ", ")"))
//        println(neighborsWeights.mkString("Influences(", ", ", ")"))
//        println(neighborBiases.mkString("Biases(", ", ", ")\n"))
    }
    
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
