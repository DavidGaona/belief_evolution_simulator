import akka.actor.{ActorSystem, ActorRef}
import akka.dispatch.{PriorityGenerator, UnboundedPriorityMailbox}
import akka.dispatch._

import com.typesafe.config.Config
import java.util.concurrent.atomic.AtomicInteger

class PrioMailbox(settings: ActorSystem.Settings, config: Config)
  extends UnboundedPriorityMailbox(
      PriorityGenerator {
          case FinishedSaving => 0
          case SaveRemainingData => 2
          case _ => 1
      }
  )

class MonitoredPrioMailbox(settings: ActorSystem.Settings, config: Config)
  extends MailboxType with ProducesMessageQueue[MonitoredPrioMailbox.MonitoredPriorityQueue] {
    
    private val underlying = new UnboundedPriorityMailbox(
        PriorityGenerator {
            case FinishedSaving => 0
            case SaveRemainingData => 2
            case _ => 1
        }
    )
    
    override def create(owner: Option[ActorRef], system: Option[ActorSystem]): MessageQueue =
        new MonitoredPrioMailbox.MonitoredPriorityQueue(underlying.create(owner, system), owner, system)
}

object MonitoredPrioMailbox {
    class MonitoredPriorityQueue
    (
      queue: MessageQueue,
      owner: Option[ActorRef],
      system: Option[ActorSystem]
    ) extends MessageQueue {
        
        private val queueSize = new AtomicInteger(0)
        
        override def enqueue(receiver: ActorRef, handle: Envelope): Unit = {
            queue.enqueue(receiver, handle)
            val size = queueSize.incrementAndGet()
            if (size % 100000 == 0)
                println(s"Queue size: $size")
        }
        
        override def dequeue(): Envelope = {
            val envelope = queue.dequeue()
            if (envelope != null) {
                queueSize.decrementAndGet()
            }
            envelope
        }
        
        override def numberOfMessages: Int = queueSize.get()
        
        override def hasMessages: Boolean = queueSize.get() > 0
        
        override def cleanUp(owner: ActorRef, deadLetters: MessageQueue): Unit = {
            queue.cleanUp(owner, deadLetters)
            queueSize.set(0)
        }
    }
}

class DBActorMailbox(settings: ActorSystem.Settings, config: Config)
  extends UnboundedPriorityMailbox(
      PriorityGenerator {
          case data: MemoryConfidenceRound => 0
          case data: MemoryMajorityRound => 0
          case data: MemoryLessConfidenceRound => 0
          case data: MemoryLessMajorityRound => 0
          case SaveRemainingData => 2
          case _ => 1
      }
  )
  