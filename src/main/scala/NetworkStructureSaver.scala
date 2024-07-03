import akka.actor.{Actor, ActorRef}

import scala.collection.mutable.ArrayBuffer
import java.util.UUID

case class NetworkStructure
(
  source: UUID,
  target: UUID,
  value: Float
)

class NetworkStructureSaver(numberOfAgents: Int) extends Actor {
    var networkStructure: ArrayBuffer[NetworkStructure] = ArrayBuffer[NetworkStructure]()
    var agentsSaved = 0
    
    def receive: Receive = {
        case SendNeighbors(neighbors) =>
            val senderName = sender().path.name
            val target = UUID.fromString(senderName)
            neighbors.foreach {
                case (neighbor, influence) =>
                    val source = UUID.fromString(neighbor.path.name)
                    networkStructure += NetworkStructure(source, target, influence)
            }
            agentsSaved += 1
            if (agentsSaved == numberOfAgents) {
                DatabaseManager.insertNetworkStructureBatch(networkStructure.toArray)
                networkStructure.clear()
            }
    }
}
