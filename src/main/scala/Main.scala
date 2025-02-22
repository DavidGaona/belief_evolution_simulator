import akka.actor.{ActorRef, ActorSystem, Props}
import benchmarking.OptimizedRdtsc
import com.typesafe.config.ConfigFactory
import rng.distributions.BimodalDistribution

import java.lang
import scala.reflect
import scala.util.Random

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


// Run metadata


// Global control

def normalizeByWeightedSum(arr: Array[(String, Float)], selfInfluence: Float = 0f): Array[(String, Float)] = {
    if (arr.isEmpty) return arr
    
    val totalSum = arr.map(_._2).sum + selfInfluence
    if (totalSum == 0) return arr
    
    arr.map { case (str, value) =>
        (str, value / totalSum)
    }
}

// Custom network inserting functions
//def simulateFromPreviousNetwork(id: UUID, silenceStrategy: SilenceStrategy, silenceEffect: SilenceEffect, 
//                                iterationLimit: Int, name: String):
//AddSpecificNetwork = {
//    AddSpecificNetwork(
//        agents = DatabaseManager.reRunSpecificNetwork(id, agentType),
//        stopThreshold = 0.0001,
//        iterationLimit = iterationLimit,
//        name = name
//    )
//}

//def simulateFromCustomExample(beliefs: Array[Float], selfInfluence: Array[Float] = Array.emptyFloatArray,
//                              influences: Array[Array[(String, Float)]], silenceStrategy: SilenceStrategy,
//                              silenceEffect: SilenceEffect, iterationLimit: Int, name: String, 
//                              tolerances: Array[Float] = Array.empty[Float]): AddSpecificNetwork = {
//    val agents: Array[AgentInitialState] = Array.ofDim(beliefs.length)
//    
//    for (i <- beliefs.indices) {
//        agents(i) = AgentInitialState(
//            name = s"Agent${i + 1}",
//            initialBelief = beliefs(i),
//            agentType = agentType,
//            if (selfInfluence.isEmpty) influences(i)
//            else normalizeByWeightedSum(influences(i), selfInfluence(i)),
//            if (tolerances.nonEmpty) tolerances(i)
//            else 0.1, // Default tolerance value
//        )
//    }
//    
//    AddSpecificNetwork(
//        agents = agents,
//        stopThreshold = 0.001,
//        iterationLimit = iterationLimit,
//        name = name
//    )
//}

object globalTimer {
    val timer: CustomTimer = new CustomTimer()
}

def estimateCPUTimerFreq(millisToWait: Long = 100L): Long = {
    val osFreq = 1_000_000_000L
    val cpuStart = OptimizedRdtsc.getRdtsc()
    val osStart = System.nanoTime()
    
    var osEnd = 0L
    var osElapsed = 0L
    val osWaitTime = osFreq * millisToWait / 1000
    while (osElapsed < osWaitTime) {
        osEnd = System.nanoTime()
        osElapsed = osEnd - osStart
    }
    val cpuEnd = OptimizedRdtsc.getRdtsc()
    val cpuElapsed = cpuEnd - cpuStart
    var cpuFreq = 0L
    if (osElapsed > 0) {
        cpuFreq = osFreq * cpuElapsed / osElapsed
    }
    
    cpuFreq
}

object Main extends App {
    val system = ActorSystem("original", ConfigFactory.load().getConfig("app-dispatcher"))
    val monitor = system.actorOf(Props(new Monitor), "Monitor")

