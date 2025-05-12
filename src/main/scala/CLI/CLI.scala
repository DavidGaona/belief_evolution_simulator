package CLI

import akka.actor.{ActorRef, ActorSystem}
import core.model.agent.behavior.bias.*
import core.model.agent.behavior.silence.*
import core.simulation.actors.*
import core.simulation.config.*
import utils.datastructures.ArrayList
import utils.rng.distributions.*

import scala.io.StdIn
import scala.util.Random


class CLI(system: ActorSystem, monitor: ActorRef) {
    def start(): Unit = {
        println("=== Interactive Akka Actors Simulation CLI ===")
        println("Type 'help' for available commands, 'exit' to quit")

        var running = true
        while(running) {
            print("\n> ")
            val input = StdIn.readLine()
            
            if (input == null) {
                println("Input stream closed. Exiting...")
                running = false
                system.terminate()
            } else {
                input.trim.toLowerCase match {
                    case "exit" | "quit" | "q" =>
                        println("Shutting down actor system...")
                        running = false
                        system.terminate()
                    case "help" =>
                        printHelp()
                    case "" =>
                    // Do nothing for empty input
                    case cmd =>
                        processCommand(cmd)
                }
            }
        }
    }

    def printHelp(): Unit = {
        println("""
                  |Available commands:
                  |
                  |run [numNetworks] [numAgents] [density] [iterationLimit] [stopThreshold] [saveMode]
                  |  - Runs a generated simulation with the specified parameters
                  |  - [numAgents] should be written as <SilenceStrategyType>:<SilenceEffectType>:<numberOfAgents> can chain multiple agent types, default is memoryless majority
                  |  - Silence strategies: DeGroot, Majority, Threshold(threshold: Float) and Confidence(threshold: Float, openMindedness: Int)
                  |  - Silence effects: DeGroot, Memory and Memoryless
                  |  - Examples:
                  |  - run 1 4 3 25 0.0001 Debug
                  |  - run 1 majority:Memoryless:4 majority:Memory:4 3 25 0.0001 Debug
                  |
                  |run-specific
                  |  - Starts a guided setup for a specific network configuration
                  |
                  |status
                  |  - Shows the current system status
                  |
                  |exit, quit, q
                  |  - Exit the application
                  |""".stripMargin)
    }

