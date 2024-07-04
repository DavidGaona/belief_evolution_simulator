import akka.actor.{Actor, ActorContext, ActorLogging, ActorRef, Props}

import scala.util.control.Breaks.*
import scala.util.Random
import scala.reflect.ClassTag
import scala.jdk.CollectionConverters._

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.UUID


// Data Storing
case object FinishedSaving

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
) // ~225 bits

// Actor
@Deprecated
class DBDataLoadBalancer extends Actor with ActorLogging {
    case class DBSaver
    (
      actorRef: ActorRef = null,
      var available: Boolean = true,
      var statesHandled: Int = 0,
      var wasActive: Boolean = false,
      var timesPicked: Int = 0
    )

    case class DBSaverTypeCounts 
    (
      var roundRobinIndex: Int = 0,
      var currentIndex: Int = 0,
      var finishedSaving: Boolean = false
    )
    
    val timer: CustomTimer = new CustomTimer()
    
    val totalCores = Runtime.getRuntime.availableProcessors()
    val limitPerDBSaver: Int = 1500000 // 1750000 ~50MB (0.05GB) per actor
    
    val dbSavers: Map[AgentType, (DBSaverTypeCounts, Array[DBSaver])] = Map(
        MemoryLessConfidence -> (DBSaverTypeCounts(), Array.fill(totalCores)(DBSaver())),
        MemoryConfidence -> (DBSaverTypeCounts(), Array.fill(totalCores)(DBSaver())),
        MemoryLessMajority -> (DBSaverTypeCounts(), Array.fill(totalCores)(DBSaver())),
        MemoryMajority -> (DBSaverTypeCounts(), Array.fill(totalCores)(DBSaver()))
    )
    
    override def preStart(): Unit = {
        for (i <- 0 until 4){
            var dbSavers: Array[DBSaver] = Array.empty
            var agentClass: Class[? <: AgentRoundDataDBSaver[?]] = null
            i match {
                case 0 =>
                    dbSavers = this.dbSavers(MemoryLessConfidence)._2
                    agentClass = classOf[MemoryLessConfidenceDBSaver]
                case 1 =>
                    dbSavers = this.dbSavers(MemoryConfidence)._2
                    agentClass = classOf[MemoryConfidenceDBSaver]
                case 2 =>
                    dbSavers = this.dbSavers(MemoryLessMajority)._2
                    agentClass = classOf[MemoryLessMajorityDBSaver]
                case 3 =>
                    dbSavers = this.dbSavers(MemoryMajority)._2
                    agentClass = classOf[MemoryMajorityDBSaver]
            }
            
            for (j <- 0 until totalCores) {
                val actorRef = context.actorOf(Props(
                    agentClass,
                    (totalCores * i) + j + 1, limitPerDBSaver).withMailbox("priority-mailbox"),
                    s"DB${(totalCores * i) + j + 1}"
                )
                if (j == 0) dbSavers(0) = DBSaver(actorRef, true, 0, false, 1)
                else dbSavers(j) = DBSaver(actorRef, true, 0, false, 0)
            }
        }
        
    }
    
    private def pickAvailableActor(agentType: AgentType): ActorRef = {
        val currentIndex = dbSavers(agentType)._1.currentIndex
        if (currentIndex >= 0) {
            val saver = dbSavers(agentType)._2(currentIndex)
            saver.statesHandled += 1
            saver.available = saver.statesHandled < limitPerDBSaver
            if (!saver.available)
                saver.statesHandled -= 1
                dbSavers(agentType)._1.currentIndex = findAvailableIndex(agentType)
            saver.wasActive = true
            saver.actorRef
        } else {
            val roundRobinIndex = dbSavers(agentType)._1.roundRobinIndex
            val saver = dbSavers(agentType)._2(roundRobinIndex)
            saver.statesHandled += 1
            saver.wasActive = true
            dbSavers(agentType)._1.roundRobinIndex = (roundRobinIndex + 1) % dbSavers(agentType)._2.length
            saver.actorRef
        }
    }
    
    private def findAvailableIndex(agentType: AgentType): Int = {
        val candidates = dbSavers(agentType)._2.zipWithIndex.filter(_._1.available)
        
        if (candidates.isEmpty) {
            return -1
        }
        
        val minTimesPicked = candidates.map(_._1.timesPicked).min
        val topCandidates = candidates.filter(_._1.timesPicked == minTimesPicked).map(_._2)
        
        val indexPicked =
            if (topCandidates.length == 1) topCandidates.head
            else topCandidates(Random.nextInt(topCandidates.length))
        
        dbSavers(agentType)._2(indexPicked).timesPicked += 1
        indexPicked
    }
    
    private def updateDBSaverState(dbSaver: ActorRef): Unit = {
        breakable {
            dbSavers.foreach { case (_, (counts, savers)) =>
                for (i <- savers.indices) {
                    if (savers(i).actorRef == dbSaver) {
                        savers(i).available = true
                        savers(i).statesHandled -= limitPerDBSaver
                        break()
                    }
                }
            }
        }
    }
    