    val density = 13
    val numberOfNetworks = 256
    val numberOfAgents = 524_288 // 4_194_304 1_048_576
    
    
    globalTimer.timer.start()
//    val densityRunner: Array[Option[ActorRef]] = new Array[Option[ActorRef]](3)
//    var i = 0
//    while (i < 3) {
//        //val maxDensity = i - 1
//        //val numberOfAgent = i
//        densityRunner(i) = Some(system.actorOf(Props(
//            new DensityRunner(density - 1 + i, 15, monitor, numberOfAgents, numberOfNetworks)),
//                                               s"DensityRunner$i"))
//        monitor ! GetDensityRunner(densityRunner(i).get)
//        i += 1
//    }
    
    
    monitor ! AddNetworks(
        agentTypeCount = Array((SilenceStrategyType.Majority, SilenceEffectType.Memoryless, numberOfAgents)),
        agentBiases = Array((CognitiveBiasType.DeGroot, 1.0f)),
        distribution = Uniform,
        saveMode = Agentless, //NeighborlessMode(Roundless) Agentless StandardLight Debug
        recencyFunction = None,
        numberOfNetworks = numberOfNetworks,
        density = density,
        iterationLimit = 1000,
        degreeDistribution = 2.5f,
        stopThreshold = 0.001f
    )
    
//    monitor ! AddNetworks(
//        agentTypeCount = Array((SilenceStrategyType.Majority, SilenceEffectType.Memoryless, numberOfAgents)),
//        agentBiases = Array((CognitiveBiasType.DeGroot, 1.0f)),
//        distribution = Uniform,
//        saveMode = Agentless, //NeighborlessMode(Roundless) Agentless StandardLight
//        recencyFunction = None,
//        numberOfNetworks = numberOfNetworks,
//        density = 3,
//        iterationLimit = 1000,
//        degreeDistribution = 2.5f,
//        stopThreshold = 0.001f
//        )
    
