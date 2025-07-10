//import CLI.CLI

import akka.actor.{ActorSystem, Props}
import com.typesafe.config.ConfigFactory
import core.simulation.actors.Monitor
import core.simulation.config.AppMode
import io.web.Server

import java.lang
import scala.reflect

object Main extends App {
    // Initialize actor system and Monitor actor.
    val system = ActorSystem("original", ConfigFactory.load().getConfig("app-dispatcher"))
    val monitor = system.actorOf(Props(new Monitor), "Monitor")
    Server.initialize(system, monitor)
    
    // val cli = new CLI(system, monitor)
    // cli.start()
    
    val runtime = Runtime.getRuntime
    val maxMemory = runtime.maxMemory() / (1024 * 1024)
    println(s"Max memory: $maxMemory")
}

