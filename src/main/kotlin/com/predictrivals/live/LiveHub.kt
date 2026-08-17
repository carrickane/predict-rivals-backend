package com.predictrivals.live

import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory broadcast hub for /ws/tournaments/{id}/live, keyed per tournament — sufficient for a
 * single Railway instance (see design doc section 9); would need a shared pub/sub (e.g. Redis) if
 * this ever scales beyond one.
 */
class LiveHub {
    private val logger = LoggerFactory.getLogger(LiveHub::class.java)
    private val sessionsByTournament = ConcurrentHashMap<Long, MutableSet<WebSocketServerSession>>()
    private val sendMutex = Mutex()

    fun register(tournamentId: Long, session: WebSocketServerSession) {
        sessionsByTournament.computeIfAbsent(tournamentId) { ConcurrentHashMap.newKeySet() } += session
    }

    fun unregister(tournamentId: Long, session: WebSocketServerSession) {
        sessionsByTournament[tournamentId]?.remove(session)
    }

    suspend fun broadcast(tournamentId: Long, state: LiveStateResponse) {
        val sessions = sessionsByTournament[tournamentId] ?: return
        val payload = Json.encodeToString(LiveStateResponse.serializer(), state)
        sendMutex.withLock {
            sessions.forEach { session ->
                runCatching { session.send(Frame.Text(payload)) }
                    .onFailure { logger.warn("Failed to send live update to a session, dropping it", it) }
            }
        }
    }
}
