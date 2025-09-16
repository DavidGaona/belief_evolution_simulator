package core.simulation.config

/**
 * Represents the different runtime modes the application can operate in.
 * Uses a bitfield approach where different bits represent various flags.
 *
 * Bit Layout:<br>
 * Bit 0 (0x01): NO_DATABASE - Skip database operations (for debugging/testing)<br>
 * Bit 1 (0x02): SERVER - Running in server/production environment  <br>
 * Bit 2 (0x04): LOCAL - Running locally (mutually exclusive with SERVER)<br>
 * Bit 3 (0x08): LEGACY_DB - Use legacy database (compile-time decision)<br>
 * Bit 4 (0x10): SERVER_LOGS - Log server operations and messages<br>
 * Bit 5 (0x20): GENERAL_LOGS - Enable general application logging<br>
 * Bit 6 (0x40): SIMULATION_LOGS - Enable detailed simulation round-by-round logging<br>
 * Bits 7-31: Reserved for future flags
 */
object AppMode {
    opaque type Mode = Int
    
    // Base environment flags (bits 0-3)
    /**
     * No Database flag - skip all database operations entirely.
     * Useful for debugging, testing, or dry-runs where you don't want
     * to persist potentially faulty simulation data to the database.
     * 
     * Impacts: No data persistence, fastest performance for testing.
     */
    private final val NO_DATABASE_FLAG: Int = 0x01
    
    /**
     * Server flag - indicates production/server environment.
     * Optimized for performance and security in deployed environments.
     * 
     * Impacts: Production-level performance, assumes server infrastructure.
     * 
     * Mutually exclusive with LOCAL_FLAG.
     */
    private final val SERVER_FLAG: Int = 0x02
    
    /**
     * Local flag - indicates local development/testing environment.
     * Allows for memory limit changes and local-specific optimizations.
     * 
     * Impacts: Performance depends on local machine, most customizable.
     * 
     * Mutually exclusive with SERVER_FLAG.
     */
    private final val LOCAL_FLAG: Int = 0x04
    
    /**
     * Legacy database flag - use legacy database system that persists every simulation state.
     * This is a compile-time decision to prevent storage resource exhaustion
     * from inexperienced users in production environments.
     * 
     * Impacts: Slower performance and higher storage requirements, but comprehensive state management.
     */
    private final val LEGACY_DB_FLAG: Int = 0x08
    
    // Logging flags (bits 4-6 reserved 4-15)
    /**
     * Server logging flag - logs server-specific operations and messages.
     * Useful when making changes to server message structure or debugging
     * client-server communication issues.
     * 
     * Impacts: Additional I/O overhead from server logging.
     */
    private final val SERVER_LOGS: Int = 0x10
    
    /**
     * General logging flag - enables general application logging.
     * Includes startup messages, configuration info, errors, and warnings.
     * 
     * Impacts: Minimal performance overhead, useful for monitoring and debugging.
     */
    private final val GENERAL_LOGS: Int = 0x20
    
    /**
     * Simulation logging flag - enables detailed round-by-round simulation logging.
     * Logs simulation state changes, agent behaviors, and detailed execution flow.
     * 
     * Impacts: Significant performance overhead due to high-frequency logging.
     */
    private final val SIMULATION_LOGS: Int = 0x40
    
    /**
     * Skip Web Socket flag - disables the sending of simulation data to the web socket server.
     * Can be useful when measuring raw performance without the overhead of sending via web or
     * when there is no front end service to receive the msgs.
     * 
     * Impacts: Notable on huge simulation (4m+ agents), less significant in medium/small sizes. 
     */
    private final val SKIP_WS_FLAG: Int = 0x10000
    
    extension (mode: Mode) {
        /** Get the underlying int value */
        def toInt: Int = mode
        
        /** Check if database operations should be skipped (debug/testing mode) */
        def skipDatabase: Boolean = (mode & NO_DATABASE_FLAG) != 0
        
