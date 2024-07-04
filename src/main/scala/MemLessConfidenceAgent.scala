import akka.actor.ActorRef

import scala.util.Random
import scala.math.log

import java.util.UUID

// Memory-less-Confidence Agent

// Actor
class MemLessConfidenceAgent(id: UUID, stopThreshold: Float, distribution: Distribution, networkSaver: ActorRef,
                             staticAgentDataSaver: ActorRef, networkId: UUID)
  extends DeGrootianAgent {
    // Confidence related
    var beliefExpressionThreshold: Float = -1f
    var perceivedOpinionClimate: Float = 0.0f
    var confidenceUnbounded: Float = -1f
    var confidence: Float = -1f
    var prevConfidence: Float = -1f
    
    // Belief update
    val openMindedness: Int = 50 // randomIntBetween(1, 100)
    var curInteractions: Int = 0
    
    override def receive: Receive = super.receive.orElse {
        case RequestBelief(roundSentFrom) if prevConfidence >= beliefExpressionThreshold =>
            sender() ! SendBelief(prevBelief, self)
        
        case RequestBelief(roundSentFrom) =>
            sender() ! SendBelief(-1f, self)
        
        case SaveAgentStaticData =>
            staticAgentDataSaver ! SendStaticData(StaticAgentData(
                id, networkId, neighbors.size, tolRadius, tolOffset, Some(beliefExpressionThreshold),
                Some(openMindedness), "confidence", "memory-less", "DeGroot"
            ))
        
        case UpdateAgent(forceBeliefUpdate) =>
            prevBelief = belief
            prevConfidence = confidence
            
            if (round == 0) {
                if (!hasUpdatedInfluences) generateInfluences()
                
                // Save Network structure
                networkSaver ! SendNeighbors(neighbors)
                
                // Save first round state
                snapshotAgentState(forceSnapshot = true)
            }
            round += 1
            unstashAll()
            
            fetchBeliefsFromNeighbors { beliefs =>
                var inFavor = 0
                var against = 0
                var selfInfluenceSummed = selfInfluence
                belief = 0f
                beliefs.foreach {
                    case SendBelief(neighborBelief, neighbor) if neighborBelief == -1f =>
                        selfInfluenceSummed += neighbors(neighbor)
                    
                    case SendBelief(neighborBelief, neighbor) =>
                        if (isCongruent(neighborBelief)) inFavor += 1
                        else against += 1
                        belief += neighborBelief * neighbors(neighbor)
                }
                
                belief += prevBelief * selfInfluenceSummed
                curInteractions += 1
                if (curInteractions == openMindedness || forceBeliefUpdate) curInteractions = 0
                else belief = prevBelief
                
                perceivedOpinionClimate = inFavor + against match {
                    case 0 => 0.0f
                    case totalSpeaking => (inFavor - against).toFloat / totalSpeaking
                }
                confidenceUnbounded = math.max(confidenceUnbounded + perceivedOpinionClimate, 0)
                confidence = (2f / (1f + Math.exp(-confidenceUnbounded).toFloat)) - 1f
                
                val aboveThreshold = math.abs(confidence - prevConfidence) >= stopThreshold || round == 1
                // Save the round state
                snapshotAgentState(selfInfluenceSummed)
                network ! AgentUpdated(aboveThreshold, belief, belief == prevBelief,
                    curInteractions == openMindedness || forceBeliefUpdate)
            }
        
    }
    
    override def preStart(): Unit = {
        distribution match {
            case Uniform =>
                belief = randomBetweenF()
                
                def reverseConfidence(c: Float): Float = {
                    if (c == 1.0) {
                        37.42994775f
                    } else {
                        -math.log(-((c - 1) / (c + 1))).toFloat
                    }
                }
                
                beliefExpressionThreshold = Random.nextFloat()
                confidence = Random.nextFloat()
                confidenceUnbounded = reverseConfidence(confidence)
//                confidenceUnbounded = Random.nextFloat()
//                confidence = (2 / (1 + Math.exp(-confidenceUnbounded).toFloat)) - 1
            
            case Normal(mean, std) =>
            // ToDo Implement initialization for the Normal distribution
            
            case Exponential(lambda) =>
            // ToDo Implement initialization for the Exponential distribution
            
            case BiModal(peak1, peak2, lower, upper) =>
            
            case _ =>
            
        }
    }
    
    private def snapshotAgentState(selfInfluence: Float = selfInfluence, forceSnapshot: Boolean = false): Unit = {
        var localBelief: Option[Float] = Some(belief)
        if (belief == prevBelief || !forceSnapshot) localBelief = None
        if (localBelief.isEmpty & (confidence == prevConfidence)) return
        
        val dbSaver = RoundDataRouters.getDBSaver(MemoryLessConfidence)
        dbSaver ! MemoryLessConfidenceRound(
            id, round, confidence >= beliefExpressionThreshold, confidence, perceivedOpinionClimate,
            selfInfluence, localBelief
        )
    }
    
}
