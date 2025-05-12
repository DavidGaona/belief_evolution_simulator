package core.model.agent.behavior.bias

import core.model.agent.behavior.bias.CognitiveBiasType.Authority

enum CognitiveBiasType:
    case DeGroot
    case Confirmation
    case Backfire
    case Authority
    case Insular
    
    def toFunction: BiasFunction = this match {
        case CognitiveBiasType.DeGroot => CognitiveBiases.DeGroot
        case CognitiveBiasType.Confirmation => CognitiveBiases.confirmation
        case CognitiveBiasType.Backfire => CognitiveBiases.backfire
        case CognitiveBiasType.Authority => CognitiveBiases.authority
        case CognitiveBiasType.Insular => CognitiveBiases.insular
    }
    
    def toBiasCode: Byte = this match {
        case CognitiveBiasType.DeGroot => 0
        case CognitiveBiasType.Confirmation => 1
        case CognitiveBiasType.Backfire => 2
        case CognitiveBiasType.Authority => 3
        case CognitiveBiasType.Insular => 4
    }

type BiasFunction = Float => Float

object CognitiveBiases {
    // Where x is the belief difference
    val DeGroot: BiasFunction = x => x
    
    // Confirmation constants
    // Original (x * (1f + 0.0001f - math.abs(x))) / (1f + 0.0001f)
    private val EPSILON_PLUS_ONE: Float = 1f + 0.001f // call this e1
    private val INV_EPSILON_PLUS_ONE: Float = 1 / EPSILON_PLUS_ONE // call this e2
    // (x * (e1 - math.abs(x))) / (e1)
    // x * (e1 - math.abs(x)) * e2
    // x * (e1 * e2 - math.abs(x) * e2)
    // x * (1 - math.abs(x) * e2)
    // x - (x * math.abs(x) * e2)
    val confirmation: BiasFunction = x => x - (x * math.abs(x) * INV_EPSILON_PLUS_ONE)
    
    val backfire: BiasFunction = x => -x * (x * x)
    
    // Authority
    // {
    //    case 0f => 0.0f
    //    case x => x / math.abs(x)
    // }
    val authority: BiasFunction = x => math.signum(x)
    
    val insular: BiasFunction = _ => 0f
    
    // bd = beliefDifference
    // 0 DeGroot
    // 1 Confirmation
    // 2 Backfire
    // 3 Authority
    // 4 Insular
    @inline def applyBias(code: Byte, bd: Float): Float = {
        code match {
            case 0 => bd
            case 1 => bd - (bd * math.abs(bd) * INV_EPSILON_PLUS_ONE)
            case 2 => -bd * (bd * bd)
            case 3 => math.signum(bd)
            case 4 => 0
        }
    }
    
    def applyBias(biasType: CognitiveBiasType, beliefDifference: Float): Float = {
        biasType match {
            case CognitiveBiasType.DeGroot => CognitiveBiases.DeGroot(beliefDifference)
            case CognitiveBiasType.Confirmation => CognitiveBiases.confirmation(beliefDifference)
            case CognitiveBiasType.Backfire => CognitiveBiases.backfire(beliefDifference)
            case CognitiveBiasType.Authority => CognitiveBiases.authority(beliefDifference)
            case CognitiveBiasType.Insular => CognitiveBiases.insular(beliefDifference)
        }
    }
    
    def toBiasType(f: BiasFunction): CognitiveBiasType = {
        if (f eq CognitiveBiases.DeGroot) CognitiveBiasType.DeGroot
        else if (f eq CognitiveBiases.confirmation) CognitiveBiasType.Confirmation
        else if (f eq CognitiveBiases.backfire) CognitiveBiasType.Backfire
        else if (f eq CognitiveBiases.authority) CognitiveBiasType.Authority
        else if (f eq CognitiveBiases.insular) CognitiveBiasType.Insular
        else throw new IllegalArgumentException("Unknown bias function")
    }
    
    val biasArr: Array[CognitiveBiasType] = Array(
        CognitiveBiasType.DeGroot,
        CognitiveBiasType.Confirmation,
        CognitiveBiasType.Backfire,
        CognitiveBiasType.Authority,
        CognitiveBiasType.Insular
        )
    
    def toBiasType(code: Byte): CognitiveBiasType = biasArr(code)
    
    def toBiasCode(biasType: CognitiveBiasType): Byte = biasType match {
        case CognitiveBiasType.DeGroot => 0
        case CognitiveBiasType.Confirmation => 1
        case CognitiveBiasType.Backfire => 2
        case CognitiveBiasType.Authority => 3
        case CognitiveBiasType.Insular => 4
    }
    
    def fromString(bias: String): Option[CognitiveBiasType] = bias match {
        case "DeGroot" => Some(CognitiveBiasType.DeGroot)
        case "Confirmation" => Some(CognitiveBiasType.Confirmation)
        case "Backfire" => Some(CognitiveBiasType.Backfire)
        case "Authority" => Some(CognitiveBiasType.Authority)
        case "Insular" => Some(CognitiveBiasType.Insular)
        case _ => None
    }
    
    
}