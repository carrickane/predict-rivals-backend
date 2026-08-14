package com.predictrivals.live

import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory broadcast hub for /ws/live — sufficient for a single Railway instance (see design
 * doc section 9); would need a shared pub/sub (e.g. Redis) if this ever scales beyond one.
 */
class LiveHub {
    private val logger = LoggerFactory.getLogger(LiveHub::class.java)
    private val sessions = ConcurrentHashMap.newKeySet<WebSocketServerSession>()
    private val sendMutex = Mutex()

    fun register(session: WebSocketServerSession) {
        sessions += session
    }

    fun unregister(session: WebSocketServerSession) {
        sessions -= session
    }

    suspend fun broadcast(state: LiveStateResponse) {
        val payload = Json.encodeToString(LiveStateResponse.serializer(), state)
        sendMutex.withLock {
            sessions.forEach { session ->
                runCatching { session.send(Frame.Text(payload)) }
                    .onFailure { logger.warn("Failed to send live update to a session, dropping it", it) }
            }
        }
    }
}
