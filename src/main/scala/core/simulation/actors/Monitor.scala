package core.simulation.actors

import akka.actor.{Actor, ActorRef, Props}
import core.model.agent.behavior.silence.*
import core.model.agent.behavior.bias.*
import core.simulation.config.*
import io.persistence.RoundRouter
import utils.rng.distributions.Distribution
import utils.timers.CustomMultiTimer

import java.util.UUID
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

// Monitor

// Containers

case class RunMetadata(
    runMode: RunMode,
    saveMode: SaveMode,
    distribution: Distribution,
    startTime: Long,
    optionalMetaData: Option[OptionalMetadata],
    var runId: Option[Int],
    var agentLimit: Int,
    numberOfNetworks: Int,
    agentsPerNetwork: Int,
    iterationLimit: Int,
    seed: Long,
    stopThreshold: Float
)

case class OptionalMetadata(
    recencyFunction: Option[(Float, Int) => Float],
    density: Option[Int],
    degreeDistribution: Option[Float]
)

// Messages
case object GetStatus

case class AddNetworks(
    agentTypeCount: Array[(SilenceStrategyType, SilenceEffectType, Int)],
    agentBiases: Array[(Byte, Int)],
    distribution: Distribution,
    saveMode: SaveMode,
    recencyFunction: Option[(Float, Int) => Float],
    numberOfNetworks: Int,
    density: Int,
    iterationLimit: Int,
    seed: Option[Long],
    degreeDistribution: Float,
    stopThreshold: Float
)

case class AddSpecificNetwork(
    agents: Array[AgentInitialState],
    neighbors: Array[Neighbors],
    distribution: Distribution,
    saveMode: SaveMode,
    stopThreshold: Float,
    iterationLimit: Int,
    name: String,
    recencyFunction: Option[(Float, Int) => Float]
)

case class AddNetworksFromExistingRun(
    runId: Int,
    agentTypeCount: Array[(SilenceStrategyType, SilenceEffectType, Int)],
    agentBiases: Array[(CognitiveBiasType, Float)],
    recencyFunction: Option[(Float, Int) => Float],
    saveMode: SaveMode,
    stopThreshold: Float,
    iterationLimit: Int
)

case class AddNetworksFromExistingNetwork(
    networkId: UUID,
    agentTypeCount: Array[(SilenceStrategyType, SilenceEffectType, Int)],
    agentBiases: Array[(CognitiveBiasType, Float)],
    recencyFunction: Option[(Float, Int) => Float],
    saveMode: SaveMode,
    stopThreshold: Float,
    iterationLimit: Int
)

case class AgentInitialState(
    name: String,
    initialBelief: Float,
    toleranceRadius: Float,
    toleranceOffset: Float,
    silenceStrategy: SilenceStrategyType,
    silenceEffect: SilenceEffectType
)

case class Neighbors(
    source: String,
    target: String,
    influence: Float,
    bias: CognitiveBiasType
)

case object RunComplete // Monitor -> Run

// Actor
class Monitor extends Actor {
    // Limits
    val agentLimit: Int = 16_777_216 // 16_777_216 10_485_760 4_194_304 1_048_576 8_388_608 2_097_152
    var currentUsage: Int = agentLimit
    
    // Router
    val saveThreshold: Int = 2_000_000
    RoundRouter.setSavers(context, saveThreshold)
    
    // Runs
    val activeRuns: mutable.HashMap[String, (ActorRef, Long, Long)] = mutable.HashMap.empty[String, (ActorRef, Long, Long)]
    var totalRuns: Int = 0
    var totalActiveNetworks: Long = 0L
    var totalActiveAgents: Long = 0L
    
    // Testing performance end
    val simulationTimers = new CustomMultiTimer
    
    def receive: Receive = {
        case AddSpecificNetwork(agents, neighbors, distribution, saveMode, stopThreshold, iterationLimit, 
                                name, recencyFunction) =>
            totalRuns += 1
            val optionalMetadata = {
                if (recencyFunction.isEmpty) None
                else Some(OptionalMetadata(recencyFunction, None, None))
            }
            
            val runMetadata = RunMetadata(
                RunMode.Custom,
                saveMode,
                distribution,
                System.currentTimeMillis(),
                optionalMetadata,
                None,
                agentLimit,
                1, agents.length, iterationLimit, 0, stopThreshold)
            val actor = context.actorOf(Props(new Run(runMetadata, agents, neighbors, name)), s"R$totalRuns")
            activeRuns += (actor.path.name -> (actor, 1L, agents.length))
        
        case RunComplete =>
            println("\nThe run has been complete\n")
            val senderActor = sender().path.name
            simulationTimers.stop(senderActor)
            totalActiveNetworks -= activeRuns(senderActor)._2
            totalActiveAgents -= activeRuns(senderActor)._3
            activeRuns -= senderActor
            
        case GetStatus =>
            println(f"\nTotal runs: $totalRuns\n" +
                      f"Active runs: ${activeRuns.size}\n" +
                      f"Total active networks: $totalActiveNetworks\n" +
                      f"Total active agents: $totalActiveAgents\n")
        
        case AddNetworks(agentTypeCount, agentBiases, distribution, saveMode, recencyFunction, numberOfNetworks,
                         density, iterationLimit, seed, degreeDistribution, stopThreshold) =>
            val optionalMetadata = Some(OptionalMetadata(recencyFunction, Some(density), Some(degreeDistribution)))
            // Here we transform the seed to some random seed based on the current time and some run parameters
            val revisedSeed: Long = if (seed.isEmpty) System.nanoTime() + numberOfNetworks + agentBiases(0)._2 else seed.get
            val runMetadata = RunMetadata(
                RunMode.Generated,
                saveMode,
                distribution,
                System.currentTimeMillis(),
                optionalMetadata,
                None,
                agentLimit,
                numberOfNetworks, agentTypeCount.map(_._3).sum, iterationLimit, revisedSeed, stopThreshold)
            totalRuns += 1
            totalActiveNetworks += runMetadata.numberOfNetworks
            totalActiveAgents += runMetadata.agentsPerNetwork * runMetadata.numberOfNetworks
            
            val actor = context.actorOf(Props(new Run(runMetadata, agentTypeCount, agentBiases)), s"R$totalRuns")
            activeRuns += (actor.path.name -> (actor, runMetadata.numberOfNetworks,
              runMetadata.agentsPerNetwork * runMetadata.numberOfNetworks))
            simulationTimers.start(s"${actor.path.name}")
            actor ! StartRun
            
            
    }
    
    def reBalanceUsage(): Unit = {
        
    }
    
    
}
