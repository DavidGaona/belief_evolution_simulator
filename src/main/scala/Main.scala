import akka.actor.{Actor, ActorRef, ActorSystem, DeadLetter, Props}

import scala.util.Random
import akka.pattern.ask

import scala.math.random
//import akka.remote.transport.ActorTransportAdapter.AskTimeout
import akka.util.Timeout


import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success}
import spray.json._

import scala.reflect
import scala.math.{log, E}

// Printing

def caseClassToString(cc: Product): Unit = {
    val className = cc.getClass.getSimpleName
    val fields = cc.getClass.getDeclaredFields

    val values = fields.map { field =>
        field.setAccessible(true)
        val name = field.getName
        val value = field.get(cc)
        s"$name = $value"
    }

    println(s"$className(\n  ${values.mkString(",\n  ")}\n)")
}

// Distributions
sealed trait Distribution

case object Uniform extends Distribution

case class Normal(mean: Double, std: Double) extends Distribution

case class Exponential(lambda: Double) extends Distribution


// Global control

case class CreateNetworkBatch
(
  batchSize: Int,
  batchNumber: Int,
  numberOfAgents: Int,
  density: Int,
  stopThreshold: Double,
  degreeDistributionParameter: Double,
  distribution: Distribution
)


object Mains extends App {
    val numberOfBatches = 10
    val batchSize = 10
    val numOfNetworks = numberOfBatches * batchSize
    val system = ActorSystem("original")
    val monitor = system.actorOf(Props(new Monitor(Run, numOfNetworks)), "Monitor")
    //val listenerActor = system.actorOf(Props(new DeadLetterListener), "Listener")

//    for (j <- 1 to 10) {
//        for (i <- 1 to numOfNetworks) {
//            monitor ! CreateNetwork(s"Network${i}_density${j}", 1000, j, 0.001, 2.5, Uniform)
//        }
//    }
    val density = 5
    val numberOfAgents = 1000
    monitor ! CreateNetworkBatch(batchSize, numberOfBatches, numberOfAgents, density, 0.001, 2.5, Uniform)

//    for (i <- 1 to numOfNetworks) {
//        monitor ! CreateNetwork(s"Network${i}_density${density}", numberOfAgents, density, 0.001, 2.5, Uniform)
//    }

}

