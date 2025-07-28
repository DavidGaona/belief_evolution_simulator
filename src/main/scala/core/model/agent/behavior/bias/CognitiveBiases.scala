package core.model.agent.behavior.bias

import scala.reflect.ClassTag

/**
 * Represents different cognitive biases that can be applied to belief differences.
 * Each bias modifies how agents process and update their beliefs when exposed
 * to conflicting information.
 */
object CognitiveBiases {
    opaque type Bias = Byte
    
    given ClassTag[Bias] = ClassTag.Byte.asInstanceOf[ClassTag[Bias]]
    
    /**
     * DeGroot bias - no modification to belief difference.
     * Represents rational, unbiased belief updating.
     * Formula: f(x) = x
     */
    final val DEGROOT: Bias = 0x00
    
    /**
     * Confirmation bias - reduces belief change when it conflicts with existing beliefs.
     * Agents tend to accept information that confirms their existing beliefs more readily.
     * Formula: f(x) = x - (x * |x| * ε)
     */
    final val CONFIRMATION: Bias = 0x01
    
    /**
     * Backfire bias - reverses belief change when strongly contradicted.
     * Agents become more entrenched in their beliefs when presented with strong opposing evidence.
     * Formula: f(x) = -x³
     */
    final val BACKFIRE: Bias = 0x02
    
    /**
     * Authority bias - belief change depends only on direction, not magnitude.
     * Agents accept or reject information based on source authority rather than evidence strength.
     * Formula: f(x) = sign(x)
     */
    final val AUTHORITY: Bias = 0x03
    
    /**
     * Insular bias - complete rejection of external information.
     * Agents ignore all conflicting information and maintain their existing beliefs.
     * Formula: f(x) = 0
     */
    final val INSULAR: Bias = 0x04
    
    // Optimization constants for confirmation bias
    final private val EPSILON_PLUS_ONE: Float = 1f + 0.001f // call this e1
    final val INV_EPSILON_PLUS_ONE: Float = 1 / EPSILON_PLUS_ONE // call this e2
    /**
     * Original (x * (1f + 0.0001f - math.abs(x))) / (1f + 0.0001f)
     * (x * (e1 - math.abs(x))) / (e1)
     * x * (e1 - math.abs(x)) * e2
     * x * (e1 * e2 - math.abs(x) * e2)
     * x * (1 - math.abs(x) * e2)
     * x - (x * math.abs(x) * e2)
     */
    
    
    extension (bias: Bias) {
        /** Get the underlying byte value */
        def toByte: Byte = bias
        
        /**
         * Applies the specified cognitive bias to a belief difference.
         *
         * @param beliefDifference The raw belief difference value
         * @return The modified belief difference after applying the bias
         */
        def apply(beliefDifference: Float): Float = {
            bias match {
                case DEGROOT => beliefDifference
                case CONFIRMATION => beliefDifference - (beliefDifference * math.abs(beliefDifference) * INV_EPSILON_PLUS_ONE)
                case BACKFIRE => -beliefDifference * (beliefDifference * beliefDifference)
                case AUTHORITY => math.signum(beliefDifference)
                case INSULAR => 0
                case _ => throw new IllegalArgumentException(s"Unknown bias code: $bias")
            }
        }
        
        /** Converts a bias code to its name as a string representation. */
        def name: String = bias match {
            case DEGROOT => "DeGroot"
            case CONFIRMATION => "Confirmation"
            case BACKFIRE => "Backfire"
            case AUTHORITY => "Authority"
            case INSULAR => "Insular"
        }
    }
    
    /** Converts a byte to a <code>Bias</code>, only for comp time type checking */
    def fromByte(b: Byte): Bias = b
            
    /**
     * Converts a bias name string to its corresponding byte code.
     *
     * @param biasName The name of the bias (case-sensitive)
     * @return Some(bias code) if valid, None otherwise
     */
    def fromString(biasName: String): Option[Bias] = biasName match {
        case "DeGroot" => Some(DEGROOT)
        case "Confirmation" => Some(CONFIRMATION)
        case "Backfire" => Some(BACKFIRE)
        case "Authority" => Some(AUTHORITY)
        case "Insular" => Some(INSULAR)
        case _ => None
    }
    
    
}