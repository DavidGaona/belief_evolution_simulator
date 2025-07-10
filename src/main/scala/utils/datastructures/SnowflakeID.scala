package utils.datastructures

import java.net.InetAddress
import java.util.concurrent.locks.ReentrantLock

class SnowflakeID {
    private val lock = new ReentrantLock()
    private var lastTimeStamp = -1L
    private var sequence = 0
    private val machineId = InetAddress.getLocalHost.getHostName.hashCode
    private val choppedMachineId = (machineId & 0x3FF) << 12
    private val customEpoch = 1704067200000L 
    
    def generateId(): Long = {
        var timeStamp = System.currentTimeMillis() - customEpoch
        
        if (timeStamp == lastTimeStamp) {
            sequence = (sequence + 1) & 0xFFF
            while (timeStamp <= lastTimeStamp) {
                timeStamp = System.currentTimeMillis() - customEpoch
            }
        } else {
            sequence = 0
        }
        lastTimeStamp = timeStamp
        (timeStamp << 22) | choppedMachineId | sequence
    }
    
    
}
