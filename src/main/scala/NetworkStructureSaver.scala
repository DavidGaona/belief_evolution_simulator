import akka.actor.{Actor, ActorRef}

import java.util.UUID

case class NetworkStructure
(
  source: UUID,
  target: UUID,
  value: Float
)

class NetworkStructureSaver(dbManager: DatabaseManager, numberOfAgents: Int) extends Actor {
    var networkStructure: Array[NetworkStructure] = Array.ofDim[NetworkStructure](numberOfAgents)
    var agentsSaved = 0
    
    def receive: Receive = {
        case SendNeighbors(neighbors) =>
            val senderName = sender().path.name
            val target = UUID.fromString(senderName)
            neighbors.foreach {
                case (neighbor, influence) =>
                    val source = UUID.fromString(neighbor.path.name)
                    networkStructure(agentsSaved) = NetworkStructure(source, target, influence)
            }
            agentsSaved += 1
            if (agentsSaved == numberOfAgents) {
                dbManager.insertNetworkStructureBatch(networkStructure)
                networkStructure = Array.ofDim[NetworkStructure](0)
            }
    }
}
