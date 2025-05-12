//import CLI.CLI
import CLI.CLI
import akka.actor.{ActorSystem, Props}
import com.typesafe.config.ConfigFactory
import core.model.agent.behavior.bias.CognitiveBiasType
import core.model.agent.behavior.silence.{SilenceEffectType, SilenceStrategyType}
import core.simulation.actors.{AddNetworks, Monitor}
import core.simulation.config.{Agentless, Debug}
import io.web.Server
import utils.rng.distributions.Uniform

import java.lang
import scala.reflect

// Run metadata


// Global control

object Main extends App {
    // Initialize actor system and monitor
    val system = ActorSystem("original", ConfigFactory.load().getConfig("app-dispatcher"))
    val monitor = system.actorOf(Props(new Monitor), "Monitor")
    Server.initialize(system, monitor)
    
    val cli = new CLI(system, monitor)
    cli.start()

//    val density = 3 // [4095, 1098, 520, 257, 128, 64, 32, 16, 8, 4, 2, 1]
//    val numberOfNetworks = 1
//    val numberOfAgents = 4 /// 4_194_304 2_097_152 1_048_576
//
//    monitor ! AddNetworks(
//        agentTypeCount = Array((SilenceStrategyType.Majority, SilenceEffectType.Memoryless, numberOfAgents)), // .Confidence(0.001, 1)
//        agentBiases = Array((CognitiveBiasType.DeGroot, 1.0f)),
//        distribution = Uniform,
//        saveMode = Debug, //NeighborlessMode(Roundless) Agentless StandardLight Debug
//        recencyFunction = None,
//        numberOfNetworks = numberOfNetworks,
//        density = density,
//        iterationLimit = 1000,
//        degreeDistribution = 2.5f,
//        stopThreshold = 0.00000001f
//    )
    
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
    println(s"Max memory: $maxMemory")
}

