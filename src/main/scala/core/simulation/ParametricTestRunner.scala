package core.simulation

import akka.actor.{Actor, ActorSystem, Props, ActorRef}
import com.typesafe.config.ConfigFactory
import core.model.agent.behavior.bias.CognitiveBiases
import core.model.agent.behavior.silence.{SilenceEffects, SilenceStrategies}
import core.simulation.actors.*
import core.simulation.config.*
import io.db.DatabaseManager
import utils.datastructures.SnowflakeID
import utils.rng.distributions.Uniform
import scala.concurrent.{Await, Promise}
import scala.concurrent.duration.*
import java.io.{FileWriter, PrintWriter}

case class ParametricResult(
    populationSize: Int,
    scenario: String,
    runId: Long,
    totalDurationMs: Long,
    consensusCount: Int,
    avgRounds: Float
)

class ParametricOrchestrator(
    sizes: Seq[Int],
    scenarios: Seq[(String, Option[CoevolutionConfig])],
    networksPerConfig: Int,
    promise: Promise[List[ParametricResult]]
) extends Actor {
    
    var results = List.empty[ParametricResult]
    var currentSizeIdx = 0
    var currentScenarioIdx = 0
    
    var currentRunActor: ActorRef = null
    var currentRunID: Long = 0L
    var startTime: Long = 0L
    
    def startNextRun(): Unit = {
        if (currentSizeIdx >= sizes.length) {
            promise.success(results.reverse)
            context.stop(self)
            return
        }
        
        val N = sizes(currentSizeIdx)
        val (scenarioName, coevConfig) = scenarios(currentScenarioIdx)
        
        println(s"\n>>> Running Parametric Configuration: N = $N | Scenario = $scenarioName ($networksPerConfig networks)")
        
        val densityParam = 4 // typical density
        
        if (!GlobalState.APP_MODE.skipDatabase) {
            val idOpt = DatabaseManager.createRun(
                runMode = RunMode.GENERATED,
                saveMode = 0.toByte, // FULL saveMode
                numberOfNetworks = networksPerConfig,
                density = Some(densityParam),
                degreeDistribution = Some(2.5f),
                stopThreshold = 0.001f,
                iterationLimit = 500,
                initialDistribution = "uniform"
            )
            currentRunID = idOpt.getOrElse(SnowflakeID.generateId())
        } else {
            currentRunID = SnowflakeID.generateId()
        }
        startTime = System.currentTimeMillis()
        
        // Define agent types: 50% MAJORITY-MEMORYLESS, 50% DEGROOT-DEGROOT
        val majorityCount = N / 2
        val degrootCount = N - majorityCount
        val agentTypeCount = Array(
            (SilenceStrategies.MAJORITY, SilenceEffects.MEMORYLESS, majorityCount),
            (SilenceStrategies.DEGROOT, SilenceEffects.DEGROOT, degrootCount)
        )
        
        // Calculate number of edges in Barabási-Albert network for density densityParam
        val edgeCount = (densityParam * (densityParam - 1)) + (N - densityParam) * (2 * densityParam)
        
        val agentBiases = Array(
            (CognitiveBiases.DEGROOT, edgeCount)
        )
        
        // Create RunMetadata
        val metadata = RunMetadata(
            runID = currentRunID,
            channelId = "parametric_test",
            runMode = RunMode.GENERATED,
            saveMode = SaveModes.FULL, // FULL persists to legacy DB
            distribution = Uniform,
            startTime = startTime,
            optionalMetaData = Some(OptionalMetadata(Some(densityParam), Some(2.5f))),
            agentLimit = 16777216,
            numberOfNetworks = networksPerConfig,
            agentsPerNetwork = N,
            iterationLimit = 500, // Reasonable limit for test execution speed
            seed = 42L,
            stopThreshold = 0.001f,
            coevolutionConfig = coevConfig
        )
        
        // If Database is enabled, save the run config
        if (!GlobalState.APP_MODE.skipDatabase) {
            DatabaseManager.saveGeneratedRun(
                id = currentRunID,
                seed = 42L,
                density = densityParam,
                iterationLimit = 500,
                totalNetworks = networksPerConfig,
                agentsPerNetwork = N,
                stopThreshold = 0.001f,
                agentTypeDistributions = agentTypeCount,
                cognitiveBiasDistributions = agentBiases
            )
        }
        
        // Spawn the Run actor as a child of the Orchestrator
        currentRunActor = context.actorOf(
            Props(new Run(metadata, agentTypeCount, agentBiases)),
            s"Run_${N}_${scenarioName.replaceAll("[^a-zA-Z0-9]", "_")}"
        )
        
        currentRunActor ! StartRun
    }
    
    override def preStart(): Unit = {
        io.persistence.RoundRouter.setSavers(context, 2000000)
        startNextRun()
    }
    
    def receive: Receive = {
        case RunComplete =>
            val duration = System.currentTimeMillis() - startTime
            println(s"<<< Completed Configuration in ${duration} ms (Run ID: $currentRunID)")
            
            // Query results from database
            val consensusCount = if (!GlobalState.APP_MODE.skipDatabase) {
                DatabaseManager.getConsensusCount(currentRunID)
            } else 0
            
            val avgRounds = if (!GlobalState.APP_MODE.skipDatabase) {
                DatabaseManager.getAvgRounds(currentRunID)
            } else 0.0f
            
            results = ParametricResult(
                populationSize = sizes(currentSizeIdx),
                scenario = scenarios(currentScenarioIdx)._1,
                runId = currentRunID,
                totalDurationMs = duration,
                consensusCount = consensusCount,
                avgRounds = avgRounds
            ) :: results
            
            // Advance scenario and size indices
            currentScenarioIdx += 1
            if (currentScenarioIdx >= scenarios.length) {
                currentScenarioIdx = 0
                currentSizeIdx += 1
            }
            
            startNextRun()
    }
}

