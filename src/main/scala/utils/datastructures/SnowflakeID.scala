package utils.datastructures

import java.net.InetAddress
import java.util.concurrent.locks.ReentrantLock

/**
 * Thread-safe Snowflake ID generator that produces unique 64-bit identifiers.
 *
 * Snowflake IDs are composed of: <br>
 * - 41 bits: timestamp (milliseconds since custom epoch) <br>
 * - 10 bits: machine identifier (derived from hostname hash) <br>
 * - 12 bits: sequence number (for multiple IDs within same millisecond)
 *
 * This implementation uses January 1, 2024 as the custom epoch, providing
 * approximately 69 years of unique timestamp space. The machine ID is derived
 * from the hostname hash to ensure uniqueness across different machines.
 *
 * Generated IDs are roughly time-ordered and can be used as primary keys
 * in distributed systems without coordination between nodes.
 */
object SnowflakeID {
    private val lock = new ReentrantLock()
    private var lastTimeStamp = -1L
    private var sequence = 0
    
    /** Machine identifier derived from hostname hash, limited to 10 bits (0-1023) */
    private val machineId = InetAddress.getLocalHost.getHostName.hashCode
    private val choppedMachineId = (machineId & 0x3FF) << 12
    
    /** Custom epoch: January 1, 2024 00:00:00 UTC */
    private val customEpoch = 1704067200000L
    
    /**
     * Generates a unique 64-bit Snowflake ID.
     *
     * This method is thread-safe and handles clock backwards scenarios by throwing
     * an exception. If multiple IDs are requested within the same millisecond,
     * it uses a sequence counter. If the sequence overflows (>4095), it waits
     * for the next millisecond.
     *
     * @return A unique 64-bit identifier
     * @throws RuntimeException if system clock moves backwards
     */
    def generateId(): Long = {
        lock.lock()
        try {
            var timeStamp = System.currentTimeMillis() - customEpoch
            
            if (timeStamp < lastTimeStamp) {
                throw new RuntimeException("Clock moved backwards")
            }
            
            if (timeStamp == lastTimeStamp) {
                // Same millisecond increments sequence up to 4095
                sequence = (sequence + 1) & 0xFFF
                
                while (sequence == 0 && timeStamp <= lastTimeStamp) {
                    timeStamp = System.currentTimeMillis() - customEpoch
                }
            } else {
                sequence = 0
            }
            
            lastTimeStamp = timeStamp
            
            // Combine: 41-bit timestamp (excluding sign bit) + 10-bit machine + 12-bit sequence
            ((timeStamp << 22) | choppedMachineId | sequence) & 0x7FFFFFFFFFFFFFFFL
        } finally {
            lock.unlock()
        }
    }
}