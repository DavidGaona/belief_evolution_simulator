import akka.actor.{Actor, ActorRef, ActorSystem, Props, Stash}
import com.typesafe.config.ConfigFactory

import java.util.UUID
import scala.collection.mutable.ArrayBuffer
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


// Agent types
sealed trait AgentType

case object MemoryConfidence extends AgentType

case object MemoryMajority extends AgentType

case object MemoryLessConfidence extends AgentType

case object MemoryLessMajority extends AgentType


// Global control
case class AddNetworks
(
  numberOfNetworks: Int,
  numberOfAgents: Int,
  density: Int,
  degreeDistribution: Float,
  stopThreshold: Float,
  distribution: Distribution,
  iterationLimit: Int,
  agentTypeCount: Map[AgentType, Int]
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
  agentType: AgentType,
  neighbors: Array[(String, Float)]
)

def normalizeByWeightedSum(arr: Array[(String, Float)], selfInfluence: Float = 0f):
Array[(String, Float)] = {
    if (arr.isEmpty) return arr
    
    val totalSum = arr.map(_._2).sum + selfInfluence
    if (totalSum == 0) return arr
    
    arr.map { case (str, value) =>
        (str, value / totalSum)
    }
}

// Custom network inserting functions
def simulateFromPreviousNetwork(id: UUID, agentType: AgentType, iterationLimit: Int, name: String):
AddSpecificNetwork = {
    AddSpecificNetwork(
        agents = DatabaseManager.reRunSpecificNetwork(id, agentType),
        stopThreshold = 0.0001,
        iterationLimit = iterationLimit,
        name = name
    )
}

def simulateFromCustomExample(beliefs: Array[Float], selfInfluence: Array[Float] = Array.emptyFloatArray,
                              influences: Array[Array[(String, Float)]], agentType: AgentType,
                              iterationLimit: Int, name: String):
AddSpecificNetwork = {
    val agents: Array[AgentInitialState] = Array.ofDim(beliefs.length)
    
    for (i <- beliefs.indices) {
        agents(i) = AgentInitialState(
            name = s"Agent${i+1}",
            initialBelief = beliefs(i),
            agentType = agentType,
            neighbors = if (selfInfluence.isEmpty) influences(i) else
                normalizeByWeightedSum(influences(i), selfInfluence(i))
        )
    }
    
    AddSpecificNetwork(
        agents = agents,
        stopThreshold = 0.001,
        iterationLimit = iterationLimit,
        name = name
    )
}

class GreeterActor(name: String) extends Actor {
    def receive: Receive = {
        case _ =>
            println(s"Hello there, I'm $name")
    }
}

object Mains extends App {
    val system = ActorSystem("original", ConfigFactory.load().getConfig("app-dispatcher"))
    val monitor = system.actorOf(Props(new Monitor), "Monitor")
    
    val density = 1
    val numberOfAgents = 25
    val numberOfNetworks = 1
    
    
    monitor ! AddNetworks(
        numberOfNetworks,
        numberOfAgents,
        density,
        2.5f,
        0.001,
        Uniform,
        1500,
        Map(
            MemoryLessConfidence -> 0,
            MemoryConfidence -> 0,
            MemoryLessMajority -> 0,
            MemoryMajority -> numberOfAgents
        )
    )
    
    
    val msg = simulateFromPreviousNetwork(
        UUID.fromString("01907514-c3f4-7000-b676-e82bc785d2cc"),
        MemoryMajority,
        1500,
        "ReDo_from_memory"
    )

    //println(msg.agents.foreach(agent => println(agent.neighbors.mkString(s"${agent.name} Array(", ", ", ")"))))
    //monitor ! msg
    
    
    val vax = simulateFromCustomExample(
        Array(1.0f, 0.9f, 0.8f, 0.2f, 0.1f, 0.0f),
        Array(1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f),
        Array(
            Array(("Agent2", 0.6f), ("Agent6", 1.0f)),
            Array(("Agent1", 0.6f)),
            Array(("Agent1", 0.4f), ("Agent4", 0.2f)),
            Array(("Agent2", 0.4f), ("Agent3", 0.2f)),
            Array(("Agent3", 0.6f)),
            Array(("Agent4", 0.4f), ("Agent5", 0.6f)),
        ),
        MemoryLessMajority,
        1500,
        "Vaccine_example"
    )
    
    //monitor ! vax
    
    val vaxxed = simulateFromCustomExample(
        beliefs = Array(1.0f, 0.5f, 0.0f),
        influences = Array(
            Array(("Agent2", 0.01f)),
            Array(("Agent1", 0.01f), ("Agent3", 0.01f)),
            Array(("Agent2", 0.01f)),
        ),
        agentType = MemoryMajority,
        iterationLimit = 5000,
        name = "memory_on_off"
    )
    
//    monitor ! vaxxed

    val runtime = Runtime.getRuntime
    val maxMemory = runtime.maxMemory() / (1024 * 1024)
}