object ParametricTestRunner {
    def main(args: Array[String]): Unit = {
        println("=========================================================================")
        println("                 Belief Evolution Simulator: Parametric Test             ")
        println("=========================================================================")
        
        // Verify database settings
        println(s"App Mode: ${GlobalState.APP_MODE.description}")
        if (GlobalState.APP_MODE.skipDatabase) {
            println("WARNING: APP_SKIP_DATABASE is true. Results will NOT be saved to DB.")
        }
        
        val system = ActorSystem("ParametricSystem", ConfigFactory.load().getConfig("app-dispatcher"))
        val promise = Promise[List[ParametricResult]]()
        
        // Define base-two population sizes
        val sizes = Seq(64, 128, 256, 512, 1024, 2048, 4096)
        
        // Define Scenarios
        val scenarios = Seq(
            ("Baseline (Static)", None),
            ("Scenario A (Pure Creation, Prop C)", Some(CoevolutionConfig(pBreak = 0.0f, pCreate = 0.02f, rewiringStrategy = 0))),
            ("Scenario B (Uniform, Prop A)", Some(CoevolutionConfig(pBreak = 0.2f, pCreate = 0.02f, rewiringStrategy = 0))),
            ("Scenario B (Homophilic, Prop B)", Some(CoevolutionConfig(pBreak = 0.2f, pCreate = 0.02f, rewiringStrategy = 1)))
        )
        
        val networksPerConfig = 3 // 3 networks per configuration to keep execution times reasonable
        
        val orchestrator = system.actorOf(
            Props(new ParametricOrchestrator(sizes, scenarios, networksPerConfig, promise)),
            "ParametricOrchestrator"
        )
        
        // Await completion
        val results = Await.result(promise.future, Duration.Inf)
        
        // Write results to CSV file
        val csvFile = "parametric_results.csv"
        val writer = new PrintWriter(new FileWriter(csvFile))
        try {
            writer.println("PopulationSize,Scenario,RunID,TotalDurationMs,ConsensusCount,AvgRounds")
            results.foreach { r =>
                writer.println(s"${r.populationSize},${r.scenario},${r.runId},${r.totalDurationMs},${r.consensusCount},${r.avgRounds}")
            }
        } finally {
            writer.close()
        }
        
        // Print results summary
        println("\n=========================================================================")
        println("                         PARAMETRIC TEST RESULTS                         ")
        println("=========================================================================")
        println(f"| ${"Size"}%-6s | ${"Scenario"}%-34s | ${"Run ID"}%-20s | ${"Time (ms)"}%-9s | ${"Consensus"}%-9s | ${"Avg Rnd"}%-7s |")
        println("+--------+------------------------------------+----------------------+-----------+-----------+---------+")
        results.foreach { r =>
            println(f"| ${r.populationSize}%-6d | ${r.scenario}%-34s | ${r.runId}%-20d | ${r.totalDurationMs}%-9d | ${s"${r.consensusCount}/$networksPerConfig"}%-9s | ${r.avgRounds}%-7.1f |")
        }
        println("=========================================================================")
        println(s"Results successfully saved to '$csvFile'")
        println("You can use this data directly in your thesis and run exploration.R on these Run IDs.")

        // Terminate Actor System
        try {
            Await.ready(system.terminate(), 10.seconds)
        } catch {
            case _: Exception => println("Actor system coordinated shutdown timed out, proceeding to exit.")
        }
    }
}