    def receive: Receive = {
        case FinishedSaving =>
            val dbSaver = sender()
            updateDBSaverState(dbSaver)
            
        case data: MemoryConfidenceRound =>
            val selectedDBSaver = pickAvailableActor(MemoryConfidence)
            selectedDBSaver ! data
        
        case data: MemoryMajorityRound =>
            val selectedDBSaver = pickAvailableActor(MemoryMajority)
            selectedDBSaver ! data
        
        case data: MemoryLessConfidenceRound =>
            val selectedDBSaver = pickAvailableActor(MemoryLessConfidence)
            selectedDBSaver ! data
        
        case data: MemoryLessMajorityRound =>
            val selectedDBSaver = pickAvailableActor(MemoryLessMajority)
            selectedDBSaver ! data
        
        case SaveRemainingData =>
            dbSavers.values.foreach { saver =>
              saver._2.foreach(
                  dbSaver => if (dbSaver.wasActive) dbSaver.actorRef ! SaveRemainingData
              )
            }
    }
}

object RoundDataRouters {
    private val memoryConfidenceRouter: RoundDataBaseRouter = new RoundDataBaseRouter()
    private val memoryMajorityRouter: RoundDataBaseRouter = new RoundDataBaseRouter()
    private val memoryLessConfidenceRouter: RoundDataBaseRouter = new RoundDataBaseRouter()
    private val memoryLessMajorityRouter: RoundDataBaseRouter = new RoundDataBaseRouter()

    def createRouters(context: ActorContext, numberOfSavers: Int, limitPerDBSaver: Int): Unit = {
        memoryConfidenceRouter.initialize(context, numberOfSavers, limitPerDBSaver,
            (tableNumber: Int, threshold: Int) => new MemoryConfidenceDBSaver(tableNumber, threshold))
        
        memoryMajorityRouter.initialize(context, numberOfSavers, limitPerDBSaver,
            (tableNumber: Int, threshold: Int) => new MemoryMajorityDBSaver(tableNumber, threshold))
        
        memoryLessConfidenceRouter.initialize(context, numberOfSavers, limitPerDBSaver,
            (tableNumber: Int, threshold: Int) => new MemoryLessConfidenceDBSaver(tableNumber, threshold))
        
        memoryLessMajorityRouter.initialize(context, numberOfSavers, limitPerDBSaver,
            (tableNumber: Int, threshold: Int) => new MemoryLessMajorityDBSaver(tableNumber, threshold))
    }
    
    def getDBSaver(agentType: AgentType): ActorRef = {
        agentType match {
            case MemoryConfidence => memoryConfidenceRouter.getNextSaver
            case MemoryMajority => memoryMajorityRouter.getNextSaver
            case MemoryLessConfidence => memoryLessConfidenceRouter.getNextSaver
            case MemoryLessMajority => memoryLessMajorityRouter.getNextSaver
        }
    }
    
    def saveRemainingData(): Unit = {
        memoryConfidenceRouter.saveRemainingData()
        memoryMajorityRouter.saveRemainingData()
        memoryLessConfidenceRouter.saveRemainingData()
        memoryLessMajorityRouter.saveRemainingData()
    }
    
}

class RoundDataBaseRouter {
    private val dbSavers = new ConcurrentLinkedQueue[ActorRef]()
    private val counter = new AtomicInteger(0)
    private val threshold = new AtomicInteger(0)
    private val currentSaver = new AtomicReference[ActorRef]()
    
    def initialize[T <: AgentRoundDataDBSaver[?]]
    (
      context: ActorContext,
      numberOfSavers: Int,
      threshold: Int,
      saverType: (Int, Int) => T
    )(implicit classTag: ClassTag[T]): Unit = {
        dbSavers.clear()
        for (i <- 0 until numberOfSavers)
            val actorRef = context.actorOf(
                Props(classTag.runtimeClass, i + 1, threshold).withMailbox("priority-mailbox"),
                s"${classTag.runtimeClass.toString.split(" ")(1)}_${i + 1}"
            )
            dbSavers.add(actorRef)
        currentSaver.set(dbSavers.peek())
        this.threshold.set(threshold)
    }
    
    def getNextSaver: ActorRef = {
        if (counter.incrementAndGet() >= threshold.get()) {
            rotateQueue()
        }
        currentSaver.get()
    }
    
    private def rotateQueue(): Unit = {
        val oldHead = dbSavers.poll()
        if (oldHead != null) {
            dbSavers.offer(oldHead)
            currentSaver.set(dbSavers.peek())
            counter.set(0)
        }
    }
    
    def saveRemainingData(): Unit = {
        dbSavers.forEach(_ ! SaveRemainingData)
    }
    
    def updateSavers(savers: Seq[ActorRef]): Unit = {
        val newQueue = new ConcurrentLinkedQueue(savers.asJava)
        dbSavers.clear()
        dbSavers.addAll(newQueue)
        if (!dbSavers.contains(currentSaver.get())) {
            currentSaver.set(dbSavers.peek())
            counter.set(0)
        }
    }
}
