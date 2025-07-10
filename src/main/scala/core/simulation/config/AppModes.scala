package core.simulation.config

/**
 * Represents the different runtime modes the application can operate in.
 * Used to control logging behavior, server configuration, and other
 * environment-specific settings.
 */
object AppMode {
    /**
     * Debug mode - enables verbose logging, detailed error messages,
     * and debugging features. Typically used during development.
     * Slow performance due to high-impact logging.
     */
    final val DEBUG: Byte = 0x00
    
    /**
     * Server mode - production-ready configuration with minimal logging,
     * optimized for performance and security in deployed environments.
     * Great performance.
     */
    final val SERVER: Byte = 0x01
    
    /**
     * LOCAL mode - similar to debug mode, just that without all the logging,
     * and printing. Can be used to run locally to allow for changes in
     * memory limits.
     * Performance depends on the local machine, most customizable.
     */
    final val LOCAL: Byte = 0x02
    
    /**
     * Debug Server mode - On top of all the Debug verbose logging this mode
     * also has logs on each message sent by the server, useful when doing
     * changes to the server message structure. Typically used during development.
     * Slowest of all modes, recommended use on small to medium size simulations.
     */
    final val DEBUG_SERVER: Byte = (0x10 | DEBUG).toByte
}


