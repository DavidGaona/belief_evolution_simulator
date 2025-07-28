package utils.logging

import core.simulation.config.GlobalState

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * High-performance logger that adapts behavior based on individual AppMode flags.
 * Provides different logging levels with configurable output destinations and formatting.
 */
object Logger {
    private val shouldLogGeneral = GlobalState.APP_MODE.hasGeneralLogs
    private val shouldLogSimulation = GlobalState.APP_MODE.hasSimulationLogs
    private val shouldLogServer = GlobalState.APP_MODE.hasServerLogs
    private val isServerMode = GlobalState.APP_MODE.isServer
    private val isLocalMode = GlobalState.APP_MODE.isLocal
    private val skipDatabase = GlobalState.APP_MODE.skipDatabase
    
    private val useStdout = isLocalMode || skipDatabase  // Local dev or debug modes use stdout
    private val useStderr = isServerMode || isLocalMode  // Server and local use stderr for warnings/errors
    private val includeTimestamp = isServerMode || isLocalMode  // Production modes include timestamps
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    
    /**
     * Logs a general application message (startup, config, status updates).
     *
     * Behavior:<br>
     * - Only logs if GENERAL_LOGS flag is enabled<br>
     * - Local/Debug: stdout, no timestamp<br>
     * - Server: stderr, with timestamp
     *
     * Use for: Application lifecycle events, configuration info, status updates
     *
     * @param message The message to log
     */
    def log(message: String): Unit = {
        if (!shouldLogGeneral) return
        
        if (includeTimestamp) {
            val timestamp = LocalDateTime.now().format(timestampFormatter)
            if (useStderr) {
                Console.err.println(s"[$timestamp] $message")
            } else {
                println(s"[$timestamp] $message")
            }
        } else {
            println(message)
        }
    }
    
    /**
     * Logs detailed simulation events (round-by-round, agent actions, state changes).
     *
     * Behavior:<br>
     * - Only logs if SIMULATION_LOGS flag is enabled<br>
     * - Always to stdout (for easy redirection/filtering)<br>
     * - No timestamp (high frequency, performance critical)
     *
     * Use for: Agent behaviors, simulation state, round details, performance metrics
     *
     * @param message The simulation message to log
     */
    def logSimulation(message: String): Unit = {
        if (!shouldLogSimulation) return
        println(s"SIM: $message")
    }
    
    /**
     * Logs server operations (messages, client connections, API calls).
     *
     * Behavior:<br>
     * - Only logs if SERVER_LOGS flag is enabled<br>
     * - Local: stdout, no timestamp<br>
     * - Server: stderr, with timestamp
     *
     * Use for: HTTP requests, message passing, client communication, server debugging
     *
     * @param message The server message to log
     */
    def logServer(message: String): Unit = {
        if (!shouldLogServer) return
        
        val formattedMessage = s"SERVER: $message"
        
        if (includeTimestamp) {
            val timestamp = LocalDateTime.now().format(timestampFormatter)
            if (useStderr) {
                Console.err.println(s"[$timestamp] $formattedMessage")
            } else {
                println(s"[$timestamp] $formattedMessage")
            }
        } else {
            println(formattedMessage)
        }
    }
    
    /**
     * Logs warning messages for non-critical issues.
     *
     * Behavior:<br>
     * - Always logs (warnings should always be visible)<br>
     * - Local/Debug: stdout with WARNING prefix<br>
     * - Server: stderr with timestamp and WARNING prefix
     *
     * Use for: Deprecated usage, config inconsistencies, recoverable errors
     *
     * @param message The warning message to log
     */
    def logWarning(message: String): Unit = {
        val formattedMessage = s"WARNING: $message"
        
        if (includeTimestamp) {
            val timestamp = LocalDateTime.now().format(timestampFormatter)
            if (useStderr) {
                Console.err.println(s"[$timestamp] $formattedMessage")
            } else {
                println(s"[$timestamp] $formattedMessage")
            }
        } else {
            println(formattedMessage)
        }
    }
    
    /**
     * Logs error messages for critical issues.
     *
     * Behavior:<br>
     * - Always logs (errors should always be visible)<br>
     * - Local/Debug: stdout with ERROR prefix<br>
     * - Server: stderr with timestamp and ERROR prefix
     *
     * Use for: Failed connections, missing files, unhandled exceptions, critical failures
     *
     * @param message The error message to log
     */
    def logError(message: String): Unit = {
        val formattedMessage = s"ERROR: $message"
        
        if (includeTimestamp) {
            val timestamp = LocalDateTime.now().format(timestampFormatter)
            if (useStderr) {
                Console.err.println(s"[$timestamp] $formattedMessage")
            } else {
                println(s"[$timestamp] $formattedMessage")
            }
        } else {
            println(formattedMessage)
        }
    }
    
    /**
     * Logs an error with exception details.
     *
     * @param message The error message
     * @param throwable The exception that occurred
     */
    def logError(message: String, throwable: Throwable): Unit = {
        logError(s"$message: ${throwable.getMessage}")
        if (shouldLogGeneral) {
            throwable.printStackTrace()
        }
    }
    
    /**
     * Performance-optimized conditional logging for hot paths.
     * Only evaluates the message if logging would actually occur.
     *
     * @param condition The logging condition to check
     * @param messageFunc Function that produces the message (only called if needed)
     */
    def logIf(condition: Boolean)(messageFunc: => String): Unit = {
        if (condition) {
            println(messageFunc)
        }
    }
    
    // Convenience methods for conditional logging
    def logGeneralIf(messageFunc: => String): Unit = logIf(shouldLogGeneral)(messageFunc)
    def logSimulationIf(messageFunc: => String): Unit = logIf(shouldLogSimulation)(messageFunc)
    def logServerIf(messageFunc: => String): Unit = logIf(shouldLogServer)(messageFunc)
}
