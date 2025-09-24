package core.simulation.actors

import akka.actor.{Actor, ActorRef, Props}
import core.model.agent.behavior.bias.CognitiveBiases.Bias
import core.model.agent.behavior.silence.SilenceEffects.SilenceEffect
import core.model.agent.behavior.silence.SilenceStrategies.SilenceStrategy
import core.simulation.config.*
import core.simulation.config.SaveModes.SaveMode
import io.persistence.RoundRouter
import io.web.CustomRunInfo
import utils.datastructures.SnowflakeID
import utils.logging.Logger
import utils.rng.distributions.{CustomDistribution, Distribution, Uniform}
import utils.timers.CustomMultiTimer

import java.util.UUID
import scala.collection.mutable

// Monitor

// Containers

/**
 * Contains immutable configuration and runtime metadata for a simulation run,
 * providing thread-safe access to core run parameters for all actors
 * participating in the run.
 *
 * @param runID Unique identifier for the simulation run
 * @param channelId Web socket communication channel identifier assigned to this run
 * @param runMode Execution mode defining run behavior (Generated, Custom, etc.)
 * @param saveMode Data persistence strategy for legacy database compatibility
 * @param distribution Statistical distribution configuration for the simulation
 * @param startTime Unix timestamp marking the simulation start time
 * @param optionalMetaData Additional run-specific parameters (e.g., density)
 * @param agentLimit Maximum number of agents that can execute concurrently
 * @param numberOfNetworks Total count of networks in the simulation
 * @param agentsPerNetwork Agent count per network (uniform across all networks)
 * @param iterationLimit Maximum permitted simulation iterations before termination
 * @param seed Random number generator seed for reproducible results
 * @param stopThreshold Consensus threshold below which the simulation terminates
 */
case class RunMetadata(
    runID: Long,
    channelId: String,
    runMode: Byte,
    saveMode: SaveMode,
    distribution: Distribution,
    startTime: Long,
    optionalMetaData: Option[OptionalMetadata],
    var agentLimit: Int,
    numberOfNetworks: Int,
    var agentsPerNetwork: Int,
    iterationLimit: Int,
    seed: Long,
    stopThreshold: Float
)

case class OptionalMetadata(
    density: Option[Int],
    degreeDistribution: Option[Float]
)

// Messages
case object GetStatus

case class AddNetworks(
    runID: Long,
    channelId: String,
    agentTypeCount: Array[(SilenceStrategy, SilenceEffect, Int)],
    agentBiases: Array[(Bias, Int)],
    optionalParams: mutable.Map[Int, (Float, Float)],
    distribution: Distribution,
    saveMode: SaveMode,
    numberOfNetworks: Int,
    density: Int,
    iterationLimit: Int,
    seed: Long,
    degreeDistribution: Float,
    stopThreshold: Float
)

case class AddNetworksFromCSV(path: String, silenceEffect: SilenceEffect, silenceStrategy: SilenceStrategy, bias: Bias)

case class AddNetworksFromExistingRun(
    runId: Int,
    agentTypeCount: Array[(Byte, Byte, Int)],
    agentBiases: Array[(Byte, Float)],
    saveMode: Int,
    stopThreshold: Float,
    iterationLimit: Int
)

case class AddNetworksFromExistingNetwork(
    networkId: UUID,
    agentTypeCount: Array[(Byte, Byte, Int)],
    agentBiases: Array[(Byte, Float)],
    saveMode: SaveMode,
    stopThreshold: Float,
    iterationLimit: Int
)

case class Neighbors(
    source: String,
    target: String,
    influence: Float,
    bias: Byte
)

case class RunCustomNetwork(customInfo: CustomRunInfo)

case object RunComplete // Monitor -> Run

// Actor

/**
 * Monitor actor.
 * The actor in charge of allocating and coordinating runs, it receives the input from
 * the user and acts accordingly. This is the sole point of communication between user
 * input and the actor system. It handles the 3 types of RunMode s. Termination of this
 * actor means termination of the entire system. The system is composed of the hierarchy:
 * Monitor -> Run -> Network -> DeGrootianAgentManager
 * */
class Monitor extends Actor {
    // Memory Limits
    var memoryLeft: Long = (Runtime.getRuntime.maxMemory() * 0.95).toLong
    val agentLimit: Int = 16_777_216 // 16_777_216 10_485_760 4_194_304 1_048_576 8_388_608 2_097_152
    var currentUsage: Int = agentLimit
    
    // Router
    val saveThreshold: Int = 2_000_000
    RoundRouter.setSavers(context, saveThreshold)
    
    // Runs
    val activeRuns: mutable.HashMap[String, (ActorRef, Long)] = mutable.HashMap.empty[String, (ActorRef, Long)]
    var totalRuns: Int = 0
    var totalActiveNetworks: Long = 0L
    var totalActiveAgents: Long = 0L
    
