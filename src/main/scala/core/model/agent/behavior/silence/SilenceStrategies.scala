package core.model.agent.behavior.silence

import scala.collection.mutable

/**
 * Represents different silence strategies that determine whether an agent speaks
 * based on the number of agents speaking in favor vs against their position.
 */
object SilenceStrategies {
    opaque type SilenceStrategy = Byte
    /**
     * DeGroot silence - agent always speaks regardless of others' opinions.
     * Represents completely open communication without social pressure.
     */
    final val DEGROOT: SilenceStrategy = 0x00
    
    /**
     * Majority silence - agent speaks only when the majority(50% or more) supports their position.
     * Agent remains silent when outnumbered by opposing voices.
     */
    final val MAJORITY: SilenceStrategy = 0x01
    
    /**
     * Threshold silence - agent speaks when support exceeds a fixed threshold.
     * Requires a threshold parameter (0.0 to 1.0) for activation.
     */
    final val THRESHOLD: SilenceStrategy = 0x02
    
    /**
     * Confidence silence - agent's willingness to speak depends on accumulated confidence.
     * Builds confidence over time based on opinion climate and has a configurable threshold.
     */
    final val CONFIDENCE: SilenceStrategy = 0x03
    
    extension (strategy: SilenceStrategy) {
        /**
         * Determines whether an agent should speak based on the silence strategy.
         *
         * @param agentIndex    The index of the agent in the SOA arrays
         * @param inFavor       Number of agents speaking in favor
         * @param against       Number of agents speaking against
         * @param thresholdMap  Map containing threshold values for agents that need them
         * @param confidenceMap Map containing (threshold, unbounded) tuples for confidence agents
         * @return 1 if agent should speak, 0 if silent
         */
        def shouldSpeak(
            agentIndex: Int,
            inFavor: Int,
            against: Int,
            thresholdMap: mutable.Map[Int, Float],
            confidenceMap: mutable.Map[Int, (Float, Float)]
        ): Byte = {
            strategy match {
                case DEGROOT => 1
                case MAJORITY => (1 - ((inFavor - against) >>> 31)).toByte
                case THRESHOLD =>
                    val threshold = thresholdMap(agentIndex)
                    val total = inFavor + against
                    (1 - (java.lang.Float.floatToRawIntBits(threshold * total - inFavor.toFloat) >>> 31)).toByte
                case CONFIDENCE =>
                    val (threshold, oldUnbounded) = confidenceMap(agentIndex)
                    val opinionClimate = (inFavor + against) match {
                        case 0 => 0.0f
                        case totalSpeaking => (inFavor - against).toFloat / totalSpeaking
                    }
                    val newUnbounded = math.max(oldUnbounded + opinionClimate, 0)
                    confidenceMap(agentIndex) = (threshold, newUnbounded)
                    val confidence = (2f / (1f + Math.exp(-newUnbounded).toFloat)) - 1f
                    (1 - (java.lang.Float.floatToRawIntBits(threshold - confidence) >>> 31)).toByte
                case _ => 1 // Default to DeGroot for unknown strategies
            }
        }
        
        /**
         * Converts the <code>Silence_Strategy</code> to its name as a string representation.
         */
        def name: String = strategy match {
            case DEGROOT => "DeGroot"
            case MAJORITY => "Majority"
            case THRESHOLD => "Threshold"
            case CONFIDENCE => "Confidence"
            case _ => throw new IllegalArgumentException(s"Unknown strategy code: $strategy")
        }
    }
    
    /** Converts a byte to a <code>SilenceStrategy</code>, only for comp time type checking */
    def fromByte(b: Byte): SilenceStrategy = b
    
    /**
     * Converts a strategy name string to its corresponding byte code.
     */
    def fromString(strategyName: String): SilenceStrategy = {
        val strategyNameLower = strategyName.toLowerCase
        
        strategyNameLower match {
            case "degroot" => DEGROOT
            case "majority" => MAJORITY
            case "threshold" => THRESHOLD
            case "confidence" => CONFIDENCE
            case _ => throw new IllegalArgumentException(s"Unknown silence strategy: $strategyName")
        }
    }
    
}
