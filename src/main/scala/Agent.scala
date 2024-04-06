import akka.actor.{Actor, ActorRef, ActorSystem, DeadLetter, Props}
import akka.util.Timeout
import akka.pattern.ask

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Random, Success}
import scala.concurrent.duration._
import scala.math.{log, E}

// Agent

// Messages
case class AddToNeighborhood(neighbor: ActorRef) // Agent -> self

case class RequestOpinion(belief: Double) // self -> Agent

case class SendOpinion(opinion: Int, belief: Double, senderAgent: ActorRef) // Agent -> self
// 0 = silent, 1 = agree, 2 disagree

case class ConfidenceUpdated(hasNextIter: Boolean) // Agent -> network

case class SendNeighbors(network: Vector[ActorRef], influences: Vector[Double]) // Agent -> NetworkSaver

// Actor
class Agent(stopThreshold: Double, distribution: Distribution, bimodal: BimodalDistribution, agentDataSaver: ActorRef,
            networkSaver: ActorRef)
  extends Actor {
    var belief: Double = -1
    val tolRadius: Double = randomBetween(0, 0.5)
    val tolOffset: Double = randomBetween(-tolRadius, tolRadius)
    var beliefExpressionThreshold: Double = -1
    var perceivedOpinionClimate: Double = 0.0
    var confidenceUnbounded: Double = -1
    var confidence: Double = -1
    var neighbors: Vector[ActorRef] = Vector.empty
    var influences: Vector[Double] = Vector.empty
    var hasUpdatedInfluences: Boolean = false
    var firstIter: Boolean = true
    var round = 0
    
    implicit val timeout: Timeout = Timeout(600.seconds)

    // Experimental zone
    val openMindedness: Int = 50 // randomIntBetween(1, 100)
    var curInteractions: Int = 0
    //

    // Calculate and return the belief and opinion climate
    def calculateOpinionClimate(callback: (Double, Double) => Unit): Unit = {
        val countsArr: Array[Int] = Array.fill(3)(0)

        // Create a sequence of futures for all neighbors
        val futures: Seq[Future[SendOpinion]] = neighbors.map { neighbor =>
            (neighbor ? RequestOpinion(belief)).mapTo[SendOpinion]
        }

        // Wait for all futures to complete
        Future.sequence(futures).onComplete {
            case Success(opinions) =>
                var currentSum = 0.0
                var ownInfluence = influences(influences.size - 1)
                curInteractions += 1
                opinions.foreach { opinion =>
                    countsArr(opinion.opinion) += 1
                    val isSilent = opinion.opinion == 0
                    if (influences.nonEmpty) {
                        if (isSilent) ownInfluence += influences(neighbors.indexOf(opinion.senderAgent))
                        else currentSum += opinion.belief * influences(neighbors.indexOf(opinion.senderAgent))
                    }
                }

                currentSum += belief * ownInfluence
                if (curInteractions == openMindedness) curInteractions = 0
                else currentSum = belief

                val climate = countsArr(1) + countsArr(2) match {
                    case 0 => 0.0
                    case _ => (countsArr(1) - countsArr(2)).toDouble / (countsArr(1) + countsArr(2))
                }
                callback(climate, currentSum)
            case Failure(exception) =>
                println(exception)
        }
    }

    def isCongruent(neighborBelief: Double): Boolean = {
        val lower = belief - tolRadius + tolOffset
        val upper = belief + tolRadius + tolOffset
        lower <= neighborBelief && neighborBelief <= upper
    }
    
    // Currently just places random numbers as the influences
    def generateInfluences(): Unit = {
        val random = new Random
        val randomNumbers = Vector.fill(neighbors.size + 1)(random.nextDouble())
        val sum = randomNumbers.sum
        influences = randomNumbers.map(_ / sum)
        hasUpdatedInfluences = true
    }

    def receive: Receive = {
        case AddToNeighborhood(neighbor) =>
            neighbors = neighbors :+ neighbor

        case RequestOpinion(senderBelief) =>
            val agentSender = sender()
            if (confidence < beliefExpressionThreshold) agentSender ! SendOpinion(0, belief, self)
            else if (isCongruent(senderBelief)) agentSender ! SendOpinion(1, belief, self)
            else agentSender ! SendOpinion(2, belief, self)

        case UpdateConfidence =>
            val network = sender()
            val oldConfidence = confidence

            // Check if influences have been updated
            if (!hasUpdatedInfluences) {
                generateInfluences()
            }

            calculateOpinionClimate { (climate, updatedBelief) =>
              // Save the initial state aka round 0
                if (round == 0) {
                    agentDataSaver ! SendAgentData(
                        round, self.path.name, belief, confidence, perceivedOpinionClimate,
                        confidence >= beliefExpressionThreshold
                    )
                }
              
                round += 1
                perceivedOpinionClimate = climate
                confidenceUnbounded = math.max(confidenceUnbounded + perceivedOpinionClimate, 0)
                confidence = (2 / (1 + Math.exp(-confidenceUnbounded))) - 1
                
                if (!firstIter) belief = updatedBelief
                else {
                    agentDataSaver ! SendStaticAgentData(
                        self.path.name, neighbors.size, tolRadius, tolOffset, beliefExpressionThreshold, openMindedness
                    )
                    networkSaver ! SendNeighbors(neighbors, influences)
                }

                val aboveThreshold = math.abs(confidence - oldConfidence) >= stopThreshold || firstIter
                firstIter = false
                agentDataSaver ! SendAgentData(
                    round, self.path.name, belief, confidence, perceivedOpinionClimate,
                    confidence >= beliefExpressionThreshold
                )

                network ! ConfidenceUpdated(aboveThreshold)
            }

    }

    override def preStart(): Unit = {
        distribution match {
            case Uniform =>
                // Uniform
                // belief = randomBetween(0.0, 1)
                // Bimodal
                //belief = bimodal.sample()
                belief = randomBetween()

                def reverseConfidence(c: Double): Double = {
                    if (c == 1.0) {
                        37.42994775023705
                    } else {
                        -math.log(-((c - 1) / (c + 1)))
                    }
                }

                beliefExpressionThreshold = Random.nextDouble()
                //confidence = Random.nextDouble()
                //confidenceUnbounded = reverseConfidence(confidence)
                confidenceUnbounded = Random.nextDouble()
                confidence = (2 / (1 + Math.exp(-confidenceUnbounded))) - 1

            case Normal(mean, std) =>
            // ToDo Implement initialization for the Normal distribution

            case Exponential(lambda) =>
            // ToDo Implement initialization for the Exponential distribution
        }

    }
}
