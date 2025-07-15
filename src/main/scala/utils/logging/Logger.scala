package utils.logging

import core.simulation.config.{AppMode, GlobalState}

import java.time.LocalDateTime


/**
 * Logs a general message for debugging and development purposes.
 *
 * Output behavior by mode:
 * - DEBUG/DEBUG_SERVER: Outputs to stdout (console) without timestamp
 * - LOCAL: No output (silent)
 * - SERVER: No output (silent)
 *
 * Use this for verbose debugging information, trace messages, and general
 * development logging that should only appear during active debugging sessions.
 *
 * @param message The message to log
 */
def log(message: String): Unit = {
    GlobalState.APP_MODE match {
        case AppMode.DEBUG | AppMode.DEBUG_SERVER =>
            println(message)
        case _ =>
    }
}

/**
 * Logs a warning message indicating potential issues or unexpected conditions.
 *
 * Output behavior by mode:
 * - DEBUG/DEBUG_SERVER: Outputs to stdout with "WARNING:" prefix, no timestamp
 * - LOCAL: Outputs to stderr with "WARNING:" prefix and timestamp
 * - SERVER: Outputs to stderr with "WARNING:" prefix and timestamp
 *
 * Use this for non-critical issues that don't break functionality but should
 * be investigated, such as deprecated API usage, configuration inconsistencies,
 * or recoverable errors that might indicate deeper problems.
 *
 * @param message The warning message to log
 */
def logWarning(message: String): Unit = {
    GlobalState.APP_MODE match {
        case AppMode.DEBUG | AppMode.DEBUG_SERVER =>
            println(s"WARNING: $message")
        case AppMode.SERVER | AppMode.LOCAL =>
            Console.err.println(s"(${LocalDateTime.now()}) WARNING: $message")
    }
}

/**
 * Logs an error message for critical issues that require immediate attention.
 *
 * Output behavior by mode:
 * - DEBUG/DEBUG_SERVER: Outputs to stdout with "ERROR:" prefix, no timestamp
 * - LOCAL: Outputs to stderr with "ERROR:" prefix and timestamp
 * - SERVER: Outputs to stderr with "ERROR:" prefix and timestamp
 *
 * Use this for serious problems that prevent normal operation, such as
 * failed database connections, missing required files, invalid configurations,
 * unhandled exceptions, or any condition that compromises application reliability.
 * These messages should always be visible regardless of the deployment environment.
 *
 * @param message The error message to log
 */
def logError(message: String): Unit = {
    GlobalState.APP_MODE match {
        case AppMode.DEBUG | AppMode.DEBUG_SERVER =>
            println(s"ERROR: $message")
        case AppMode.SERVER | AppMode.LOCAL =>
            Console.err.println(s"(${LocalDateTime.now()}) ERROR: $message")
    }
}

object Logger {
    
}