    //DatabaseManager.exportToMaudeTXT(s"${numberOfAgents}_agents_memoryless.txt", numberOfAgents)
//    val customRun = AddSpecificNetwork(
//        Array(
//            AgentInitialState("Agent1", 1f, 0.2, 0.0, SilenceStrategyType.Majority, SilenceEffectType.Memoryless),
//            AgentInitialState("Agent2", 0.8f, 0.15, 0.0, SilenceStrategyType.Majority, SilenceEffectType.Memoryless),
//            AgentInitialState("Agent3", 0.5f, 0.1, 0.0, SilenceStrategyType.Majority, SilenceEffectType.Memoryless),
//            AgentInitialState("Agent4", 0.2f, 0.15, 0.0, SilenceStrategyType.Majority, SilenceEffectType.Memoryless),
//            AgentInitialState("Agent5", 0.0f, 0.2, 0.0, SilenceStrategyType.Majority, SilenceEffectType.Memoryless)
//        ),
//        Array(
//            Neighbors("Agent1", "Agent2", 0.4f, CognitiveBiasType.DeGroot),
//            Neighbors("Agent1", "Agent3", 0.1f, CognitiveBiasType.DeGroot),
//
//            Neighbors("Agent2", "Agent1", 0.4f, CognitiveBiasType.DeGroot),
//            Neighbors("Agent2", "Agent3", 0.1f, CognitiveBiasType.DeGroot),
//
//            Neighbors("Agent3", "Agent1", 0.2f, CognitiveBiasType.DeGroot),
//            Neighbors("Agent3", "Agent2", 0.2f, CognitiveBiasType.DeGroot),
//            Neighbors("Agent3", "Agent4", 0.2f, CognitiveBiasType.DeGroot),
//            Neighbors("Agent3", "Agent5", 0.2f, CognitiveBiasType.DeGroot),
//
//            Neighbors("Agent4", "Agent3", 0.1f, CognitiveBiasType.DeGroot),
//            Neighbors("Agent4", "Agent5", 0.4f, CognitiveBiasType.DeGroot),
//
//            Neighbors("Agent5", "Agent3", 0.1f, CognitiveBiasType.DeGroot),
//            Neighbors("Agent5", "Agent4", 0.4f, CognitiveBiasType.DeGroot),
//        ),
//        CustomDistribution,
//        Debug,
//        0.001f,
//        1000,
//        "First_try",
//        None
//    )
//
//     monitor ! customRun
//
//    var outer_weak = 0.21f
//    var outer_strong = 0.15f
//    var inner_weak = 0.4f
//    var inner_strong = 0.32f
//    val privateConsensus = AddSpecificNetwork(
//        Array(
//            AgentInitialState("Agent1", 1f, 0.2, 0.0, SilenceStrategyType.Majority, SilenceEffectType.Memory),
//            AgentInitialState("Agent2", 0.9f, 0.1, 0.0, SilenceStrategyType.Majority, SilenceEffectType.Memory),
//            AgentInitialState("Agent3", 0.1f, 0.05, 0.0, SilenceStrategyType.Majority, SilenceEffectType.Memory),
//            AgentInitialState("Agent4", 0f, 0.15, 0.0, SilenceStrategyType.Majority, SilenceEffectType.Memory)
//            ),
//        Array(
//            Neighbors("Agent1", "Agent2", inner_weak, CognitiveBiasType.DeGroot),
//            Neighbors("Agent1", "Agent3", outer_weak, CognitiveBiasType.DeGroot),
//            Neighbors("Agent1", "Agent4", outer_strong, CognitiveBiasType.DeGroot),
//
//            Neighbors("Agent2", "Agent1", inner_strong, CognitiveBiasType.DeGroot),
//            Neighbors("Agent2", "Agent3", outer_weak, CognitiveBiasType.DeGroot),
//            Neighbors("Agent2", "Agent4", outer_strong, CognitiveBiasType.DeGroot),
//
//            Neighbors("Agent3", "Agent1", outer_strong, CognitiveBiasType.DeGroot),
//            Neighbors("Agent3", "Agent2", outer_weak, CognitiveBiasType.DeGroot),
//            Neighbors("Agent3", "Agent4", inner_strong, CognitiveBiasType.DeGroot),
//
//            Neighbors("Agent4", "Agent1", outer_strong, CognitiveBiasType.DeGroot),
//            Neighbors("Agent4", "Agent2", outer_weak, CognitiveBiasType.DeGroot),
//            Neighbors("Agent4", "Agent3", inner_weak, CognitiveBiasType.DeGroot)
//            ),
//        CustomDistribution,
//        Debug,
//        0.001f,
//        1000,
//        "First_try",
//        None
//        )
//    monitor ! privateConsensus
    
//    outer_weak = 0.4f
//    outer_strong = 0.15f
//    inner_weak = 0.35f
//    inner_strong = 0.15f
//    val privateDissensus = AddSpecificNetwork(
//        Array(
//            AgentInitialState("Agent1", 1f, 0.05, 0.0, SilenceStrategyType.Majority, SilenceEffectType.Memory),
//            AgentInitialState("Agent2", 0.9f, 0.05, 0.0, SilenceStrategyType.Majority, SilenceEffectType.Memory),
//            AgentInitialState("Agent3", 0.1f, 0.05, 0.0, SilenceStrategyType.Majority, SilenceEffectType.Memory),
//            AgentInitialState("Agent4", 0f, 0.05, 0.0, SilenceStrategyType.Majority, SilenceEffectType.Memory)
//            ),
//        Array(
//            // Agent2 --inner_weak--> Agent1
//            Neighbors("Agent1", "Agent2", inner_weak, CognitiveBiasType.DeGroot),
//            Neighbors("Agent1", "Agent3", outer_weak, CognitiveBiasType.DeGroot),
//            Neighbors("Agent1", "Agent4", outer_strong, CognitiveBiasType.DeGroot),
//
//            Neighbors("Agent2", "Agent1", inner_strong, CognitiveBiasType.DeGroot),
//            Neighbors("Agent2", "Agent3", outer_weak, CognitiveBiasType.DeGroot),
//            Neighbors("Agent2", "Agent4", outer_strong, CognitiveBiasType.DeGroot),
//
//            Neighbors("Agent3", "Agent1", outer_strong, CognitiveBiasType.DeGroot),
//            Neighbors("Agent3", "Agent2", outer_weak, CognitiveBiasType.DeGroot),
//            Neighbors("Agent3", "Agent4", inner_strong, CognitiveBiasType.DeGroot),
//
//            Neighbors("Agent4", "Agent1", outer_strong, CognitiveBiasType.DeGroot),
//            Neighbors("Agent4", "Agent2", outer_weak, CognitiveBiasType.DeGroot),
//            Neighbors("Agent4", "Agent3", inner_weak, CognitiveBiasType.DeGroot)
//            ),
//        CustomDistribution,
//        Full,
//        0.001f,
//        1000,
//        "First_try",
//        None
//        )
//
//     monitor ! privateDissensus
    
    val runtime = Runtime.getRuntime
    val maxMemory = runtime.maxMemory() / (1024 * 1024)
}

