package io.persistence.actors

import akka.actor.{Actor, ActorRef}
import core.model.agent.behavior.bias.*
import io.db.DatabaseManager

import java.util.UUID
import scala.collection.IndexedSeqView
import scala.collection.mutable.ArrayBuffer

// Messages
case class SendNeighbors(neighborStructures: ArrayBuffer[NeighborStructure]) // Agent -> NetworkSaver

// Actor

case class NeighborStructure(
    source: UUID,
    target: UUID,
    value: Float,
    bias: Byte
)

class NeighborSaver(numberOfAgents: Int) extends Actor {
    var agentsSaved = 0
    
    def receive: Receive = {
        case SendNeighbors(neighborStructures) =>
            DatabaseManager.insertNeighborsBatch(neighborStructures)
            agentsSaved += 1
            if (agentsSaved == numberOfAgents) {
                context.stop(self)
            }
    }
}
