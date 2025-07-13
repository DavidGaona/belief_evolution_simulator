package core.model.agent.behavior.silence

import scala.collection.mutable

/**
 * Represents different silence effects that determine how an agent's public belief
 * is presented based on whether they are currently speaking or silent.
 */
object SilenceEffect {
    /**
     * DeGroot effect - public belief always matches private belief.
     * No memory or modification of beliefs regardless of speaking status.
     */
    final val DEGROOT: Byte = 0x00
    
    /**
     * Memory effect - maintains last spoken belief when silent.
     * When speaking, updates public belief to current private belief.
     * When silent, retains the last belief that was publicly expressed.
     */
    final val MEMORY: Byte = 0x01
    
    /**
     * Memoryless effect - identical to DeGroot, public belief equals private belief.
     * Provided as separate type for semantic clarity in simulation configuration.
     */
    final val MEMORYLESS: Byte = 0x02
    
    /**
     * Determines the public belief value based on the silence effect.
     * Works directly with SOA storage for memory efficiency.
     *
     * @param effect        The silence effect code (0-2)
     * @param agentIndex    The index of the agent in the SOA arrays
     * @param privateBelief The agent's current private belief
     * @param isSpeaking    Whether the agent is currently speaking (1) or silent (0)
     * @param publicBelief  Map containing stored public beliefs for agents using MEMORY effect
     * @return The public belief value to be displayed/used
     */
    @inline def getPublicValue(
        effect: Byte,
        agentIndex: Int,
        privateBelief: Float,
        isSpeaking: Byte,
        publicBelief: mutable.Map[Int, Float] = null
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
     * Converts an effect name string to its corresponding byte code.
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
    
    /**
     * Converts an effect code to its string representation.
     *
     * @param effectCode The effect type code
     * @return The string name of the effect
     * @throws IllegalArgumentException if the effect code is unknown
     */
    def toString(effectCode: Byte): String = effectCode match {
        case DEGROOT => "DeGroot"
        case MEMORY => "Memory"
        case MEMORYLESS => "Memoryless"
        case _ => throw new IllegalArgumentException(s"Unknown effect code: $effectCode")
    }
}
        