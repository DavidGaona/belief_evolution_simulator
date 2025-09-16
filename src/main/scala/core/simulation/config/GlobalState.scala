package core.simulation.config

import utils.logging.Logger

/**
 * Contains all global variables that are needed throughout the program.
 */
object GlobalState {
    import AppMode.Mode
    
    /**
     * Application runtime mode constructed from individual environment variables.
     * This approach is more flexible and self-documenting than predefined mode strings.
     *
     * <h4>Environment variables:</h4>
     * <ul>
     * <li><code>APP_SKIP_DATABASE</code>: Skip all database operations (useful for testing)</li>
     * <li><code>APP_SERVER_MODE</code>: Running in server/production environment</li>
     * <li><code>APP_LOCAL_MODE</code>: Running in local development environment</li>
     * <li><code>APP_LEGACY_DB</code>: Use legacy database system</li>
     * <li><code>APP_SERVER_LOGS</code>: Enable server operation logging</li>
     * <li><code>APP_GENERAL_LOGS</code>: Enable general application logging</li>
     * <li><code>APP_SIMULATION_LOGS</code>: Enable detailed simulation logging</li>
     * </ul>
     *
     * <p><strong>Fallback:</strong> If <code>APP_MODE</code> is set, use string parsing for backwards compatibility</p>
     */
    final val APP_MODE: Mode = AppMode.fromEnvironment()
    
    Logger.log(s"Application starting in mode: ${APP_MODE.description} (0x${APP_MODE.toInt.toHexString.toUpperCase})")
}