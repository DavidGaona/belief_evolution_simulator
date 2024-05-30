import java.util.concurrent.TimeUnit
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.collection.mutable.Map

class CustomTimer {
    private var startTime: Option[Long] = None
    private var endTime: Option[Long] = None

    def start(): Unit = {
        startTime = Some(System.nanoTime())
    }

    def stop(message: String, timeUnit: TimeUnit = MILLISECONDS): Unit = {
        endTime = Some(System.nanoTime())
        val duration = endTime.get - startTime.getOrElse(throw new IllegalStateException("Timer was not started"))
        val durationInUnit = getTimeElapsed(duration, timeUnit)
        println(s"$message: $durationInUnit ${timeUnit.toString.toLowerCase}")
    }
    
    def getTimeElapsed(duration: Long, timeUnit: TimeUnit = MILLISECONDS): Long = {
        timeUnit match {
            case NANOSECONDS => duration
            case MICROSECONDS => duration / 1000
            case MILLISECONDS => duration / 1000000
            case SECONDS => duration / 1000000000
            case MINUTES => duration / 60000000000L
            case HOURS => duration / 3600000000000L
            case DAYS => duration / 86400000000000L
            case null => throw new UnsupportedOperationException("Unsupported time unit")
        }
    }
}

class CustomMultiTimer {
    private val times: mutable.Map[String, (Option[Long], Option[Long])] = mutable.Map()
    
    def start(key: String): Unit = {
        times.update(key, (Some(System.nanoTime()), None))
    }
    
    def stop(key: String, timeUnit: TimeUnit = TimeUnit.MILLISECONDS): Long = {
        val (startOpt, _) = times.getOrElse(key, throw new IllegalStateException("Timer was not started"))
        val endTime = System.nanoTime()
        times.update(key, (startOpt, Some(endTime)))
        
        val duration = endTime - startOpt.getOrElse(throw new IllegalStateException("Timer was not started"))
        val durationInUnit = getTimeElapsed(duration, timeUnit)
        println(s"$key: $durationInUnit ${timeUnit.toString.toLowerCase}")
        duration
    }
    
    private def getTimeElapsed(duration: Long, timeUnit: TimeUnit): Long = timeUnit match {
        case TimeUnit.NANOSECONDS => duration
        case TimeUnit.MICROSECONDS => duration / 1000
        case TimeUnit.MILLISECONDS => duration / 1000000
        case TimeUnit.SECONDS => duration / 1000000000
        case TimeUnit.MINUTES => duration / 60000000000L
        case TimeUnit.HOURS => duration / 3600000000000L
        case TimeUnit.DAYS => duration / 86400000000000L
        case null => throw new UnsupportedOperationException("Unsupported time unit")
    }
    
    
}