    // Testing performance end
    val simulationTimers = new CustomMultiTimer
    
    def receive: Receive = {
        case RunCustomNetwork(customInfo) =>
            totalRuns += 1
            
            val runMetadata = RunMetadata(
                customInfo.runID,
                customInfo.channelId,
                RunMode.CUSTOM,
                customInfo.saveMode,
                CustomDistribution,
                System.currentTimeMillis(),
                None,
                agentLimit,
                1, 
                customInfo.agentBeliefs.length, 
                customInfo.iterationLimit, 
                0,
                customInfo.stopThreshold
            )
            val map = mutable.HashMap[(SilenceStrategy, SilenceEffect), Int]().withDefaultValue(0)
            for (i <- customInfo.agentSilenceStrategy.indices) {
                val id = (customInfo.agentSilenceStrategy(i), customInfo.agentSilenceEffect(i))
                map(id) += 1
            }
            
            val agentTypeCount: Array[(SilenceStrategy, SilenceEffect, Int)] =
                map.map { case ((strategy, effect), count) => (strategy, effect, count) }.toArray
            
            val runActor = context.actorOf(Props(new Run(runMetadata, customInfo, agentTypeCount)), s"R$totalRuns")
            trackRunMemory(runActor, 1, customInfo.agentBeliefs.length, customInfo.target.length)
            simulationTimers.start(s"${runActor.path.name}")

        
        case AddNetworks(runID, channelId, agentTypeCount, agentBiases, optionalParams, distribution, saveMode,
        numberOfNetworks, density, iterationLimit, seed, degreeDistribution, stopThreshold) =>
            val optionalMetadata = Some(OptionalMetadata(Some(density), Some(degreeDistribution)))
            val runMetadata = RunMetadata(
                runID,
                channelId,
                RunMode.GENERATED,
                saveMode,
                distribution,
                System.currentTimeMillis(),
                optionalMetadata,
                agentLimit,
                numberOfNetworks,
                agentTypeCount.map(_._3).sum,
                iterationLimit,
                seed,
                stopThreshold
            )
            totalRuns += 1
            val n = runMetadata.agentsPerNetwork
            val m = density
            
            val actor = context.actorOf(Props(new Run(runMetadata, agentTypeCount, agentBiases)), s"R$totalRuns")
            val numberOfNeighbors = (m * (m-1)) + (n - m) * (2 * m)
            trackRunMemory(actor, numberOfNetworks, runMetadata.agentsPerNetwork, numberOfNeighbors)
            
            simulationTimers.start(s"${actor.path.name}")
            actor ! StartRun
        
        case AddNetworksFromCSV(path, silenceEffect, silenceStrategy, bias) =>
            totalRuns += 1
            val optionalMetadata = Some(OptionalMetadata(Some(0), Some(0)))
            val runMetadata = RunMetadata(
                SnowflakeID.generateId(),
                "0",
                RunMode.CSV,
                SaveModes.DEBUG,
                Uniform,
                System.currentTimeMillis(),
                optionalMetadata,
                agentLimit,
                1,
                1,
                1_000_000,
                42L,
                0.00001f
            )
            val agentTypeCount = Array((silenceStrategy, silenceEffect, 18470))
            val agentBiases = Array((bias, 61157))
            val actor = context.actorOf(Props(new Run(runMetadata, path, agentTypeCount, agentBiases)), s"R$totalRuns")
            trackRunMemory(actor, 1, runMetadata.agentsPerNetwork, 61157)
            
            simulationTimers.start(s"${actor.path.name}")
            actor ! StartRun
            
        case RunComplete =>
            Logger.log("\nThe run has been complete\n")
            val senderActor = sender().path.name
            simulationTimers.stop(senderActor)
            memoryLeft += activeRuns(senderActor)._2
            activeRuns -= senderActor
            
        case GetStatus =>
            Logger.log(f"\nTotal runs: $totalRuns\n" +
                      f"Active runs: ${activeRuns.size}\n" +
                      f"Total active networks: $totalActiveNetworks\n" +
                      f"Total active agents: $totalActiveAgents\n")
            
            
    }
    
    
    private def trackRunMemory(runActor: ActorRef, numberOfNetworks: Int, agentsPerNetwork: Int, neighborsPerNetwork: Int):
    Unit = {
        // ~64 bytes per agent
        // ~9 bytes per neighbor
        // 95% memory max
        val runMemoryUsage = (numberOfNetworks * agentsPerNetwork * 64) +
          (numberOfNetworks * neighborsPerNetwork * 9)
        
        if (memoryLeft >= runMemoryUsage) {
        
        } else {
            Logger.logWarning(s"Exceeding memory limits: ${memoryLeft}")
        }
        memoryLeft -= runMemoryUsage
        activeRuns += (runActor.path.name -> (runActor, runMemoryUsage))
    }
    
    
}
