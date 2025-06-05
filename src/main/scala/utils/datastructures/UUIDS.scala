package utils.datastructures

import benchmarking.OptimizedRdtsc
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import tech.ant8e.uuid4cats.UUIDv7
import utils.rng.Xoshiro

import java.util.UUID
import scala.util.Random

class UUIDS {
    val rand = Xoshiro(System.nanoTime() + Random(System.nanoTime()).nextLong())
    private object UUIDGenerator {
        private val generator = UUIDv7.generator[IO]
        
        def generateUUID(): IO[java.util.UUID] = for {
            gen <- generator
            uuid <- gen.uuid
        } yield uuid
    }
    
    def v7(): UUID = {
        val timestamp = System.currentTimeMillis
        val version = 0x7000
        val variant = 0b10L << 62
        val high = (timestamp << 12) | version | (rand.xoshiro256StarStar() & 0xFFF)
        val low = (rand.xoshiro256StarStar() >>> 2) | variant
        UUID(high, low)
    }
    
    def v7ToLongArray(uuidsArr: Array[Long]): Unit = {
        var i = 0
        var timestamp = System.currentTimeMillis
        val version = 0x7000
        val variant = 0b10L << 62
        while (i < uuidsArr.length) {
            uuidsArr(i) = (timestamp << 16) | version | (rand.xoshiro256StarStar() & 0xFFF) 
            uuidsArr(i + 1) = (rand.xoshiro256StarStar() >>> 2) | variant
            timestamp += 1
            i += 2
        }
    }
    
    def v7Bulk(uuidsArr: Array[UUID]): Unit = {
        var i = 0
        var timestamp = System.currentTimeMillis
        val version = 0x7000
        val variant = 0b10L << 62
        while (i < uuidsArr.length) {
            val high = (timestamp << 16) | version | (rand.xoshiro256StarStar() & 0xFFF) // 0x3FF
            val low = (rand.xoshiro256StarStar() >>> 2) | variant
            uuidsArr(i) = UUID(high, low)
            timestamp += 1
            i += 1
        }
    }
    
    def v7Bulk2(uuidsArr: Array[UUID]): Unit = {
        var i = 0
        var timestamp = System.currentTimeMillis
        val rand = Xoshiro(timestamp)
        val version = 0x7000
        val variant = 0b10L << 62
        rand.crazyTest(uuidsArr)
        while (i < uuidsArr.length) {
            val high = (timestamp << 12) | version | (uuidsArr(i).getMostSignificantBits & 0x3FF)
            val low = (uuidsArr(i).getLeastSignificantBits >>> 2) | variant
            uuidsArr(i) = UUID(high, low)
            timestamp += 1
            i += 1
            
        }
    }
    //            println(String.format("%64s", idk.toBinaryString).replace(' ', '0').grouped(8).mkString(" "))
    //            println(String.format("%64s", (idk & 0xFF).toBinaryString).replace(' ', '0').grouped(8).mkString(" "))
    //            println(String.format("%64s", uuidsArr(i).getMostSignificantBits.toBinaryString).replace(' ', '0').grouped(8).mkString(" "))
    //            println(String.format("%16s", uuidsArr(i).getMostSignificantBits.toHexString).replace(' ', '0'))
    
    def v7Native(uuidsArr: Array[UUID]): Unit = {
        var i = 0
        while (i < uuidsArr.length) {
            uuidsArr(i) = UUIDGenerator.generateUUID().unsafeRunSync()
            i += 1
        }
    }
}

object TesterUUID extends App {
    def estimateCPUTimerFreq(millisToWait: Long = 100L): Long = {
        val osFreq = 1_000_000_000L
        val cpuStart = OptimizedRdtsc.getRdtsc()
        val osStart = System.nanoTime()
        
        var osEnd = 0L
        var osElapsed = 0L
        val osWaitTime = osFreq * millisToWait / 1000
        while (osElapsed < osWaitTime) {
            osEnd = System.nanoTime()
            osElapsed = osEnd - osStart
        }
        val cpuEnd = OptimizedRdtsc.getRdtsc()
        val cpuElapsed = cpuEnd - cpuStart
        var cpuFreq = 0L
        if (osElapsed > 0) {
            cpuFreq = osFreq * cpuElapsed / osElapsed
        }
        
        cpuFreq
    }
    
    def formatNanos(nanos: Long): String = {
        if (nanos < 1000) {
            f"$nanos%.2f ns"
        } else if (nanos < 1000000) {
            f"${nanos / 1000.0}%.2f µs" // using µs for microseconds
        } else if (nanos < 1000000000) {
            f"${nanos / 1000000.0}%.2f ms"
        } else {
            f"${nanos / 1000000000.0}%.2f s"
        }
    }
    
    
    def test(iters: Int, arrSize: Int): Unit = {
        var max = Long.MinValue
        var min = Long.MaxValue
        val cpuFreq = estimateCPUTimerFreq()
        val randsArr: Array[UUID] = new Array[UUID](arrSize)
        val randsArr2: Array[UUID] = new Array[UUID](arrSize)
        val uuids = UUIDS()
        
        // Warmup
        val rand = Xoshiro(System.nanoTime())
        var i = 0
        while (i < arrSize) {
            rand.xoshiro256StarStar()
            i += 1
        }
        
        var cumSum = 0L
        
        i = 0
        while (i < iters) {
            val start = OptimizedRdtsc.getRdtsc()
            randsArr(i) = uuids.v7()
            val stop = OptimizedRdtsc.getRdtsc()
            
            val time = ((stop - start).toDouble / cpuFreq * 1_000_000_000).toLong
            max = math.max(max, time)
            min = math.min(min, time)
            cumSum += time
            i += 1
        }
        
        println(s"For custom uuid\n" +
                  s"Total Time: ${formatNanos(cumSum)}\n" +
                  s"Avg Time: ${formatNanos((cumSum.toDouble / iters).toLong)}\n" +
                  s"Max Time: ${formatNanos(max)}\n" +
                  s"Min Time: ${formatNanos(min)}\n")
        
        max = Long.MinValue
        min = Long.MaxValue
        cumSum = 0L
        
        i = 0
        while (i < iters) {
            val start = OptimizedRdtsc.getRdtsc()
            uuids.v7Native(randsArr2)
            val stop = OptimizedRdtsc.getRdtsc()
            
            val time = ((stop - start).toDouble / cpuFreq * 1_000_000_000).toLong
            max = math.max(max, time)
            min = math.min(min, time)
            cumSum += time
            
            i += 1
        }
        
        // Your print statement would become:
        println(s"For native uuid\n" +
                  s"Total Time: ${formatNanos(cumSum)}\n" +
                  s"Avg Time: ${formatNanos((cumSum.toDouble / iters).toLong)}\n" +
                  s"Max Time: ${formatNanos(max)}\n" +
                  s"Min Time: ${formatNanos(min)}\n")
    }
    
    val uuids = UUIDS()
    test(10, 16_384)
//    val arr = new Array[UUID](1024)
//    var i = 0
//    while (i < 1024) {
//        arr(i) = uuids.v7()
//        i += 1
//    }
//    
//    i = 0
//    while (i < 1024) {
//        val upper = arr(i).getMostSignificantBits
//        val lower = arr(i).getLeastSignificantBits
//        var j = i + 1
//        while (j < 1024) {
//            if (arr(j).getMostSignificantBits == upper && arr(j).getLeastSignificantBits == lower) {
//                println(s"REPEATED $i $j")
//                println(arr(i))
//                println(arr(j))
//            }
//            j += 1
//        }
//        i += 1
//    }
    
}