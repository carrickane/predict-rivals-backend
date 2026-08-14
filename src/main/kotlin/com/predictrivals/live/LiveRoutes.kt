package com.predictrivals.live

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close

fun Route.liveRoutes(liveStateService: LiveStateService, liveHub: LiveHub) {
    get("/api/live") {
        call.respond(liveStateService.buildCurrentState())
    }

    webSocket("/ws/live") {
        liveHub.register(this)
        try {
            send(Frame.Text(kotlinx.serialization.json.Json.encodeToString(
                LiveStateResponse.serializer(),
                liveStateService.buildCurrentState(),
            )))
            for (frame in incoming) {
                // Read-only channel from the client's perspective; incoming frames (e.g. pings) are drained and ignored.
            }
        } finally {
            liveHub.unregister(this)
        }
    }
}
