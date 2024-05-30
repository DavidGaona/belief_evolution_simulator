import akka.actor.{Actor, ActorRef, ActorSystem, Props, Stash}

import scala.reflect

// Distributions
sealed trait Distribution {
    def toString: String
}

case object CustomDistribution extends Distribution {
    override def toString: String = "custom"
}

case object Uniform extends Distribution {
    override def toString: String = "uniform"
}

case class Normal(mean: Double, std: Double) extends Distribution {
    override def toString: String = s"normal(mean=$mean, std=$std)"
}

case class Exponential(lambda: Double) extends Distribution {
    override def toString: String = s"exponential(lambda=$lambda)"
}

case class BiModal(peak1: Double, peak2: Double, lower: Double = 0, upper: Double = 1) extends Distribution {
    override def toString: String = s"biModal(peak1=$peak1, peak2=$peak2, lower=$lower, upper=$upper)"
    val bimodalDistribution: BimodalDistribution = BimodalDistribution(peak1, peak2, lower, upper)
}

object Distribution {
    def fromString(s: String): Option[Distribution] = s match {
        case "customDistribution" => Some(CustomDistribution)
        case "uniform" => Some(Uniform)
        case str if str.startsWith("normal") =>
            val params = str.stripPrefix("normal(mean=").stripSuffix(")").split(", std=")
            if (params.length == 2) Some(Normal(params(0).toDouble, params(1).toDouble)) else None
        case str if str.startsWith("exponential") =>
            val param = str.stripPrefix("exponential(lambda=").stripSuffix(")")
            Some(Exponential(param.toDouble))
        case str if str.startsWith("biModal") =>
            None
        case _ => None
    }
}


// Global control
case class AddNetworks
(
  numberOfNetworks: Int, 
  numberOfAgents: Int, 
  density: Int, 
  degreeDistribution: Float,
  stopThreshold: Float,
  distribution: Distribution,
  iterationLimit: Int
)

case class AddSpecificNetwork
(
  agents: Array[AgentInitialState], 
  stopThreshold: Float, 
  iterationLimit: Int,
  name: String
)

case class AgentInitialState
(
  name: String,
  initialBelief: Float,
  neighbors: Array[(String, Float)]
)



object Mains extends App {
    val system = ActorSystem("original")
    val monitor = system.actorOf(Props(new Monitor), "Monitor")

    val density = 2
    val numberOfAgents = 100
    val numberOfNetworks = 10
    monitor ! AddNetworks(numberOfNetworks, numberOfAgents, density, 2.5f, 0.001, Uniform, 2000)
    val runtime = Runtime.getRuntime
    val maxMemory = runtime.maxMemory() / (1024 * 1024)
    println(s"Max memory: $maxMemory MB")
    
//    val testi = system.actorOf(Props(new Testi), "Receiver")
//    val testo = system.actorOf(Props(new Testo(testi)).withDispatcher("prio-mailbox"), "Sender")
//    testo ! StartSending(100000)
//    testi ! StartedSaving
//    Thread.sleep(1000)
//    testi ! StartedSaving
//    Thread.sleep(1000)
//    testi ! StartedSaving
//    Thread.sleep(1000)
//    testi ! StartedSaving
//    Thread.sleep(10000)

}