        /** Check if running in server/production environment */
        def isServer: Boolean = (mode & SERVER_FLAG) != 0
        
        /** Check if running in local development environment */
        def isLocal: Boolean = (mode & LOCAL_FLAG) != 0
        
        /** Check if using legacy database system */
        def usesLegacyDB: Boolean = (mode & LEGACY_DB_FLAG) != 0
        
        /** Check if server logging is enabled */
        def hasServerLogs: Boolean = (mode & SERVER_LOGS) != 0
        
        /** Check if general application logging is enabled */
        def hasGeneralLogs: Boolean = (mode & GENERAL_LOGS) != 0
        
        /** Check if detailed simulation logging is enabled */
        def hasSimulationLogs: Boolean = (mode & SIMULATION_LOGS) != 0
        
        /** Check if any form of logging is enabled */
        def hasAnyLogging: Boolean = hasGeneralLogs || hasSimulationLogs || hasServerLogs
        
        /** Check if web socket data should be skipped */
        def skipWS: Boolean = (mode & SKIP_WS_FLAG) != 0
        
        /** Get a human-readable description of the mode */
        def description: String = {
            val flags = List(
                if (skipDatabase) Some("No-DB") else None,
                if (isServer) Some("Server") else None,
                if (isLocal) Some("Local") else None,
                if (usesLegacyDB) Some("Legacy-DB") else None,
                if (hasServerLogs) Some("Server-Logs") else None,
                if (hasGeneralLogs) Some("General-Logs") else None,
                if (hasSimulationLogs) Some("Simulation-Logs") else None,
                if (skipWS) Some("No-WS") else None
            ).flatten
            
            if (flags.nonEmpty) flags.mkString(" + ") else "Unknown"
        }
    }
    
    // Utility methods for mode creation and validation
    /**
     * Create a custom mode from individual flags.
     * Validates that SERVER and LOCAL are mutually exclusive.
     */
    def createMode(skipDatabase: Boolean = false,
        server: Boolean = false,
        local: Boolean = false,
        legacyDB: Boolean = false,
        serverLogs: Boolean = false,
        generalLogs: Boolean = false,
        simulationLogs: Boolean = false,
        skipWS: Boolean = false): Mode = {
        require(!(server && local), "SERVER and LOCAL modes are mutually exclusive")
        require(!skipDatabase || !legacyDB, "Cannot use legacy database when skipping database entirely")
        
        var mode: Int = 0
        if (skipDatabase) mode = (mode | NO_DATABASE_FLAG)
        if (server) mode = (mode | SERVER_FLAG)
        if (local) mode = (mode | LOCAL_FLAG)
        if (legacyDB) mode = (mode | LEGACY_DB_FLAG)
        if (serverLogs) mode = (mode | SERVER_LOGS)
        if (generalLogs) mode = (mode | GENERAL_LOGS)
        if (simulationLogs) mode = (mode | SIMULATION_LOGS)
        if (skipWS) mode = (mode | SKIP_WS_FLAG)
        
        mode
    }
    
    /**
     * Parse mode from environment variables using individual flag settings.
     */
    def fromEnvironment(): Mode = {
        createMode(
            skipDatabase = sys.env.get("APP_SKIP_DATABASE").exists(_.toLowerCase == "true"),
            server = sys.env.get("APP_SERVER_MODE").exists(_.toLowerCase == "true"),
            local = sys.env.get("APP_LOCAL_MODE").exists(_.toLowerCase == "true"),
            legacyDB = sys.env.get("APP_LEGACY_DB").exists(_.toLowerCase == "true"),
            serverLogs = sys.env.get("APP_SERVER_LOGS").exists(_.toLowerCase == "true"),
            generalLogs = sys.env.get("APP_GENERAL_LOGS").exists(_.toLowerCase == "true"),
            simulationLogs = sys.env.get("APP_SIMULATION_LOGS").exists(_.toLowerCase == "true"),
            skipWS = sys.env.get("APP_SKIP_WS").exists(_.toLowerCase == "true")
        )
    }
    
}