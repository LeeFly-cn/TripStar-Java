package com.zkry.api.chat;

import com.zkry.ai.agent.TripstarAgent;
import com.zkry.ai.prompt.TripstarPrompt;
import com.zkry.ai.prompt.TripstarPromptVariable;
import com.zkry.ai.service.AiAgentService;
import com.zkry.ai.service.PromptResourceService;
import com.zkry.common.core.exception.BizException;
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

    private final AiAgentService aiAgentService;
    private final PromptResourceService promptResourceService;

    public ChatController(
        AiAgentService aiAgentService,
        PromptResourceService promptResourceService
    ) {
        this.aiAgentService = aiAgentService;
        this.promptResourceService = promptResourceService;
    }

    @PostMapping("/ask")
    public TripChatResponse ask(@RequestBody Map<String, Object> request) {
        long startedAt = System.currentTimeMillis();
        String message = String.valueOf(request.getOrDefault("message", ""));
        Object tripPlan = request.get("trip_plan");
        log.info("[ChatAPI] 收到伴游问答 messageLength={} hasTripPlan={} aiAvailable={}",
            message.length(), tripPlan != null, aiAgentService.isAvailable());
        Optional<String> aiReply = aiAgentService.call(
            TripstarAgent.TRIP_CHAT,
            promptResourceService.load(TripstarPrompt.CHAT_SYSTEM),
            promptResourceService.render(TripstarPrompt.CHAT_USER, Map.of(
                TripstarPromptVariable.MESSAGE, message,
                TripstarPromptVariable.TRIP_PLAN, String.valueOf(tripPlan)
            )),
            "trip-chat"
        );
        String reply = aiReply.orElseThrow(() -> new BizException("AI 未配置或调用失败，请先在设置页填写 AI API Key 和模型名称。"));
        log.info("[ChatAPI] 伴游问答完成 aiReply={} replyLength={} elapsedMs={}",
            aiReply.isPresent(), reply.length(), System.currentTimeMillis() - startedAt);
        return new TripChatResponse(true, reply);
    }
}
