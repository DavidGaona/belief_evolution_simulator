package io.web

import java.time.Instant
import scala.collection.mutable

case class TopologySnapshot(
    agentCount: Int,
    edgeCount: Int,
    names: Array[String],           // null for generated runs
    initialBeliefs: Array[Float],
    tolRadius: Array[Float],
    tolOffset: Array[Float],
    silenceStrategies: Array[Byte],
    silenceEffects: Array[Byte],
    indexOffset: Array[Int],
    neighborsRefs: Array[Int],
    neighborsWeights: Array[Float],
    neighborBiases: Array[Byte]
)

case class ResultsSnapshot(
    finalRound: Int,
    consensus: Boolean,
    agentCount: Int,
    names: Array[String],
    finalBeliefs: Array[Float],
    publicBeliefs: Array[Float]
)

object SimulationCache {
    private case class Entry[A](data: A, storedAt: Instant)

    private val topologies      = mutable.Map[(Long, String), Entry[TopologySnapshot]]()
    private val finalTopologies  = mutable.Map[(Long, String), Entry[TopologySnapshot]]()
    private val results          = mutable.Map[(Long, String), Entry[ResultsSnapshot]]()
    private val networkIndex     = mutable.Map[Long, mutable.Set[String]]()

    def storeTopology(runId: Long, networkId: String, snap: TopologySnapshot): Unit =
        topologies.synchronized {
            topologies((runId, networkId)) = Entry(snap, Instant.now())
            networkIndex.getOrElseUpdate(runId, mutable.Set()).add(networkId)
        }

    def getTopology(runId: Long, networkId: String): Option[TopologySnapshot] =
        topologies.synchronized { topologies.get((runId, networkId)).map(_.data) }

    def storeResults(runId: Long, networkId: String, snap: ResultsSnapshot): Unit =
        results.synchronized { results((runId, networkId)) = Entry(snap, Instant.now()) }

    def getResults(runId: Long, networkId: String): Option[ResultsSnapshot] =
        results.synchronized { results.get((runId, networkId)).map(_.data) }

    def storeFinalTopology(runId: Long, networkId: String, snap: TopologySnapshot): Unit =
        finalTopologies.synchronized { finalTopologies((runId, networkId)) = Entry(snap, Instant.now()) }

    def getFinalTopology(runId: Long, networkId: String): Option[TopologySnapshot] =
        finalTopologies.synchronized { finalTopologies.get((runId, networkId)).map(_.data) }

    def getNetworkIds(runId: Long): Seq[String] =
        topologies.synchronized { networkIndex.get(runId).map(_.toSeq).getOrElse(Seq.empty) }

    def cleanup(): Unit = {
        val cutoff = Instant.now().minusSeconds(600)
        topologies.synchronized {
            val removed = topologies.filter((_, e) => !e.storedAt.isAfter(cutoff)).keySet
            topologies.filterInPlace((_, e) => e.storedAt.isAfter(cutoff))
            removed.foreach { case (runId, netId) => networkIndex.get(runId).foreach(_.remove(netId)) }
            networkIndex.filterInPlace((_, s) => s.nonEmpty)
        }
        finalTopologies.synchronized { finalTopologies.filterInPlace((_, e) => e.storedAt.isAfter(cutoff)) }
        results.synchronized { results.filterInPlace((_, e) => e.storedAt.isAfter(cutoff)) }
    }
}
