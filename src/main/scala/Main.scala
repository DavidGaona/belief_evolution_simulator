import akka.actor.{ActorSystem, Props}
import com.typesafe.config.ConfigFactory
import core.simulation.actors.Monitor
import io.web.Server
import utils.logging.Logger

import java.lang
import scala.reflect

object Main extends App {
    val maxMemory = Runtime.getRuntime.maxMemory() / (1024 * 1024)
    Logger.log(s"Max memory: $maxMemory")
    
    val system = ActorSystem("original", ConfigFactory.load().getConfig("app-dispatcher"))
    val monitor = system.actorOf(Props(new Monitor), "Monitor")
    Server.initialize(system, monitor)
}
