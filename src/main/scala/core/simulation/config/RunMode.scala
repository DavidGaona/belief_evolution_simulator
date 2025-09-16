package core.simulation.config

/**
 * Represents the different mode a simulation run can be. Every simulation run
 * mode has a common core:
 * Iteration limit: Max number of iterations a simulation may run
 * Stop Threshold: Number after which beliefs are considered being close enough to
 * be classified as consensus, thus stopping the simulation.
 *
 */
object RunMode {
    /**
     * Represents a run that has its network generated with the network generating
     * algorithm, the user specifies how many agents of each type and how many
     * connections with their corresponding cognitive bias. Giving a good exploratory
     * option for users.
     */
    final val GENERATED: Byte = 0x00
    
    /**
     * Represents a run where the user manually specifies the network structure,
     * defining each agent's connections, initial beliefs, and cognitive biases 
     * individually for precise control over the simulation setup.
     */
    final val CUSTOM: Byte = 0x01
    
    /**
     * Represents a run that reuses an existing simulation configuration while allowing
     * modifications to specific parameters such as iteration limit, stop threshold,
     * agent types, or cognitive biases. This provides a way to explore variations
     * of previously successful or interesting simulation setups.
     */
    final val EXISTING: Byte = 0x02
    
    /**
     * Represents a run that is generated but with a custom network passed as a CSV file
     * the file must look like:
     * source, target
     */
    final val CSV: Byte = 0x10
}
    
    
// --add-modules=jdk.incubator.vector -Xmx32g 
// -Xmx32g --add-modules jdk.incubator.vector -XX:-UseCompressedOops 