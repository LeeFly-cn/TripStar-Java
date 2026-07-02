package com.zkry.config;

import com.zkry.api.trip.TripTaskWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class TripstarWebSocketConfig implements WebSocketConfigurer {

    private final TripTaskWebSocketHandler tripTaskWebSocketHandler;

    public TripstarWebSocketConfig(TripTaskWebSocketHandler tripTaskWebSocketHandler) {
        this.tripTaskWebSocketHandler = tripTaskWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(tripTaskWebSocketHandler, "/api/trip/ws/{taskId}")
            .setAllowedOriginPatterns("*");
    }
}
