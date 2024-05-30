import akka.actor.{Actor, ActorRef, Props}

import java.nio.file.{Files, Paths}
import spray.json.*


// Messages
case object RunMultiRoundAnalysis

// Actor
class DataSaver(initialCount: Int, dataSavingPath: String) extends Actor {
    var counter: Int = initialCount
    val threshold: Int = (initialCount * 0.05).toInt
    val multiRoundRunner: ActorRef = context.actorOf(Props(new MultiRoundRunner(dataSavingPath)), 
        name = "MultiRoundRunner")

    def receive: Receive = {
        case _ =>
            counter -= 1
            
//            println(initialCount)
//            if (counter % threshold == 0 && csvCounter != initialCount) {
//                val percentage = ((initialCount - csvCounter) * 100) / initialCount
//                println(s"${(initialCount - csvCounter)} out of $initialCount($percentage%) processed.")
//            }
            if (counter == 0)
                context.parent ! AnalysisComplete
                //multiRoundRunner ! RunMultiRoundAnalysis
            
    }
}
