package com.zkry.ai.service;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

@Service
public class AiAgentService {

    private static final Logger log = LoggerFactory.getLogger(AiAgentService.class);

    private final AiTextService aiTextService;

    public AiAgentService(AiTextService aiTextService) {
        this.aiTextService = aiTextService;
    }

    public boolean isAvailable() {
        return aiTextService.isAvailable();
    }

    public Optional<String> call(String agentName, String instruction, String userPrompt, String threadId) {
        Optional<ChatModel> chatModel = aiTextService.chatModel();
        if (chatModel.isEmpty()) {
            log.info("[AI-AGENT] ChatModel 不可用，跳过 Agent 调用 agent={}", agentName);
            return Optional.empty();
        }
        long startedAt = System.currentTimeMillis();
        try {
            ReactAgent agent = ReactAgent.builder()
                .name(agentName)
                .model(chatModel.get())
                .instruction(instruction)
                .enableLogging(true)
                .build();
            RunnableConfig config = RunnableConfig.builder()
                .threadId(threadId == null || threadId.isBlank() ? agentName : threadId)
                .build();
            log.info("[AI-AGENT] 开始调用 ReactAgent agent={} promptLength={}", agentName, length(userPrompt));
            AssistantMessage message = agent.call(userPrompt, config);
            String content = message == null ? "" : message.getText();
            if (content == null || content.isBlank()) {
                log.warn("[AI-AGENT] ReactAgent 返回空内容 agent={} elapsedMs={}",
                    agentName, System.currentTimeMillis() - startedAt);
                return Optional.empty();
            }
            log.info("[AI-AGENT] ReactAgent 调用成功 agent={} responseLength={} elapsedMs={}",
                agentName, content.length(), System.currentTimeMillis() - startedAt);
            return Optional.of(content.trim());
        } catch (Exception ex) {
            log.warn("[AI-AGENT] ReactAgent 调用失败 agent={} elapsedMs={} reason={}",
                agentName, System.currentTimeMillis() - startedAt, ex.getMessage());
            return Optional.empty();
        }
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }
}
