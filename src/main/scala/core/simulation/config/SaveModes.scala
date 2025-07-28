package core.simulation.config

import utils.logging.Logger

/**
 * Controls what simulation data is saved to the database during execution.
 * Uses a bitfield approach where each bit represents a different data category.
 *
 * <p>Bit Layout:
 * <ul>
 * <li>Bit 0 (0x01): INCLUDES_FIRST_ROUND - Save initial simulation state</li>
 * <li>Bit 1 (0x02): INCLUDES_ROUNDS - Save intermediate round data</li>
 * <li>Bit 2 (0x04): INCLUDES_LAST_ROUND - Save final simulation state</li>
 * <li>Bit 3 (0x08): INCLUDES_NETWORKS - Save network topology and structure</li>
 * <li>Bit 4 (0x10): INCLUDES_NEIGHBORS - Save agent neighbor relationships</li>
 * <li>Bit 5 (0x20): INCLUDES_AGENTS - Save individual agent data</li>
 * <li>Bit 6 (0x40): INCLUDES_AGENT_TYPES - Save agent type definitions</li>
 * <li>Bit 7 (0x80): SAVES_TO_DB - Enable database persistence (master flag)</li>
 * </ul>
 *
 * <p>Performance Impact: More inclusive modes have higher storage and I/O overhead.
 * Choose the minimal mode that captures the data you need for analysis.
 */
object SaveModes {
    opaque type SaveMode = Int
    
    // Individual flag definitions (bits 0-7)
    /**
     * Save the initial state of the simulation (round 0).<br>
     * Includes: Initial agent positions, network setup, starting parameters.<br>
     * Use when: You need to analyze simulation initialization or compare starting conditions.
     */
    private final val INCLUDES_FIRST_ROUND: Int = 0x01
    
    /**
     * Save intermediate round data during simulation execution.<br>
     * Includes: Round-by-round agent states, network changes, intermediate metrics.<br>
     * Use when: You need detailed evolution tracking or step-by-step analysis.<br>
     * Warning: High storage overhead for long simulations.
     */
    private final val INCLUDES_ROUNDS: Int = 0x02
    
    /**
     * Save the final state of the simulation (last round).<br>
     * Includes: Final agent positions, end state metrics, convergence data.<br>
     * Use when: You need simulation outcomes and final results.
     */
    private final val INCLUDES_LAST_ROUND: Int = 0x04
    
    /**
     * Save network topology and structural information.<br>
     * Includes: Node connections, edge weights, network metadata, topology changes.<br>
     * Use when: Network structure is important for your analysis.
     */
    private final val INCLUDES_NETWORKS: Int = 0x08
    
    /**
     * Save agent neighbor relationships and local connectivity.<br>
     * Includes: Who is connected to whom, local neighborhood data, relationship changes.<br>
     * Use when: Local interactions and neighborhood effects are important.<br>
     * Note: Requires INCLUDES_AGENTS to be meaningful.
     */
    private final val INCLUDES_NEIGHBORS: Int = 0x10
    
    /**
     * Save individual agent data and states.<br>
     * Includes: Agent properties, positions, internal states, behavior data.<br>
     * Use when: You need agent-level analysis or individual tracking.
     */
    private final val INCLUDES_AGENTS: Int = 0x20
    
    /**
     * Save agent type definitions and classifications.<br>
     * Includes: Agent types, behavioral parameters, type-specific configurations.<br>
     * Use when: You need to analyze behavior by agent type or understand type distributions.
     */
    private final val INCLUDES_AGENT_TYPES: Int = 0x40
    
    /**
     * Master flag that enables database persistence.<br>
     * Must be set for any data to be saved to the database.<br>
     * When disabled: No database operations occur (useful for testing/debugging).
     */
    private final val SAVES_TO_DB: Int = 0x80
    
    extension (saveMode: SaveMode) {
        /** Check if initial round state is saved */
        def includesFirstRound: Boolean = (saveMode & INCLUDES_FIRST_ROUND) != 0
        
        /** Check if intermediate rounds data is saved */
        def includesRounds: Boolean = (saveMode & INCLUDES_ROUNDS) != 0
        
        /** Check if final round state is saved */
        def includesLastRound: Boolean = (saveMode & INCLUDES_LAST_ROUND) != 0
        
        /** Check if networks are saved */
        def includesNetworks: Boolean = (saveMode & INCLUDES_NETWORKS) != 0
        
        /** Check if agent neighbor relationships are saved */
        def includesNeighbors: Boolean = (saveMode & INCLUDES_NEIGHBORS) != 0
        
        /** Check if constant individual agent data is saved */
        def includesAgents: Boolean = (saveMode & INCLUDES_AGENTS) != 0
        
        /** Check if agent type definitions are saved */
        def includesAgentTypes: Boolean = (saveMode & INCLUDES_AGENT_TYPES) != 0
        
        /** Check if database persistence is enabled */
        def savesToDB: Boolean = (saveMode & SAVES_TO_DB) != 0
        
