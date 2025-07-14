//import CLI.CLI

import akka.actor.{ActorSystem, Props}
import com.typesafe.config.ConfigFactory
import core.model.agent.behavior.bias.CognitiveBiases
import core.model.agent.behavior.silence.{SilenceEffect, SilenceStrategy}
import core.simulation.actors.{AddNetworksFromCSV, Monitor}
import io.web.Server

import java.lang
import scala.collection.mutable
import scala.io.Source
import scala.reflect

object Main extends App {
    val system = ActorSystem("original", ConfigFactory.load().getConfig("app-dispatcher"))
    val monitor = system.actorOf(Props(new Monitor), "Monitor")
    Server.initialize(system, monitor)

    // val cli = new CLI(system, monitor)
    // cli.start()

    monitor ! AddNetworksFromCSV("rt-pol.csv", SilenceEffect.MEMORYLESS, SilenceStrategy.MAJORITY, CognitiveBiases.DEGROOT)
    
    val runtime = Runtime.getRuntime
    val maxMemory = runtime.maxMemory() / (1024 * 1024)
    println(s"Max memory: $maxMemory")
}
