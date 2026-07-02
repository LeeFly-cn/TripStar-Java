package com.zkry.api.chat;

import com.zkry.common.core.exception.BizException;
import com.zkry.ai.service.AiTextService;
import com.zkry.trip.dto.TripChatResponse;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final AiTextService aiTextService;

    public ChatController(AiTextService aiTextService) {
        this.aiTextService = aiTextService;
    }

    @PostMapping("/ask")
    public TripChatResponse ask(@RequestBody Map<String, Object> request) {
        long startedAt = System.currentTimeMillis();
        String message = String.valueOf(request.getOrDefault("message", ""));
        Object tripPlan = request.get("trip_plan");
        log.info("[ChatAPI] 收到伴游问答 messageLength={} hasTripPlan={} aiAvailable={}",
            message.length(), tripPlan != null, aiTextService.isAvailable());
        Optional<String> aiReply = aiTextService.generate(
            "你是 TripStar 的旅行伴游助手。请基于用户提供的 trip_plan 回答问题，回答要简洁、具体、可执行。",
            "用户问题：" + message + "\n\n当前行程上下文：" + tripPlan
        );
        String reply = aiReply.orElseThrow(() -> new BizException("AI 未配置或调用失败，请先在设置页填写 AI API Key 和模型名称。"));
        log.info("[ChatAPI] 伴游问答完成 aiReply={} replyLength={} elapsedMs={}",
            aiReply.isPresent(), reply.length(), System.currentTimeMillis() - startedAt);
        return new TripChatResponse(true, reply);
    }
}
