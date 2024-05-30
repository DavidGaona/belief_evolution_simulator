import akka.actor.{Actor, ActorRef, Props}
import scala.util.control.Breaks._
import scala.collection.mutable.ArrayBuffer
import java.util.UUID

// Data Storing
case class RoundData
(
  round: Int,
  belief: Float,
  isSpeaking: Boolean,
  confidence: Option[Float],
  opinionClimate: Option[Float],
  publicBelief: Option[Float],
  selfInfluence: Float,
  agentId: UUID
)

// Actor
class DBDataLoadBalancer(dbManager: DatabaseManager, totalNumberOfActorsRunning: Int) extends Actor {
    var dbSavers: ArrayBuffer[(ActorRef, Boolean)] = ArrayBuffer.fill(totalNumberOfActorsRunning)((null, true))
    var roundRobinIndex = 0
    
    override def preStart(): Unit = {
        for (i <- 0 until totalNumberOfActorsRunning) {
            val actorRef = context.actorOf(Props(new AgentRoundDataDBSaver(dbManager, i, 500000)), s"DB$i")
            dbSavers(i) = (actorRef, true)
        }
    }
    
    private def pickAvailableActor(): ActorRef = {
        var selectedActor: Option[ActorRef] = None
        
        breakable {
            for (i <- dbSavers.indices) {
                if (dbSavers(i)._2) {
                    selectedActor = Some(dbSavers(i)._1)
                    break()
                }
            }
        }
        
        selectedActor.getOrElse {
            val dbSaver = dbSavers(roundRobinIndex)._1
            roundRobinIndex = (roundRobinIndex + 1) % dbSavers.length
            dbSaver
        }
    }
    
    def receive: Receive = {
        case StartedSaving =>
            val dbSaver = sender()
            for (i <- dbSavers.indices) {
                if (dbSavers(i)._1 == dbSaver) {
                    dbSavers(i) = (dbSaver, false)
                }
            }
        
        case FinishedSaving =>
            val dbSaver = sender()
            for (i <- dbSavers.indices) {
                if (dbSavers(i)._1 == dbSaver) {
                    dbSavers(i) = (dbSaver, true)
                }
            }
        
        case data: SendAgentData =>
            val selectedDBSaver = pickAvailableActor()
            selectedDBSaver ! data
        
        case SaveRemainingData =>
            dbSavers.foreach(_._1 ! SaveRemainingData)
    }
}