    def processCommand(cmd: String): Unit = {
        val tokens = cmd.split("\\s+")

        tokens(0) match {
            case "run-specific" =>
                println("This will guide you through creating a specific network configuration.")
                try {
                    // Collect agent information
                    println("How many agents do you want to create?")
                    val numAgents = StdIn.readLine().toInt
                    // ToDo use use defined seed for this random else use current approach
                    val random = Random(System.nanoTime())
                    val agents = new Array[AgentInitialState](numAgents)
                    val neighbors = new scala.collection.mutable.ArrayBuffer[Neighbors]()

                    for (i <- 0 until numAgents) {
                        println(s"\nAgent ${i+1} configuration:")
                        println("Name: (or press Enter for default)")
                        val name = {
                            val input = StdIn.readLine()
                            if (input.trim.isEmpty) s"Agent${i+1}" else input
                        }

                        println("Initial belief (0.0-1.0):")
                        val belief = StdIn.readLine().trim.toFloat

                        println("Tolerance radius (0.0-1.0):")
                        val radius = StdIn.readLine().toFloat

                        println("Tolerance offset (Belief - radius to belief + radius):")
                        var offset = StdIn.readLine().toFloat
                        while (offset < (belief - radius) || offset > (radius + belief)) {
                            println("Insert a valid tolerance offset (Belief - radius to belief + radius):")
                            offset = StdIn.readLine().toFloat
                        }
                        
                        println("Silence strategy (1=Majority, 2=Confidence):")
                        val strategyType = StdIn.readLine().toInt match {
                            case 1 => SilenceStrategyType.Majority
                            case 2 => {
                                println("Belief expression threshold (0.0-1.0 or R/r for random uses seed if provided):")

                                val betInput = StdIn.readLine().trim
                                var threshold: Float = 0f
                                if (betInput == "R" || betInput == "r") {
                                    threshold = random.nextFloat()
                                } else {
                                    threshold = betInput.toFloat
                                }
                                
                                println("Open mindedness (Integer > 0 or R/r for random[1-50]):")
                                val omInput = StdIn.readLine().trim
                                var openMindedness: Int = 1
                                if (betInput == "R" || betInput == "r") {
                                    threshold = random.nextInt(50) + 1
                                } else {
                                    threshold = betInput.toInt
                                }
                                SilenceStrategyType.Confidence(threshold, openMindedness)
                            }
                            case _ => SilenceStrategyType.Majority
                        }

                        println("Silence effect (1=Memory, 2=Opinion):")
                        val effectType = StdIn.readLine().toInt match {
                            case 1 => SilenceEffectType.Memory
                            case 2 => SilenceEffectType.Memoryless
                            case _ => SilenceEffectType.Memory
                        }

                        agents(i) = AgentInitialState(
                            name, belief, radius, offset, strategyType, effectType
                            )

                        // Add connections for this agent
                        println(s"How many connections should ${name} have?")
                        val numConnections = StdIn.readLine().toInt

                        for (j <- 0 until numConnections) {
                            println(s"Connection ${j+1}:")
                            println("Connect to which agent? (Enter agent number 1-$numAgents):")
                            val targetIdx = StdIn.readLine().toInt - 1

                            if (targetIdx >= 0 && targetIdx < numAgents && targetIdx != i) {
                                val targetName = if (targetIdx < i) agents(targetIdx).name else s"Agent${targetIdx+1}"

                                println("Influence (0.0-1.0):")
                                val influence = StdIn.readLine().toFloat

                                println("Bias type (1=DeGroot, 2=Confirmation, 3=Backfire, 4=Authority, 5=Insular):")
                                val biasType = StdIn.readLine().toInt match {
                                    case 1 => CognitiveBiasType.DeGroot
                                    case 2 => CognitiveBiasType.Confirmation
                                    case 3 => CognitiveBiasType.Backfire
                                    case 4 => CognitiveBiasType.Authority
                                    case 5 => CognitiveBiasType.Insular
                                    case _ => CognitiveBiasType.DeGroot
                                }

                                neighbors += Neighbors(name, targetName, influence, biasType)
                            } else {
                                println("Invalid agent number, connection skipped.")
                            }
                        }
                    }

                    println("\nNetwork parameters:")
                    println("Iteration limit:")
                    val iterLimit = StdIn.readLine().toInt

                    println("Stop threshold (e.g., 0.001):")
                    val stopThreshold = StdIn.readLine().toFloat

                    println("Network name:")
                    val networkName = StdIn.readLine()

                    println(s"Starting specific network '$networkName' with ${agents.length} agents...")

                    monitor ! AddSpecificNetwork(
                        agents = agents,
                        neighbors = neighbors.toArray,
                        distribution = Uniform,
                        saveMode = Agentless,
                        stopThreshold = stopThreshold,
                        iterationLimit = iterLimit,
                        name = networkName,
                        recencyFunction = None
                        )

                } catch {
                    case e: Exception => println(s"Error creating specific network: ${e.getMessage}")
                }

            case "run" =>
                if (tokens.length < 6) {
                    println("Error: Not enough parameters")
                    println("Usage: run-basic [numNetworks] [agentTypesPerNetwork] [density] " +
                              "[iterationLimit] [stopThreshold] [saveMode]")
                    return
                }

                try {

                    var tokenOffset = 0
                    val agentTypeCount = ArrayList[(SilenceStrategyType, SilenceEffectType, Int)]()
                    var hasNext = true
                    while (hasNext) {
                        hasNext = false
                        val parts = tokens(2 + tokenOffset).split(":")
                        if (parts.length == 3) {
                            tokenOffset += 1
                            hasNext = true
                            agentTypeCount.add((
                              SilenceStrategyType.fromString(parts(0)),
                              SilenceEffectType.fromString(parts(1)),
                              parts(2).toInt
                            ))
                        }
                    }
                    tokenOffset -= 1
                    if (tokenOffset == -1) {
                        tokenOffset = 0
                        agentTypeCount.add((
                          SilenceStrategyType.fromString("default"),
                          SilenceEffectType.fromString("default"),
                          tokens(2).toInt
                        ))
                    }
                    
                    val numNetworks = tokens(1).toInt
                    val density = tokens(3 + tokenOffset).toInt
                    val iterLimit = tokens(4 + tokenOffset).toInt
                    val stopThreshold = tokens(5 + tokenOffset).toFloat
                    val saveModeStr = tokens(6 + tokenOffset)
                    val saveMode = stringToSaveMode(saveModeStr) match {
                        case Some(mode) => mode
                        case None =>
                            println(s"Error: Invalid save mode '$saveModeStr'")
                            printSaveModeHelp()
                            return
                    }
                    
                    val seed: Option[Long] = if (tokens(7) == "--seed") Some(tokens(8 + tokenOffset).toLong) else None
                    
                    println(s"Starting generated run with $numNetworks networks, density $density...")
                    
                    monitor ! AddNetworks(
                        agentTypeCount = agentTypeCount.toArray,
                        agentBiases = Array((0.toByte, 0)),
                        distribution = Uniform,
                        saveMode = saveMode,
                        recencyFunction = None,
                        numberOfNetworks = numNetworks,
                        density = density,
                        iterationLimit = iterLimit,
                        seed = seed,
                        degreeDistribution = 2.5f,
                        stopThreshold = stopThreshold
                    )
                } catch {
                    case e: Exception => println(s"Error parsing parameters: ${e.getMessage}")
                }
                
            case "status" =>
                monitor ! GetStatus

            case _ =>
                println(s"Unknown command: ${tokens(0)}")
                println("Type 'help' to see available commands")
        }
    }
    
    
    /**
     * Converts a string to a SaveMode object
     */
    def stringToSaveMode(str: String): Option[SaveMode] = {
        str.toLowerCase match {
            case "full" => Some(Full)
            case "standard" => Some(Standard)
            case "standardlight" | "standard-light" => Some(StandardLight)
            case "roundless" => Some(Roundless)
            case "agentlesstyped" | "agentless-typed" => Some(AgentlessTyped)
            case "agentless" => Some(Agentless)
            case "performance" => Some(Performance)
            case "debug" => Some(Debug)
            case s if s.startsWith("roundsampling") || s.startsWith("round-sampling") =>
                try {
                    // Parse format like "roundsampling:10" or "round-sampling:10"
                    val parts = s.split(":")
                    if (parts.length == 2) {
                        val interval = parts(1).toInt
                        Some(RoundSampling(interval))
                    } else {
                        None
                    }
                } catch {
                    case _: Exception => None
                }
            case s if s.endsWith("neighborless") || s.endsWith("-neighborless") =>
                // Handle neighborless modes like "standard-neighborless"
                val baseMode = s.split("-")(0)
                stringToSaveMode(baseMode).flatMap { mode =>
                    if (mode.allowsNeighborless) Some(SaveMode.withNeighborless(mode)) else None
                }
            case _ => None
        }
    }
    
