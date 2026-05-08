package io.web

import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import scala.collection.mutable

object TicketStore {
    private case class TicketEntry(runId: Long, uid: String, expiresAt: Instant, used: Boolean)
    private val store  = mutable.Map[String, TicketEntry]()
    private val rng    = new SecureRandom()

    def issue(runId: Long, uid: String): String = {
        val bytes = new Array[Byte](32)
        rng.nextBytes(bytes)
        val ticket = Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
        store.synchronized {
            store(ticket) = TicketEntry(runId, uid, Instant.now().plusSeconds(30), false)
        }
        ticket
    }

    def consume(ticket: String, runId: Long): Option[String] = store.synchronized {
        store.get(ticket) match {
            case Some(e) if !e.used && e.runId == runId && e.expiresAt.isAfter(Instant.now()) =>
                store(ticket) = e.copy(used = true)
                Some(e.uid)
            case _ => None
        }
    }

    def cleanup(): Unit = store.synchronized {
        val now = Instant.now()
        store.filterInPlace((_, e) => !e.used && e.expiresAt.isAfter(now))
    }
}
