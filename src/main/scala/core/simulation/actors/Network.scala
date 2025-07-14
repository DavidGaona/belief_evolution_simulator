package core.simulation.actors

import akka.actor.{Actor, ActorRef, Props}
import akka.util.Timeout
import core.model.agent.behavior.silence.*
import io.db.DatabaseManager
import io.web.Server
import io.persistence.actors.{AgentStaticDataSaver, NeighborSaver}
import io.web.CustomRunInfo
import utils.datastructures.{FenwickTree, UUIDS}
import utils.rng.distributions.BimodalDistribution

import java.io.{File, FileWriter, PrintWriter}
import java.nio.ByteBuffer
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.util.Using
// Network

// Messages
case class BuildCustomNetwork(customRunInfo: CustomRunInfo) // Monitor -> Network
case class AgentUpdated(maxBelief: Float, minBelief: Float, isStable: Boolean) // Agent -> network
case class BuildNetworkFromCSV(neighborsArr: Array[Int], offsetsArr: Array[Int]) // Monitor -> network

case object BuildNetwork // Monitor -> network
case object RunNetwork // Monitor -> network
case object RunFirstRound // Agent -> Network
case object SaveRemainingData // Network -> AgentRoundDataSaver
case object ActorFinished // Agent -> Network

// Agent types

