import akka.actor.ActorSystem
import akka.dispatch.{PriorityGenerator, UnboundedPriorityMailbox}
import com.typesafe.config.Config

class PrioMailbox(settings: ActorSystem.Settings, config: Config)
  extends UnboundedPriorityMailbox(
      PriorityGenerator {
          case FinishedSaving => 0
          case StartedSaving => 0
          case _ => 1
      }
  )
