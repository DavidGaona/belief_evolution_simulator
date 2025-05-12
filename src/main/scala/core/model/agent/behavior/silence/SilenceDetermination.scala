package core.model.agent.behavior.silence

import io.serialization.binary.Encoder

trait SilenceStrategy {
    def determineSilence(inFavor: Int, against: Int): Byte
    @inline def encodeOptionalValues(encoder: Encoder): Unit
}

class DeGrootSilenceStrategy extends SilenceStrategy {
    inline override def determineSilence(inFavor: Int, against: Int): Byte = 1
    override def toString: String = "DeGroot"
    override def encodeOptionalValues(encoder: Encoder): Unit = {}
}

class MajoritySilence extends SilenceStrategy {
    inline override def determineSilence(inFavor: Int, against: Int): Byte = {
        //println(s"F: $inFavor, A: $against, Res:${(1 - ((inFavor - against) >>> 31)).toByte}")
        (1 - ((inFavor - against) >>> 31)).toByte
    }
    override def toString: String = "Majority"
    override def encodeOptionalValues(encoder: Encoder): Unit = {}
}

class ThresholdSilence(threshold: Float) extends SilenceStrategy {
    inline override def determineSilence(inFavor: Int, against: Int): Byte = {
        (1 - (java.lang.Float.floatToRawIntBits(threshold * (inFavor + against) - inFavor.toFloat) >>> 31)).toByte
    }
    override def toString: String = "Threshold"
    override def encodeOptionalValues(encoder: Encoder): Unit = {}
}

class ConfidenceSilence(threshold: Float = 0.5f, openMindedness: Int = 1) extends SilenceStrategy {
    private var confidenceUnbounded: Float = -1f
    private var opinionClimate: Float = -1f
    
    //ToDo optimize confidence calculation
    override def determineSilence(inFavor: Int, against: Int): Byte = {
        opinionClimate = inFavor + against match {
            case 0 => 0.0f
            case totalSpeaking => (inFavor - against).toFloat / totalSpeaking
        }
        confidenceUnbounded = math.max(confidenceUnbounded + opinionClimate, 0)
        val confidence = (2f / (1f + Math.exp(-confidenceUnbounded).toFloat)) - 1f
        (1 - (java.lang.Float.floatToRawIntBits(threshold - confidence) >>> 31)).toByte
    }
    
    def getConfidence: Float = (2f / (1f + Math.exp(-confidenceUnbounded).toFloat)) - 1f
    override def toString: String = "Confidence"
    @inline override def encodeOptionalValues(encoder: Encoder): Unit = {
        encoder.encodeFloat("confidence", (2f / (1f + Math.exp(-confidenceUnbounded).toFloat)) - 1f)
        encoder.encodeFloat("opinionClimate", opinionClimate)
    }
}

enum SilenceStrategyType:
    case DeGroot
    case Majority
    case Threshold(threshold: Float)
    case Confidence(threshold: Float, openMindedness: Int)


object SilenceStrategyType {
    def fromString(string: String): SilenceStrategyType = {
        val parts = string.toLowerCase.trim.split("\\(", 2)
        
        parts(0) match
            case "degroot" => DeGroot
            case "majority" => Majority
            case "threshold" =>
                if parts.length > 1 then
                    Threshold(parts(1).stripSuffix(")").toFloat)
                else
                    Threshold(0.5f) // Default value
            case "confidence" =>
                if parts.length > 1 then
                    val params = parts(1).stripSuffix(")").split(",", 2)
                    if params.length == 2 then
                        Confidence(params(0).toFloat, params(1).toInt)
                    else
                        Confidence(params(0).toFloat, 1) // Default openMindedness
                else
                    Confidence(0.5f, 1) // Default values
            case _ => Majority
    }
    
    def fromByte(code: Byte, thresholdValue: Float = 0.5f, confidenceValue: Float = 0.5f, updateValue: Int = 1): SilenceStrategyType = {
        code match {
            case 0 => DeGroot
            case 1 => Majority
            case 2 => Threshold(thresholdValue)
            case 3 => Confidence(confidenceValue, updateValue)
            case _ => Majority
        }
    }
}
    

object SilenceStrategyFactory:
    def create(strategyType: SilenceStrategyType): SilenceStrategy = strategyType match
        case SilenceStrategyType.DeGroot => DeGrootSilenceStrategy()
        case SilenceStrategyType.Majority => MajoritySilence()
        case SilenceStrategyType.Threshold(threshold) => ThresholdSilence(threshold)
        case SilenceStrategyType.Confidence(threshold, openMindedness) =>
            ConfidenceSilence(threshold, openMindedness)