    /**
     * Converts a SaveMode object to a descriptive string
     */
    def saveModeToString(mode: SaveMode): String = mode match {
        case Full => "Full"
        case Standard => "Standard"
        case StandardLight => "StandardLight"
        case Roundless => "Roundless"
        case AgentlessTyped => "AgentlessTyped"
        case Agentless => "Agentless"
        case Performance => "Performance"
        case Debug => "Debug"
        case RoundSampling(interval) => s"RoundSampling($interval)"
        case NeighborlessMode(baseMode) => s"${saveModeToString(baseMode)}-Neighborless"
        case _ => "Unknown"
    }
    
    /**
     * Prints the available save mode options
     */
    def printSaveModeHelp(): Unit = {
        println("Available save modes:")
        println("  full              - Saves all data including all rounds")
        println("  standard          - Saves first and last rounds, agents, networks")
        println("  standard-light    - Saves first round, agents, networks")
        println("  roundless         - Saves agents, networks, agent types")
        println("  agentless-typed   - Saves networks and agent types only")
        println("  agentless         - Saves networks only")
        println("  performance       - Saves minimal data to database")
        println("  debug             - Saves nothing")
        println("  roundsampling:N   - Samples rounds at interval N (e.g., roundsampling:10)")
        println("")
        println("Neighborless variants (append '-neighborless' to a mode):")
        println("  standard-neighborless, roundless-neighborless, etc.")
        println("  Note: Full mode does not support neighborless variant")
    }
}
