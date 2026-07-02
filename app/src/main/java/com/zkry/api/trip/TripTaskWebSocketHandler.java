package com.zkry.api.trip;

import com.zkry.common.json.utils.JsonUtils;
import com.zkry.trip.dto.TripTaskEvent;
import com.zkry.trip.service.TripTaskNotFoundException;
import com.zkry.trip.service.TripTaskService;
import com.zkry.trip.service.TripTaskSubscription;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class TripTaskWebSocketHandler extends TextWebSocketHandler {

    private static final String WS_PREFIX = "/api/trip/ws/";

    private final TripTaskService tripTaskService;

    public TripTaskWebSocketHandler(TripTaskService tripTaskService) {
        this.tripTaskService = tripTaskService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String taskId = resolveTaskId(session.getUri());
        if (taskId == null || taskId.isBlank()) {
            send(session, failedEvent("", "任务ID缺失"));
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        AtomicReference<TripTaskSubscription> subscriptionRef = new AtomicReference<>();
        try {
            TripTaskSubscription subscription = tripTaskService.subscribe(taskId, event -> {
                try {
                    send(session, event);
                    if (isFinal(event)) {
                        TripTaskSubscription current = subscriptionRef.get();
                        if (current != null) {
                            current.close();
                        }
                        session.close(CloseStatus.NORMAL);
                    }
                } catch (IOException ex) {
                    TripTaskSubscription current = subscriptionRef.get();
                    if (current != null) {
                        current.close();
                    }
                }
            });
            subscriptionRef.set(subscription);

            TripTaskEvent snapshot = tripTaskService.snapshot(taskId);
            send(session, snapshot);
            if (isFinal(snapshot)) {
                subscription.close();
                session.close(CloseStatus.NORMAL);
            }
        } catch (TripTaskNotFoundException ex) {
            send(session, failedEvent(taskId, "任务不存在"));
            session.close(CloseStatus.NOT_ACCEPTABLE);
        }
    }

    private String resolveTaskId(URI uri) {
        if (uri == null) {
            return null;
        }
        String path = uri.getPath();
        int index = path.indexOf(WS_PREFIX);
        if (index < 0) {
            return null;
        }
        return path.substring(index + WS_PREFIX.length());
    }

    private void send(WebSocketSession session, TripTaskEvent event) throws IOException {
        synchronized (session) {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(JsonUtils.toJsonString(event)));
            }
        }
    }

    private boolean isFinal(TripTaskEvent event) {
        return "completed".equals(event.status()) || "failed".equals(event.status());
    }

    private TripTaskEvent failedEvent(String taskId, String message) {
        return new TripTaskEvent(
            taskId,
            taskId,
            "failed",
            "failed",
            100,
            message,
            message,
            null,
            null
        );
    }
}
