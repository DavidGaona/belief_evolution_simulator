import akka.actor.{Actor, ActorRef, Props}

import scala.collection.mutable.ArrayBuffer
import java.util.UUID


case class MemoryConfidenceRound
(
  agentId: UUID,
  round: Int,
  isSpeaking: Boolean,
  confidence: Float,
  opinionClimate: Float,
  belief: Option[Float],
  publicBelief: Option[Float]
) // ~193 bits

case class MemoryMajorityRound
(
  agentId: UUID,
  round: Int,
  isSpeaking: Boolean,
  belief: Float,
  publicBelief: Float
)

case class MemoryLessConfidenceRound
(
  agentId: UUID,
  round: Int,
  isSpeaking: Boolean,
  confidence: Float,
  opinionClimate: Float,
  selfInfluence: Float,
  belief: Option[Float]
)

case class MemoryLessMajorityRound
(
  agentId: UUID,
  round: Int,
  isSpeaking: Boolean,
  selfInfluence: Float,
  belief: Float,
)


abstract class AgentRoundDataDBSaver[T](tableNumber: Int) extends Actor {
    // Data
    protected var agentRoundData: ArrayBuffer[T] = ArrayBuffer.empty
    
    // Timing
    val timer: CustomTimer = new CustomTimer()
    timer.start()
    
    // Cleaning
    private var numberOfSavesToDB = 0
    private val routineCleanupThreshold = 5
    private var timesUpdated = 1
    val totalCores: Int = Runtime.getRuntime.availableProcessors()
    protected var saveTableName: String = ""
    protected val dbCleaner: ActorRef = context.actorOf(Props(new DBCleaner))
    
    def contactDB(): Unit
    def saveToDB(): Unit = {
        numberOfSavesToDB += 1
        //timer.stop(s"Started saving ${self.path.name} for the $numberOfSavesToDB time")
        timer.start()
        contactDB()
        timer.stop(s"Finished saving ${self.path.name} for the $numberOfSavesToDB time")
        timer.start()
        agentRoundData.clear()
        if ((numberOfSavesToDB % routineCleanupThreshold) == 0) routineCleanUP()
    }
    
    def cleanTempTable(): Unit
    
    private def routineCleanUP(): Unit = {
        cleanTempTable()
        val regex = "^(.+)_[^_]*$".r
        saveTableName = regex.findFirstMatchIn(saveTableName).map(_.group(1)).get
        saveTableName = s"${saveTableName}_${totalCores * timesUpdated + tableNumber}"
        timesUpdated += 1
        if (timesUpdated == 65536) timesUpdated = 1 // Reset back to 1
    }
    
    
    def receive: Receive = {
        case SaveRemainingData =>
            if (agentRoundData.nonEmpty) saveToDB()
            if (numberOfSavesToDB != 0 || agentRoundData.nonEmpty) cleanTempTable()
    }
    
}

case class CleanAndSaveTable(cleanupFunction: String => Unit, tableName: String)
class DBCleaner extends Actor {
    private val timer: CustomTimer = new CustomTimer()
    def receive: Receive = {
        case CleanAndSaveTable(cleanupFunction, tableName) =>
            println(s"Cleaning table ${tableName}...")
            timer.start()
            cleanupFunction(tableName)
            timer.stop(s"Finished cleaning $tableName")
    }
}

class MemoryConfidenceDBSaver(tableNumber: Int, saveThreshold: Int) extends
  AgentRoundDataDBSaver[MemoryConfidenceRound](tableNumber) {
    saveTableName =  s"temp_table_mc_$tableNumber"
    def contactDB(): Unit = {
        DatabaseManager.insertBatchMemoryConfidenceRoundData(agentRoundData, saveTableName)
    }
    
    def cleanTempTable(): Unit = {
        dbCleaner ! CleanAndSaveTable(DatabaseManager.cleanTempTableMemoryConfidence, saveTableName)
    }
    
    override def receive: Receive = super.receive.orElse {
        case data: MemoryConfidenceRound =>
            agentRoundData += data
            if (agentRoundData.size == saveThreshold) saveToDB()
    }
}

class MemoryMajorityDBSaver(tableNumber: Int, saveThreshold: Int) extends
  AgentRoundDataDBSaver[MemoryMajorityRound](tableNumber) {
    saveTableName = s"temp_table_mm_$tableNumber"
    def contactDB(): Unit = {
        DatabaseManager.insertBatchMemoryMajorityRoundData(agentRoundData, saveTableName)
    }
    
    def cleanTempTable(): Unit = {
        dbCleaner ! CleanAndSaveTable(DatabaseManager.cleanTempTableMemoryMajority, saveTableName)
    }
    
    override def receive: Receive = super.receive.orElse {
        case data: MemoryMajorityRound =>
            agentRoundData += data
            if (agentRoundData.size == saveThreshold) saveToDB()
    }
}

class MemoryLessConfidenceDBSaver(tableNumber: Int, saveThreshold: Int) extends
  AgentRoundDataDBSaver[MemoryLessConfidenceRound](tableNumber) {
    saveTableName = s"temp_table_mlc_$tableNumber"
    def contactDB(): Unit = {
        DatabaseManager.insertBatchMemoryLessConfidenceRoundData(agentRoundData, saveTableName)
    }
    
    def cleanTempTable(): Unit = {
        dbCleaner ! CleanAndSaveTable(DatabaseManager.cleanTempTableMemorylessConfidence, saveTableName)
    }
    
    override def receive: Receive = super.receive.orElse {
        case data: MemoryLessConfidenceRound =>
            agentRoundData += data
            if (agentRoundData.size == saveThreshold) saveToDB()
    }
}

class MemoryLessMajorityDBSaver(tableNumber: Int, saveThreshold: Int) extends
  AgentRoundDataDBSaver[MemoryLessMajorityRound](tableNumber) {
    saveTableName = s"temp_table_mlm_$tableNumber"
    def contactDB(): Unit = {
        DatabaseManager.insertBatchMemoryLessMajorityRoundData(agentRoundData, saveTableName)
    }
    
    def cleanTempTable(): Unit = {
        dbCleaner ! CleanAndSaveTable(DatabaseManager.cleanTempTableMemorylessMajority, saveTableName)
    }
    
    override def receive: Receive = super.receive.orElse {
        case data: MemoryLessMajorityRound =>
            agentRoundData += data
            if (agentRoundData.size == saveThreshold) saveToDB()
    }
}