// Actor
class Network(networkId: UUID,
    runMetadata: RunMetadata,
    agentTypeCount: Array[(Byte, Byte, Int)],
    agentBiases: Array[(Byte, Int)]) extends Actor {
    // Agents
    val numberOfAgentActors: Int = math.min(32, (runMetadata.agentsPerNetwork + 31) / 32)
    val agentsPerActor: Array[Int] = new Array[Int](numberOfAgentActors)
    calculateAgentsPerActor() // fill agents per actor
    val bucketStart: Array[Int] = new Array[Int](numberOfAgentActors)
    calculateCumSum() // Fill buckets
    val agents: Array[ActorRef] = Array.ofDim[ActorRef](numberOfAgentActors)
    val agentsIds: Array[UUID] = Array.ofDim[UUID](runMetadata.agentsPerNetwork)
    val uuids = UUIDS()
    val bimodal = new BimodalDistribution(0.25, 0.75)
    
    // Belief buffers
    val beliefBuffer1: Array[Float] = Array.fill(runMetadata.agentsPerNetwork)(-1f)
    val beliefBuffer2: Array[Float] = Array.fill(runMetadata.agentsPerNetwork)(-1f)
    val privateBeliefs: Array[Float] = Array.fill(runMetadata.agentsPerNetwork)(-1f)
    
    // Speaking buffers
    val speakingBuffer1: Array[Byte] = Array.fill(runMetadata.agentsPerNetwork)(1)
    val speakingBuffer2: Array[Byte] = Array.fill(runMetadata.agentsPerNetwork)(1)
    //    val speakingBuffer1: AgentStates = AgentStates(runMetadata.agentsPerNetwork)
    //    val speakingBuffer2: AgentStates = AgentStates(runMetadata.agentsPerNetwork)
    
    // Agent Statics
    val tolRadius: Array[Float] = Array.fill(runMetadata.agentsPerNetwork)(0.1f)
    val tolOffset: Array[Float] = Array.fill(runMetadata.agentsPerNetwork)(0.05f)
    
    // Behaviors
    val silenceStrategy: Array[Byte] = new Array[Byte](runMetadata.agentsPerNetwork)
    val silenceEffect: Array[Byte] = new Array[Byte](runMetadata.agentsPerNetwork)
    val hasMemory: Array[Byte] = new Array[Byte](runMetadata.agentsPerNetwork)
    val threshold: mutable.Map[Int, Float] = mutable.Map[Int, Float]()
    // (Threshold, Unbounded Confidence)
    val confidenceState: mutable.Map[Int, (Float, Float)] = mutable.Map[Int, (Float, Float)]()
    val publicBelief: mutable.Map[Int, Float] = mutable.Map[Int, Float]()
    
    // Optional
    var names: Array[String] = null
    
    // Neighbors
    var neighborsRefs: Array[Int] = null // Size = -m^2 - m + 2mn or m(m-1) + (n - m) * 2m
    var neighborsWeights: Array[Float] = null
    var neighborBiases: Array[Byte] = null
    val indexOffset: Array[Int] = new Array[Int](runMetadata.agentsPerNetwork)
    
    // Agent varying
    val timesStable: Array[Int] = new Array[Int](runMetadata.agentsPerNetwork)
    
    // Data saving
    var neighborSaver: ActorRef = null
    var agentStaticDataSaver: ActorRef = null
    
    // Limits
    implicit val timeout: Timeout = Timeout(600.seconds)
    
    // Round state
    var round: Int = 0
    var pendingResponses: Int = 0
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
    var finishedIterating: Boolean = false
    var minBelief: Float = 2.0f
    var maxBelief: Float = -1.0f
    var shouldUpdate: Boolean = false
    var shouldContinue: Boolean = false
    var bufferSwitch: Boolean = true
    
    def receive: Receive = building
    
    // Building state
    private def building: Receive = {
        case BuildCustomNetwork(customRunInfo) =>
            uuids.v7Bulk(agentsIds)
            
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
                hasMemory(i) = if (silenceEffect(i) == SilenceEffect.MEMORY) 1 else 0
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
                    new Agent(
                        agentsIds, silenceStrategy, silenceEffect, threshold,
                        confidenceState, runMetadata, beliefBuffer1, beliefBuffer2,
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
            neighborBiases = new Array[Byte](neighborsRefs.length)
            
            uuids.v7Bulk(agentsIds)
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
                        hasMemory(total + bucketStart(i)) = if (effect == SilenceEffect.MEMORY) 1 else 0
                        k += 1
                        total += 1
                    }
                    j += 1
                }
                agentRemainingCount -= agentsPerActor(i)
                val biasCounts = new mutable.HashMap[Byte, Int]()
                agentBiases.foreach((key, value) => biasCounts.put(key, value))
                // Create the agent actor
                val index = i
                agents(i) = context.actorOf(Props(
                    new Agent(
                        agentsIds, silenceStrategy, silenceEffect, threshold,
                        confidenceState,runMetadata, beliefBuffer1, beliefBuffer2,
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
            val density = runMetadata.optionalMetaData.get.density.get
            // Declare arrays of size -m^2 - m + 2mn <->  m(m-1) + (n - m) * 2m
            val size = (density * (density - 1)) + ((runMetadata.agentsPerNetwork - density) * (2 * density))
            neighborsRefs = new Array[Int](size)
            neighborsWeights = new Array[Float](size)
            neighborBiases = new Array[Byte](size)
            
            
            val fenwickTree = new FenwickTree(
                runMetadata.agentsPerNetwork,
                runMetadata.optionalMetaData.get.density.get,
                runMetadata.optionalMetaData.get.degreeDistribution.get - 2,
                runMetadata.seed
            )
            
            // Create the Actors
            uuids.v7Bulk(agentsIds)
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
                        hasMemory(total + bucketStart(i)) = if (effect == SilenceEffect.MEMORY) 1 else 0
                        k += 1
                        total += 1
                    }
                    j += 1
                }
                agentRemainingCount -= agentsPerActor(i)
                val biasCounts = new mutable.HashMap[Byte, Int]()
                agentBiases.foreach((key, value) => biasCounts.put(key, value))
                // Create the agent actor
                val index = i
                agents(i) = context.actorOf(Props(
                    new Agent(
                        agentsIds, silenceStrategy, silenceEffect, threshold,
                        confidenceState,runMetadata, beliefBuffer1, beliefBuffer2,
                        speakingBuffer1, speakingBuffer2, privateBeliefs, publicBelief,
                        tolRadius, tolOffset, indexOffset, timesStable,
                        neighborsRefs, neighborsWeights, neighborBiases, hasMemory,
                        Some(biasCounts), networkId, agentsPerActor(index), bucketStart(index), null
                    )
                ), s"${self.path.name}_A$i")
                i += 1
            }
            
            
            // Link the first n=density agents
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
                indexOffset(i) += density
                i += 1
            }
            
            // Link the rest of the agents
            while (i < runMetadata.agentsPerNetwork) {
                // val agentsPicked = fenwickTree.pickRandoms()
                // Array.copy(agentsPicked, 0, neighborsRefs, count, density)
                fenwickTree.pickRandomsInto(neighborsRefs, count)
                var j = count
                while (j < (count + density)) {
                    indexOffset(neighborsRefs(j)) += 1
                    j += 1
                }
                indexOffset(i) = density
                count += density
                i += 1
            }
            
            // Cumulative sum
            var prev = indexOffset(0)
            indexOffset(0) = 0
            
            i = 1
            while (i < runMetadata.agentsPerNetwork) {
                val curr = indexOffset(i)
                indexOffset(i) = indexOffset(i - 1) + prev
                prev = curr
                i += 1
            }
            
            // Copy of neighbors references
            val temp = new Array[Int](neighborsRefs.length)
            System.arraycopy(neighborsRefs, 0, temp, 0, neighborsRefs.length)
            
            // First the density ones
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
            
            // Then the rest
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
            
            // var neighborsRefs: Array[Int] = null // Size = -m^2 - m + 2mn or m(m-1) + (n - m) * 2m
            //    var neighborsWeights: Array[Float] = null
            //    var neighborBiases: Array[Byte] = null
            //    val indexOffset: Array[Int] = new Array[Int](runMetadata.agentsPerNetwork)
            
            context.become(running)
            context.parent ! BuildingComplete(networkId)
            
        
    }
    
    // Running State
    private def running: Receive = {
        case RunNetwork =>
            //exportAgentDataToCSV("rt-pol-evo.csv", round, privateBeliefs, speakingBuffer1)
            // agents.foreach { agent => agent ! SaveAgentStaticData }
            pendingResponses = agents.length
            var i = 0
            while (i < agents.length) {
                agents(i) ! FirstUpdate(neighborSaver, agentStaticDataSaver, agents)
                i += 1
            }
        
        case RunFirstRound =>
            pendingResponses -= 1
            if (pendingResponses == 0) {
//                println(s"Round: $round")
//                println(privateBeliefs.mkString("Private(", ", ", ")"))
//                if (bufferSwitch) println(beliefBuffer2.mkString("Public(", ", ", ")"))
//                else println(beliefBuffer1.mkString("Public(", ", ", ")"))
//                println()
                round += 1
                //sendNeighbors()
                Using(new PrintWriter("agent_influences.csv")) { writer =>
                    // Write CSV header
                    writer.println("Source,Target,Influence")

                    var i = 0
                    var j = 0
                    while (i < runMetadata.agentsPerNetwork) {
                        while (j < indexOffset(i)) {
                            //println(s"From Agent${neighborsRefs(j)}, to Agent$i, influence:${neighborsWeights(j)}")
                            writer.println(s"Agent${neighborsRefs(j)},Agent$i,${neighborsWeights(j)}")
                            j += 1
                        }
                        i += 1
                    }
                }
                runRound()
                pendingResponses = agents.length
            }
        
        case AgentUpdated(maxActorBelief, minActorBelief, isStable) =>
            pendingResponses -= 1
            // If isStable true then we don't continue as we are stable
            if (!isStable) shouldContinue = true
            maxBelief = math.max(maxBelief, maxActorBelief)
            minBelief = math.min(minBelief, minActorBelief)
            if (pendingResponses == 0) {
//                if (bufferSwitch) {
//                    exportAgentDataToCSV("rt-pol-evo.csv", round, privateBeliefs, speakingBuffer1)
//                } else {
//                    exportAgentDataToCSV("rt-pol-evo.csv", round, privateBeliefs, speakingBuffer2)
//                }

//                println(s"Round: $round")
//                println(privateBeliefs.mkString("Private(", ", ", ")"))
//                if (bufferSwitch) println(beliefBuffer2.mkString("Public(", ", ", ")"))
//                else println(beliefBuffer1.mkString("Public(", ", ", ")"))
//                if (bufferSwitch) println(speakingBuffer2.mkString("Speaking(", ", ", ")"))
//                else println(speakingBuffer1.mkString("Speaking(", ", ", ")"))
//                println()
                //                if (bufferSwitch) println(String.format("%32s", (speakingBuffer2.states(0) << 28).toBinaryString).replace(' ', '0').grouped(8).mkString(" "))
                //                else println(String.format("%32s", (speakingBuffer1.states(0) << 28).toBinaryString).replace(' ', '0').grouped(8).mkString(" "))
                //                println()
                println(s"Round: $round, Max: $maxBelief, Min: $minBelief")
                if ((maxBelief - minBelief) < runMetadata.stopThreshold) {
                    //                    println(s"Consensus! \nFinal round: $round\n" +
                    //                              s"Belief diff: of ${maxBelief - minBelief} ($maxBelief - $minBelief)")
                    context.parent ! RunningComplete(networkId, round, 1)
                    if (runMetadata.saveMode.includesNetworks) DatabaseManager.updateNetworkFinalRound(networkId, round, true)
                    if (runMetadata.saveMode.includesLastRound) agents.foreach { agent => agent ! SnapShotAgent }
                    if (finishState == 0) context.stop(self)
                    finishedIterating = true
                }
                else if (round == runMetadata.iterationLimit || !shouldContinue) {
                    //                    println(s"Dissensus \nFinal round: $round\n" +
                    //                              s"Belief diff: of ${maxBelief - minBelief} ($maxBelief - $minBelief)")
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
    
    
    // Functions:
    def getNextBucketDistribution(agentsRemaining: Array[Int], bucketSize: Int, totalAgentsRemaining: Int): Array[Int] = {
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
    
    @inline def calculateAgentsPerActor(): Unit = {
        var i = 0
        var remainingToAssign = runMetadata.agentsPerNetwork
        while (0 < remainingToAssign) {
            agentsPerActor(i) += math.min(32, remainingToAssign)
            remainingToAssign -= 32
            i = (i + 1) % numberOfAgentActors
        }
    }
    
    @inline def calculateCumSum(): Unit = {
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
    
    private def sendNeighbors(): Unit = {
        // NetworkId
        // RunId
        // Number of agents
        // Number of neighbors
        val numberOfAgents = indexOffset.length
        val numberOfNeighbors = neighborsRefs.length
        val buffer = ByteBuffer.allocate(24 + (numberOfAgents * 4) + (numberOfNeighbors * 8))
        
        // Header 28 bytes
        buffer.putLong(networkId.getMostSignificantBits)
        buffer.putLong(networkId.getLeastSignificantBits)
        buffer.putInt(runMetadata.runId.get)
        buffer.putInt(indexOffset.length)
        buffer.putInt(neighborsRefs.length)
        
        // Body variable bytes
        buffer.asIntBuffer().put(indexOffset)
        buffer.asIntBuffer().put(neighborsRefs)
        buffer.asFloatBuffer().put(neighborsWeights)
        // buffer.put(neighborBiases)
       
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
        
        // Use FileWriter with append=true to add to existing file
        val writer = new PrintWriter(new FileWriter(file, true))
        
        try {
            // Write header only if file doesn't exist or is empty
            if (!fileExists || file.length() == 0) {
                writer.println("id,round,belief,speaking")
            }
            
            // Write data for each agent
            var agentId = 0
            while (agentId < privateBeliefs.length) {
                writer.println(s"$agentId,$round,${privateBeliefs(agentId)},${speakingBuffer(agentId)}")
                agentId += 1
            }
            
        } finally {
            writer.close()
        }
    }
}
