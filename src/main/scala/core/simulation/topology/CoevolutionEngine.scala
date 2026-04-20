package core.simulation.topology

import core.model.agent.behavior.bias.CognitiveBiases
import core.model.agent.behavior.bias.CognitiveBiases.Bias
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

object CoevolutionEngine {

    def evolveTopology(
        numAgents: Int,
        indexOffset: Array[Int],
        neighborsRefs: Array[Int],
        neighborsWeights: Array[Float],
        neighborBiases: Array[Bias],
        beliefs: Array[Float],
        publicBeliefs: Array[Float],
        speaking: Array[Byte],
        hasMemory: Array[Byte],
        tolRadius: Array[Float],
        pBreak: Float,
        pCreate: Float,
        rewiringStrategy: Int,
        random: Random
    ): (Array[Int], Array[Int], Array[Float], Array[Bias]) = {

        val estimatedNewEdges = neighborsRefs.length
        val newIndexOffset = new Array[Int](numAgents)
        val newNeighborsRefs = new ArrayBuffer[Int](estimatedNewEdges)
        val newNeighborsWeights = new ArrayBuffer[Float](estimatedNewEdges)
        val newNeighborBiases = new ArrayBuffer[Bias](estimatedNewEdges)

        // Pre-allocate tracking structures to avoid GC overhead inside the loop
        val isNeighbor = new java.util.BitSet(numAgents)
        val keptNeighbors = new ArrayBuffer[(Int, Float, Bias)]()
        val newCandidates = new ArrayBuffer[Int]()

        var i = 0
        while (i < numAgents) {
            val startIdx = if (i == 0) 0 else indexOffset(i - 1)
            val endIdx = indexOffset(i)

            var keptWeightSum = 0f
            var lostWeightSum = 0f

            isNeighbor.clear()
            keptNeighbors.clear()
            newCandidates.clear()

            // --- PHASE 1: FRACTURE (Rupture) ---
            var j = startIdx
            while (j < endIdx) {
                val neighborId = neighborsRefs(j)
                val weight = neighborsWeights(j)
                val bias = neighborBiases(j)

                isNeighbor.set(neighborId)

                val isSpeaking = speaking(neighborId) == 1
                val isSOMPlus = hasMemory(neighborId) == 1
                var edgeBroken = false

                if (isSpeaking || isSOMPlus) {
                    val perceivedBelief = if (isSpeaking) beliefs(neighborId) else publicBeliefs(neighborId)
                    val distance = math.abs(perceivedBelief - beliefs(i))
                    val tau = tolRadius(i)

                    if (distance > tau) {
                        val g = 1f - math.exp(1f - (distance / tau)).toFloat
                        if (random.nextFloat() < (pBreak * g)) {
                            edgeBroken = true
                        }
                    }
                }

                if (edgeBroken) {
                    lostWeightSum += weight
                } else {
                    keptWeightSum += weight
                    keptNeighbors.addOne((neighborId, weight, bias))
                }
                j += 1
            }

            // --- PHASE 2: BOUNDED CREATION ---
            var k = 0
            while (k < numAgents) {
                if (k != i && !isNeighbor.get(k)) {
                    val distance = math.abs(beliefs(k) - beliefs(i))
                    if (distance <= tolRadius(i)) {
                        if (random.nextFloat() < pCreate) {
                            newCandidates.addOne(k)
                        }
                    }
                }
                k += 1
            }

            // --- PHASE 3: REWIRING & INFLUENCE CONSERVATION ---
            var m = 0
            val startOfNewAgentIdx = newNeighborsRefs.length
            while (m < keptNeighbors.length) {
                newNeighborsRefs.addOne(keptNeighbors(m)._1)
                newNeighborsWeights.addOne(keptNeighbors(m)._2)
                newNeighborBiases.addOne(keptNeighbors(m)._3)
                m += 1
            }

            if (lostWeightSum > 0f) {
                if (newCandidates.isEmpty) {
                    // Fallback: Isolation — redirect lost weight to self-loop
                    var foundSelf = false
                    var idx = startOfNewAgentIdx
                    while (idx < newNeighborsRefs.length) {
                        if (newNeighborsRefs(idx) == i) {
                            newNeighborsWeights(idx) += lostWeightSum
                            foundSelf = true
                        }
                        idx += 1
                    }
                    if (!foundSelf) {
                        newNeighborsRefs.addOne(i)
                        newNeighborsWeights.addOne(lostWeightSum)
                        newNeighborBiases.addOne(CognitiveBiases.DEGROOT)
                    }
                } else {
                    if (rewiringStrategy == 0) {
                        // Proposal A: Uniform redistribution
                        val weightPerNew = lostWeightSum / newCandidates.length
                        var c = 0
                        while (c < newCandidates.length) {
                            newNeighborsRefs.addOne(newCandidates(c))
                            newNeighborsWeights.addOne(weightPerNew)
                            newNeighborBiases.addOne(CognitiveBiases.DEGROOT)
                            c += 1
                        }
                    } else {
                        // Proposal B: Homophily-weighted redistribution
                        var similaritySum = 0f
                        val similarities = new Array[Float](newCandidates.length)
                        var c = 0
                        while (c < newCandidates.length) {
                            val sim = 1f - math.abs(beliefs(newCandidates(c)) - beliefs(i))
                            similarities(c) = sim
                            similaritySum += sim
                            c += 1
                        }

                        c = 0
                        while (c < newCandidates.length) {
                            val weight = if (similaritySum > 0) lostWeightSum * (similarities(c) / similaritySum) else lostWeightSum / newCandidates.length
                            newNeighborsRefs.addOne(newCandidates(c))
                            newNeighborsWeights.addOne(weight)
                            newNeighborBiases.addOne(CognitiveBiases.DEGROOT)
                            c += 1
                        }
                    }
                }
            }
            newIndexOffset(i) = newNeighborsRefs.length
            i += 1
        }
        (newIndexOffset, newNeighborsRefs.toArray, newNeighborsWeights.toArray, newNeighborBiases.toArray)
    }
}
