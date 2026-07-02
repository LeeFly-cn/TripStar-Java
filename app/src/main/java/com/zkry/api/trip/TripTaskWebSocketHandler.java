package com.zkry.api.trip;

import com.zkry.common.json.utils.JsonUtils;
import com.zkry.trip.dto.TripTaskEvent;
import com.zkry.trip.service.TripTaskNotFoundException;
import com.zkry.trip.service.TripTaskService;
import com.zkry.trip.service.TripTaskSubscription;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class TripTaskWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TripTaskWebSocketHandler.class);

    private static final String WS_PREFIX = "/api/trip/ws/";

    private final TripTaskService tripTaskService;

    public TripTaskWebSocketHandler(TripTaskService tripTaskService) {
        this.tripTaskService = tripTaskService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String taskId = resolveTaskId(session.getUri());
        log.info("[TripWS] WebSocket 连接建立 sessionId={} uri={} taskId={}",
            session.getId(), session.getUri(), taskId == null ? "-" : taskId);
        if (taskId == null || taskId.isBlank()) {
            log.warn("[TripWS] WebSocket 缺少 taskId sessionId={} uri={}", session.getId(), session.getUri());
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
                        log.info("[TripWS] 推送最终事件并关闭 sessionId={} taskId={} status={}",
                            session.getId(), taskId, event.status());
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
                    log.warn("[TripWS] 推送任务事件失败 sessionId={} taskId={} reason={}",
                        session.getId(), taskId, ex.getMessage());
                }
            });
            subscriptionRef.set(subscription);
            log.info("[TripWS] 已订阅任务事件 sessionId={} taskId={}", session.getId(), taskId);

            TripTaskEvent snapshot = tripTaskService.snapshot(taskId);
            log.info("[TripWS] 推送任务快照 sessionId={} taskId={} status={} stage={} progress={}",
                session.getId(), taskId, snapshot.status(), snapshot.stage(), snapshot.progress());
            send(session, snapshot);
            if (isFinal(snapshot)) {
                subscription.close();
                session.close(CloseStatus.NORMAL);
            }
        } catch (TripTaskNotFoundException ex) {
            log.warn("[TripWS] 任务不存在，关闭连接 sessionId={} taskId={}", session.getId(), taskId);
            send(session, failedEvent(taskId, "任务不存在"));
            session.close(CloseStatus.NOT_ACCEPTABLE);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("[TripWS] WebSocket 连接关闭 sessionId={} status={} reason={}",
            session.getId(), status.getCode(), status.getReason());
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
                log.debug("[TripWS] 推送任务事件 sessionId={} taskId={} status={} stage={} progress={}",
                    session.getId(), event.task_id(), event.status(), event.stage(), event.progress());
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
