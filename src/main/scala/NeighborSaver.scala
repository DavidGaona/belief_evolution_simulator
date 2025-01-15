import akka.actor.{Actor, ActorRef}

import scala.collection.mutable.ArrayBuffer
import java.util.UUID
import scala.collection.IndexedSeqView

// Messages
case class SendNeighbors(neighborStructures: IndexedSeqView[NeighborStructure]) // Agent -> NetworkSaver

// Actor

case class NeighborStructure(
    source: UUID,
    target: UUID,
    value: Float,
    bias: CognitiveBiasType
)

class NeighborSaver(numberOfAgents: Int) extends Actor {
    var agentsSaved = 0
    
    def receive: Receive = {
        case SendNeighbors(neighborStructures) =>
            DatabaseManager.insertNeighborsBatch(neighborStructures)
            agentsSaved += neighborStructures.length
            if (agentsSaved == numberOfAgents) {
                context.stop(self)
            }
    }
}
