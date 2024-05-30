import akka.actor.{Actor, ActorRef, Props}
import akka.util.Timeout

import scala.concurrent.duration.*
import cats.effect.unsafe.implicits.global

import java.util.UUID
// Network

// Messages
case class setInitialState(belief: Float) // Network -> Agent
case class setNeighborInfluence(neighbor: ActorRef, influence: Float) // Network -> Agent
case class BuildingComplete(id: UUID) // Network -> Monitor
case class UpdateAgent(forceBeliefUpdate: Boolean) // Network -> Agent
case class RunningComplete(id: UUID) // Network -> Monitor
case object SaveAgentStaticData // Network -> Agent
case object SaveRemainingData // Network -> AgentRoundDataSaver
case object RunRound //self -> self

// Actor
class Network(networkId: UUID, numberOfAgents: Int, density: Int = -1, degreeDistributionParameter: Float = -0.1f,
              stopThreshold: Float, distribution: Distribution = CustomDistribution, monitor: ActorRef,
              dbManager: DatabaseManager, iterationLimit: Int, dbDataLoadBalancer: ActorRef) extends Actor {
    // Agents
    val agents: Array[ActorRef] = Array.ofDim[ActorRef](numberOfAgents)
    val bimodal = new BimodalDistribution(0.25, 0.75)
    
    // Round state
    var round: Int = 0
    var pendingResponses: Int = 0
    var minBelief: Float = 2.0f
    var maxBelief: Float = -1.0f
    var shouldContinue: Boolean = false
    
    // Data saving
    val networkSaver: ActorRef = context.actorOf(Props(
        new NetworkStructureSaver(dbManager, numberOfAgents)
    ), name = s"NetworkSaverOf${self.path.name}")
    
    val agentStaticDataSaver: ActorRef = context.actorOf(Props(
        new AgentStaticDataSaver(dbManager, numberOfAgents)
    ), name = s"StaticA_${self.path.name}")
    
    // Limits
    implicit val timeout: Timeout = Timeout(600.seconds)
    val roundLimit = 2001
    
    def receive: Receive = building
    
    // ToDo Create function to pick agent based on cause and effect of silence
    private def createNewAgent(agentId: UUID, agentName: String): ActorRef = {
        context.actorOf(Props(
            new MemLessConfidenceAgent(agentId, stopThreshold, distribution, networkSaver, agentStaticDataSaver,
                dbDataLoadBalancer, networkId)
        ), agentName)
    }
    
    private def building: Receive = {
        case BuildCustomNetwork(agents) =>
            for (i <- agents.indices) {
                val agentId: UUID = UUIDGenerator.generateUUID().unsafeRunSync()
                val newAgent = createNewAgent(agentId, agents(i).name)
                this.agents(i) = newAgent
                agents(i).neighbors.foreach((agentName, influence) =>
                    this.agents.filter(_ != null).find(agent => agent.path.name == agentName) match {
                        case Some(neighbor) =>
                            newAgent ! setNeighborInfluence(neighbor, influence)
                            val influenceN = agents.flatMap(_.neighbors)
                              .find(_._1 == newAgent.path.name)
                              .map(_._2)
                              .get
                            neighbor ! setNeighborInfluence(newAgent, influenceN)
                        case None =>
                    }
                )
                newAgent ! setInitialState(agents(i).initialBelief)
            }
            
            context.become(running)
            monitor ! BuildingComplete(networkId)
        
        case BuildNetwork =>
            val fenwickTree = new FenwickTree(numberOfAgents, density, degreeDistributionParameter - 2)
            
            // Initialize the first n=density agents
            for (i <- 0 until density) {
                val agentId: UUID = UUIDGenerator.generateUUID().unsafeRunSync()
                val newAgent = createNewAgent(agentId, agentId.toString)
                agents(i) = newAgent
                for (j <- 0 until i) {
                    agents(j) ! AddToNeighborhood(newAgent)
                    newAgent ! AddToNeighborhood(agents(j))
                }
            }
            
            // Create and link the agents
            for (i <- density - 1 until numberOfAgents - 1) {
                // Create the new agent
                val agentId: UUID = UUIDGenerator.generateUUID().unsafeRunSync()
                val newAgent = createNewAgent(agentId, agentId.toString)
                agents(i + 1) = newAgent
                
                // Pick the agents based on their atractiveness score and link them
                val agentsPicked = fenwickTree.pickRandoms()
                agentsPicked.foreach { agent =>
                    agents(agent) ! AddToNeighborhood(newAgent)
                    newAgent ! AddToNeighborhood(agents(agent))
                }
            }
            context.become(running)
            monitor ! BuildingComplete(networkId)
    }
    
    private def running: Receive = {
        case RunNetwork =>
            agents.foreach { agent => agent ! SaveAgentStaticData }
        
        case RunRound =>
            val forceUpdate = shouldContinue
            shouldContinue = false
            pendingResponses = agents.length
            agents.foreach { agent => agent ! UpdateAgent(!forceUpdate) }
        
        case AgentUpdated(hasNextIter, belief) =>
            pendingResponses -= 1
            if (hasNextIter) {
                shouldContinue = true
            }
            
            maxBelief = math.max(maxBelief, belief)
            minBelief = math.min(minBelief, belief)
            // println(s"$pendingResponses From: ${sender().path.name} at round $round")
            if (pendingResponses == 0) {
                if (round % 50 == 0)
                    println(s"Round $round")
                round += 1
                // Stop if threshold stop condition is met or more than roundLimit rounds have been run
                //if (shouldContinue && round < roundLimit) {
                if ((maxBelief - minBelief) < stopThreshold) {
                    dbDataLoadBalancer ! SaveRemainingData
                    monitor ! RunningComplete(networkId)
                    dbManager.updateNetworkFinalRound(networkId, round, true)
                    context.become(analyzing)
                }
                else if (round == iterationLimit) {
                    dbDataLoadBalancer ! SaveRemainingData
                    monitor ! RunningComplete(networkId)
                    dbManager.updateNetworkFinalRound(networkId, round, false)
                    context.become(analyzing)
                } else {
                    self ! RunRound
                    minBelief = 2.0
                    maxBelief = -1.0
                }
            }
        
    }
    
    private def analyzing: Receive = {
        case StartAnalysis =>
        
    }
}
