import akka.actor.{Actor, ActorRef, Props, PoisonPill}
import akka.util.Timeout
import akka.pattern.ask

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Random, Success}
import scala.collection.mutable
// Network

// Messages
case object BuildingComplete // Network -> Monitor
case object UpdateConfidence // Network -> Agent
case object RunningComplete // Network -> Monitor

// Actor
class Network(numberOfAgents: Int, density: Int, degreeDistributionParameter: Double, stopThreshold: Double,
              distribution: Distribution, monitor: ActorRef, dataSavingPath: String, dataSaver: ActorRef) extends Actor {

    // Agents
    val agents: Array[ActorRef] = Array.ofDim[ActorRef](numberOfAgents)
    val bimodal = new BimodalDistribution(0.25, 0.75)

    // Round state
    var currentRound: Int = 0
    var pendingResponses: Int = 0
    var shouldContinue: Boolean = false

    // Data saving
    val networkSaver: ActorRef = context.actorOf(Props(new NetworkSaver(dataSavingPath)), 
        name = s"NetworkSaverOf${self.path.name}")
    val agentData: ActorRef = context.actorOf(Props(
        new AgentDataSaver(dataSavingPath, dataSaver, networkSaver, numberOfAgents, self)
    ), name = s"AgentDataSaverOf${self.path.name}")

    // Limits
    implicit val timeout: Timeout = Timeout(600.seconds)
    val roundLimit = 2001

    def receive: Receive = building

    def createNewAgent(agentName: String): ActorRef = {
        context.actorOf(Props(new Agent(stopThreshold, distribution, bimodal, agentData, networkSaver)), agentName)
    }
    
    def building: Receive = {
        case BuildNetwork =>
            val fenwickTree = new FenwickTree(numberOfAgents, density, degreeDistributionParameter - 2)

            // Initialize the first n=density agents
            for (i <- 0 until density) {
                val newAgent = createNewAgent(s"Agent${i + 1}")
                agents(i) = newAgent
                for (j <- 0 until i) {
                    agents(j) ! AddToNeighborhood(newAgent)
                    newAgent ! AddToNeighborhood(agents(j))
                }
            }

            // Create and link the agents
            for (i <- density-1 until numberOfAgents - 1) {
                // Create the new agent
                val newAgent = createNewAgent(s"Agent${i + 2}")
                agents(i+1) = newAgent

                // Pick the agents based on their atractiveness score and link them
                val agentsPicked = fenwickTree.pickRandoms()
                agentsPicked.foreach { agent =>
                    agents(agent) ! AddToNeighborhood(newAgent)
                    newAgent ! AddToNeighborhood(agents(agent))
                }
            }
            context.become(running)
            monitor ! BuildingComplete
    }

    def running: Receive = {
        case RunNetwork =>
            pendingResponses = agents.length
            shouldContinue = false
            agents.foreach { agent =>
                agent ! UpdateConfidence
            }

        case ConfidenceUpdated(hasNextIter) =>
            pendingResponses -= 1
            if (hasNextIter) {
                shouldContinue = true
            }

            if (pendingResponses == 0) {
                // Stop if threshold stop condition is met or more than roundLimit rounds have been run
                if (shouldContinue && currentRound < roundLimit) {
                    // Reset round data
                    currentRound += 1
                    self ! RunNetwork
                } else {
                    monitor ! RunningComplete
                    context.become(analyzing)
                }
            }

    }

    def analyzing: Receive = {
        case StartAnalysis =>
            agentData ! ExportCSV
    }
}
