package core.model.agent.behavior.silence

/**
 * Represents different silence effects that determine how an agent's public belief
 * is presented based on whether they are currently speaking or silent.
 */
object SilenceEffects {
    opaque type SilenceEffect = Byte
    /**
     * DeGroot effect - public belief always matches private belief.
     * No memory or modification of beliefs regardless of speaking status.
     */
    final val DEGROOT: SilenceEffect = 0x00
    
    /**
     * Memory effect - maintains last spoken belief when silent.
     * When speaking, updates public belief to current private belief.
     * When silent, retains the last belief that was publicly expressed.
     */
    final val MEMORY: SilenceEffect = 0x01
    
    /**
     * Memoryless effect - identical to DeGroot, public belief equals private belief.
     * Provided as separate type for semantic clarity in simulation configuration.
     */
    final val MEMORYLESS: SilenceEffect = 0x02
    
    extension (effect: SilenceEffect) {
        /**
         * Determines the public belief value based on the <code>SilenceEffect</code>.
         * Works directly with SOA storage for memory efficiency.
         *
         * @param agentIndex    The index of the agent in the SOA
         * @param privateBelief The agent's current private belief
         * @param isSpeaking    Whether the agent is currently speaking (1) or silent (0)
         * @param publicBelief  Array containing stored public beliefs for agents using MEMORY effect
         * @return The public belief value to be displayed/used
         */
        def getPublicValue(
            agentIndex: Int,
            privateBelief: Float,
            isSpeaking: Byte,
            publicBelief: Array[Float] = null
        ): Float = {
            effect match {
                case DEGROOT => privateBelief
                
                case MEMORY =>
                    if (isSpeaking == 1) publicBelief(agentIndex) = privateBelief
                    publicBelief(agentIndex)
                    
                case MEMORYLESS => privateBelief
                
                case _ => privateBelief // Default to DeGroot for unknown effects
            }
        }
        
        /**
         * Converts the <code>SilenceEffect</code> to its string representation.
         *
         * @return The string name of the effect
         * @throws IllegalArgumentException if the effect code is unknown
         */
        def name: String = effect match {
            case DEGROOT => "DeGroot"
            case MEMORY => "Memory"
            case MEMORYLESS => "Memoryless"
            case _ => throw new IllegalArgumentException(s"Unknown effect code: $effect")
        }
    }
    
    /** Converts a byte to a <code>SilenceEffect</code>, only for comp time type checking */
    def fromByte(b: Byte): SilenceEffect = b
    
    /**
     * Converts an effect name string to its corresponding <code>SilenceEffect</code>
     *
     * @param effectName The name of the effect (case-insensitive)
     * @return The effect byte code
     * @throws IllegalArgumentException if the effect name is unknown
     */
    def fromString(effectName: String): Byte = {
        val effectNameLower = effectName.toLowerCase.trim
        
        effectNameLower match {
            case "degroot" => DEGROOT
            case "memory" => MEMORY
            case "memoryless" => MEMORYLESS
            case _ => throw new IllegalArgumentException(s"Unknown silence effect: $effectName")
        }
    }
    
}
        