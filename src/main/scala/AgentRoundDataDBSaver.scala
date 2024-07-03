import akka.actor.Actor
import scala.collection.mutable.ArrayBuffer

abstract class AgentRoundDataDBSaver[T](tableNumber: Int) extends Actor {
    var agentRoundData: ArrayBuffer[T] = ArrayBuffer.empty
    val timer: CustomTimer = new CustomTimer()
    timer.start()
    var round = 0
    
    def contactDB(): Unit
    def saveToDB(): Unit = {
        round += 1
        timer.stop(s"Started saving ${self.path.name} for the $round time")
        timer.start()
        contactDB()
        context.parent ! FinishedSaving
        timer.stop(s"Finished saving ${self.path.name} for the $round time")
        timer.start()
        agentRoundData.clear()
    }
    
    def cleanTempTable(): Unit
    private def finalSaveAndCleanUp(): Unit = {
        timer.start()
        cleanTempTable()
        timer.stop(s"Finished saving cleaning $tableNumber")
    }
    
    def receive: Receive = {
        case SaveRemainingData =>
            if (agentRoundData.nonEmpty) saveToDB()
            finalSaveAndCleanUp()
    }
    
}

class MemoryConfidenceDBSaver(tableNumber: Int, saveThreshold: Int) extends
  AgentRoundDataDBSaver[MemoryConfidenceRound](tableNumber) {
    
    def contactDB(): Unit = {
        DatabaseManager.insertBatchMemoryConfidenceRoundData(agentRoundData, s"temp_table_mc_$tableNumber")
    }
    
    def cleanTempTable(): Unit = {
        DatabaseManager.cleanTempTableMemoryConfidence(s"temp_table_mc_$tableNumber")
    }
    
    override def receive: Receive = super.receive.orElse {
        case data: MemoryConfidenceRound =>
            agentRoundData += data
            if (agentRoundData.size == saveThreshold) saveToDB()
    }
}

class MemoryMajorityDBSaver(tableNumber: Int, saveThreshold: Int) extends
  AgentRoundDataDBSaver[MemoryMajorityRound](tableNumber) {
    
    def contactDB(): Unit = {
        DatabaseManager.insertBatchMemoryMajorityRoundData(agentRoundData, s"temp_table_mm_$tableNumber")
    }
    
    def cleanTempTable(): Unit = {
        DatabaseManager.cleanTempTableMemoryMajority(s"temp_table_mm_$tableNumber")
    }
    
    override def receive: Receive = super.receive.orElse {
        case data: MemoryMajorityRound =>
            agentRoundData += data
            if (agentRoundData.size == saveThreshold) saveToDB()
    }
}

class MemoryLessConfidenceDBSaver(tableNumber: Int, saveThreshold: Int) extends
  AgentRoundDataDBSaver[MemoryLessConfidenceRound](tableNumber) {
    
    def contactDB(): Unit = {
        DatabaseManager.insertBatchMemoryLessConfidenceRoundData(agentRoundData, s"temp_table_mlc_$tableNumber")
    }
    
    def cleanTempTable(): Unit = {
        DatabaseManager.cleanTempTableMemorylessConfidence(s"temp_table_mlc_$tableNumber")
    }
    
    override def receive: Receive = super.receive.orElse {
        case data: MemoryLessConfidenceRound =>
            agentRoundData += data
            if (agentRoundData.size == saveThreshold) saveToDB()
    }
}

class MemoryLessMajorityDBSaver(tableNumber: Int, saveThreshold: Int) extends
  AgentRoundDataDBSaver[MemoryLessMajorityRound](tableNumber) {
    
    def contactDB(): Unit = {
        DatabaseManager.insertBatchMemoryLessMajorityRoundData(agentRoundData, s"temp_table_$tableNumber")
    }
    
    def cleanTempTable(): Unit = {
        DatabaseManager.cleanTempTableMemorylessMajority(s"temp_table_$tableNumber")
    }
    
    override def receive: Receive = super.receive.orElse {
        case data: MemoryLessMajorityRound =>
            agentRoundData += data
            if (agentRoundData.size == saveThreshold) saveToDB()
    }
}
