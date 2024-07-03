import akka.actor.{Actor, ActorRef}

import java.util.UUID

case class StaticAgentData
(
  id: UUID,
  networkId: UUID,
  numberOfNeighbors: Int,
  toleranceRadius: Float,
  tolOffset: Float,
  beliefExpressionThreshold: Option[Float],
  openMindedness: Option[Int],
  causeOfSilence: String,
  effectOfSilence: String,
  beliefUpdateMethod: String
)
case object AgentsSaved

class AgentStaticDataSaver(numberOfAgents: Int) extends Actor {
    var staticAgentData: Array[StaticAgentData] = Array.ofDim[StaticAgentData](numberOfAgents)
    var agentsSaved: Int = 0
    
    def receive: Receive = {
        case SendStaticData(staticAgentData) =>
            this.staticAgentData(agentsSaved) = staticAgentData
            agentsSaved += 1
            if (agentsSaved == numberOfAgents) {
                DatabaseManager.insertAgentsBatch(this.staticAgentData)
                this.staticAgentData = Array.ofDim[StaticAgentData](0)
                context.parent ! RunRound
            }
            
    }
}
