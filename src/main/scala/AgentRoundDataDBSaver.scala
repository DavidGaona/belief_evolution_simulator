import akka.actor.Actor
import scala.collection.mutable.ArrayBuffer

case object StartedSaving
case object FinishedSaving

class AgentRoundDataDBSaver (dbManager: DatabaseManager, tableNumber: Int, saveThreshold: Int) extends Actor {
    var agentRoundData: ArrayBuffer[RoundData] = ArrayBuffer[RoundData]()
    val timer: CustomTimer = new CustomTimer()
    timer.start()
    var round = 0
    
    private def saveToDB(): Unit = {
        context.parent ! StartedSaving
        round += 1
        timer.stop(s"Started saving ${self.path.name}")
        timer.start()
        dbManager.insertBatchRoundData(agentRoundData, s"temp_table_$tableNumber")
        agentRoundData.clear()
        context.parent ! FinishedSaving
        timer.stop(s"Finished saving ${self.path.name}")
        timer.start()
    }
    
    def receive: Receive = {
        case SendAgentData(roundData) =>
            agentRoundData += roundData
            if (agentRoundData.size >= saveThreshold) saveToDB()
        
        case SaveRemainingData =>
            if (agentRoundData.nonEmpty) saveToDB() // Save only if there is remaining data
            dbManager.cleanTempTable(s"temp_table_$tableNumber")
    }
}
