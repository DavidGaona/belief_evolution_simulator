package core.model.agent.behavior.silence
import io.serialization.binary.Encoder

trait SilenceEffect {
    def getPublicValue(belief: Float, isSpeaking: Boolean): Float
    def encodeOptionalValues(encoder: Encoder): Unit
    def hasMemory: Int
}

class DeGrootSilenceEffect extends SilenceEffect {
    inline override def getPublicValue(belief: Float, isSpeaking: Boolean): Float = belief
    override def toString: String = "DeGroot"
    inline override def encodeOptionalValues(encoder: Encoder): Unit = {}
    final def hasMemory: Int = 1
}

class MemoryEffect extends SilenceEffect {
    var publicBelief: Float = 2f
    def initialize(initValue: Float): Unit = {
        publicBelief = initValue
    }
    
    override def getPublicValue(belief: Float, isSpeaking: Boolean): Float = {
        if (isSpeaking) publicBelief = belief
        publicBelief
    }
    
    override def toString: String = "Memory"
    @inline override def encodeOptionalValues(encoder: Encoder): Unit = {
        encoder.encodeFloat("publicBelief", publicBelief)
    }
    final def hasMemory: Int = 1
}

class MemorylessEffect extends SilenceEffect {
    final inline override def getPublicValue(belief: Float, isSpeaking: Boolean): Float = belief
    
    override def toString: String = "Memoryless"
    override def encodeOptionalValues(encoder: Encoder): Unit = {}
    final def hasMemory: Int = 0
}

class RecencyEffect(recencyFunction: (Float, Int) => Float) extends SilenceEffect {
    private var roundsSilent: Int = 0
    
    // ToDo implement recency effect
    inline override def getPublicValue(belief: Float, isSpeaking: Boolean): Float = belief
    
    override def toString: String = "Recency"
    override def encodeOptionalValues(encoder: Encoder): Unit = {}
    final def hasMemory: Int = 0
}

class PeersEffect(silenceEffect: SilenceEffect) extends SilenceEffect {
    private var neighborsAgreement: Array[Boolean] = new Array[Boolean](16)
    
    def updateNeighborAgreement(index: Int, value: Boolean): Unit = {
        neighborsAgreement(index) = value
    }
    
    // ToDo implement peers effect
    inline override def getPublicValue(belief: Float, isSpeaking: Boolean): Float = belief
    
    override def toString: String = "Peers"
    override def encodeOptionalValues(encoder: Encoder): Unit = {}
    final def hasMemory: Int = 0
}

enum SilenceEffectType:
    case DeGroot
    case Memory
    case Memoryless
    case Recency(recencyFunction: (Float, Int) => Float)
    case Peers(baseEffect: SilenceEffectType)


object SilenceEffectType{
    def fromString(string: String): SilenceEffectType = {
        val parts = string.toLowerCase.trim.split("\\(", 2)
        parts(0) match
            case "degroot" => SilenceEffectType.DeGroot
            case "memory" => SilenceEffectType.Memory
            case "memoryless" => SilenceEffectType.Memoryless
            case _ => SilenceEffectType.Memoryless
    }
    
    def fromByte(code: Byte): SilenceEffectType = {
        code match {
            case 0 => SilenceEffectType.DeGroot
            case 1 => SilenceEffectType.Memory
            case 2 => SilenceEffectType.Memoryless
            case _ => SilenceEffectType.Memoryless
        }
    }
}
    
object SilenceEffectFactory:
    def create(effectType: SilenceEffectType): SilenceEffect = effectType match
        case SilenceEffectType.DeGroot => DeGrootSilenceEffect()
        case SilenceEffectType.Memory => MemoryEffect()
        case SilenceEffectType.Memoryless => MemorylessEffect()
        case SilenceEffectType.Recency(recencyFunction) => RecencyEffect(recencyFunction)
        case SilenceEffectType.Peers(baseEffect) => PeersEffect(create(baseEffect))