        /** Get the underlying integer value for serialization */
        def toInt: Int = saveMode
    }
    
    // Predefined save mode combinations
    /**
     * Full save mode - saves all possible simulation data.<br>
     * Includes: Everything (all rounds, networks, neighbors, agents, agent types).<br>
     * Use when: You need complete simulation history and maximum detail for analysis.<br>
     * Performance: Highest storage and I/O overhead, slowest execution.
     */
    final val FULL: SaveMode = INCLUDES_FIRST_ROUND | INCLUDES_ROUNDS | INCLUDES_LAST_ROUND | INCLUDES_NETWORKS |
      INCLUDES_NEIGHBORS | INCLUDES_AGENTS | INCLUDES_AGENT_TYPES | SAVES_TO_DB
    
    /**
     * Standard save mode - saves key simulation data without intermediate rounds.<br>
     * Includes: First round, last round, networks, neighbors, agents, agent types.<br>
     * Excludes: Intermediate round data.<br>
     * Use when: You need comprehensive data but want to avoid round-by-round storage overhead.<br>
     * Performance: Good balance between detail and efficiency.
     */
    final val STANDARD: SaveMode = INCLUDES_FIRST_ROUND | INCLUDES_LAST_ROUND | INCLUDES_NETWORKS |
      INCLUDES_NEIGHBORS | INCLUDES_AGENTS | INCLUDES_AGENT_TYPES | SAVES_TO_DB
    
    /**
     * Standard Light save mode - essential data without last round details.<br>
     * Includes: First round, networks, agents, agent types.<br>
     * Excludes: Intermediate rounds, last round, neighbor relationships.<br>
     * Use when: You need basic simulation data with minimal storage requirements.<br>
     * Performance: Lower overhead than STANDARD, good for large-scale runs.
     */
    final val STANDARD_LIGHT: SaveMode = INCLUDES_FIRST_ROUND | INCLUDES_NETWORKS | INCLUDES_AGENTS |
      INCLUDES_AGENT_TYPES | SAVES_TO_DB
    
    /**
     * Roundless save mode - saves structural and agent data without temporal information.<br>
     * Includes: Networks, agents, agent types.<br>
     * Excludes: All round data (first, intermediate, last).<br>
     * Use when: You only care about final network structure and agent distribution.<br>
     * Performance: Fast execution, minimal temporal data storage.
     */
    final val ROUNDLESS: SaveMode = INCLUDES_NETWORKS | INCLUDES_AGENTS | INCLUDES_AGENT_TYPES | SAVES_TO_DB
    
    /**
     * Agentless Typed save mode - saves network and type information without individual agents.<br>
     * Includes: Networks, agent types.<br>
     * Excludes: Individual agent data, rounds, neighbor relationships.<br>
     * Use when: You need network topology and type definitions but not individual agent tracking.<br>
     * Performance: Very fast, minimal storage for large agent populations.
     */
    final val AGENTLESS_TYPED: SaveMode = INCLUDES_NETWORKS | INCLUDES_AGENT_TYPES | SAVES_TO_DB
    
    /**
     * Agentless save mode - saves only network structure.<br>
     * Includes: Networks only.<br>
     * Excludes: All agent data, rounds, types, neighbors.<br>
     * Use when: You only need final network topology for analysis.<br>
     * Performance: Fastest database operations, minimal storage.
     */
    final val AGENTLESS: SaveMode = INCLUDES_NETWORKS | SAVES_TO_DB
    
    /**
     * Performance save mode - database enabled but no actual data saved.<br>
     * Includes: Only the database persistence flag.<br>
     * Excludes: All simulation data.<br>
     * Use when: You want to test database connectivity without data overhead.<br>
     * Performance: Maximum execution speed, database operations without data storage.
     */
    final val PERFORMANCE: SaveMode = SAVES_TO_DB
    
    /**
     * Debug save mode - no database operations at all.<br>
     * Includes: Nothing.<br>
     * Excludes: All data and database operations.<br>
     * Use when: Testing simulation logic without any database overhead or persistence.<br>
     * Performance: Absolute fastest execution, no I/O operations.
     */
    final val DEBUG: SaveMode = 0
    
    /**
     * Convert a numeric code to a predefined SaveMode.
     * Used for backward compatibility and configuration file parsing.
     *
     * @param code Numeric identifier for the save mode (0-7)
     * @return Corresponding SaveMode, defaults to DEBUG for unknown codes
     */
    def codeToSaveMode(code: Byte): SaveMode = {
        code match {
            case 0x00 => FULL
            case 0x01 => STANDARD
            case 0x02 => STANDARD_LIGHT
            case 0x03 => ROUNDLESS
            case 0x04 => AGENTLESS_TYPED
            case 0x05 => AGENTLESS
            case 0x06 => PERFORMANCE
            case 0x07 => DEBUG
            case _ =>
                Logger.logWarning(s"Unknown save mode code: $code, defaulting to DEBUG mode")
                DEBUG
        }
    }
    
}
