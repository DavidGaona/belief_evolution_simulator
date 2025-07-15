package utils.timers

import utils.logging.log

import java.util.concurrent.TimeUnit
import scala.collection.mutable
import scala.collection.mutable.Map
import scala.concurrent.duration.*

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
        log(s"$message: $durationInUnit ${timeUnit.toString.toLowerCase}")
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
    
    def stop(key: String, timeUnit: TimeUnit = TimeUnit.MILLISECONDS, msg: String = "", printDuration: Boolean = true): Long = {
        val (startOpt, _) = times.getOrElse(key, throw new IllegalStateException("Timer was not started"))
        val endTime = System.nanoTime()
        times.update(key, (startOpt, Some(endTime)))
        
        val duration = endTime - startOpt.getOrElse(throw new IllegalStateException("Timer was not started"))
        val formattedDuration = formatDuration(duration, timeUnit)
        if (printDuration) log(s"$key$msg: $formattedDuration")
        duration
    }
    
    def formatDuration(duration: Long, timeUnit: TimeUnit = MILLISECONDS): String = {
        val convertedDuration = getTimeElapsed(duration, timeUnit)
        // Convert everything to milliseconds first for easier calculation
        val millis = timeUnit match {
            case TimeUnit.NANOSECONDS => convertedDuration / 1_000_000
            case TimeUnit.MICROSECONDS => convertedDuration / 1_000
            case TimeUnit.MILLISECONDS => convertedDuration
            case TimeUnit.SECONDS => convertedDuration * 1_000
            case TimeUnit.MINUTES => convertedDuration * 60 * 1_000
            case TimeUnit.HOURS => convertedDuration * 60 * 60 * 1_000
            case _ => convertedDuration
        }
        
        if (millis < 1) {
            val micros = millis * 1000
            if (micros < 1) {
                val nanos = micros * 1000
                f"${nanos.toDouble}%.1f ns"
            } else {
                f"${micros.toDouble}%.1f µs"
            }
        } else if (millis < 1000) {
            f"${millis.toDouble}%.1f ms"
        } else if (millis < 60000) { // less than 1 minute
            val seconds = millis / 1000.0
            f"$seconds%.1f s"
        } else if (millis < 3600000) { // less than 1 hour
            val totalSeconds = millis / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            f"$minutes%d:${seconds}%02d"
        } else {
            val totalMinutes = millis / (1000 * 60)
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            f"$hours%d:${minutes}%02d"
        }
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