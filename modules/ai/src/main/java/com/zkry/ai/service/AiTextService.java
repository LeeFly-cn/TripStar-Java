package com.zkry.ai.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.zkry.common.core.config.TripstarRuntimeSettingsService;
import com.zkry.common.core.config.TripstarSettingKeys;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class AiTextService {

    private static final Logger log = LoggerFactory.getLogger(AiTextService.class);
    private static final Duration DASHSCOPE_CONNECT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DASHSCOPE_READ_TIMEOUT = Duration.ofMinutes(5);

    private final TripstarRuntimeSettingsService runtimeSettingsService;

    public AiTextService(TripstarRuntimeSettingsService runtimeSettingsService) {
        this.runtimeSettingsService = runtimeSettingsService;
    }

    public boolean isAvailable() {
        boolean available = runtimeSettingsService.hasText(TripstarSettingKeys.OPENAI_API_KEY);
        log.debug("[AI] 运行时 AI 配置可用性检查 available={}", available);
        return available;
    }

    /**
     * 统一封装 Spring AI Alibaba 文本生成调用。
     *
     * <p>业务层只关心“有没有生成出文本”，失败原因在这里记录日志并返回 Optional.empty()。
     * 日志只记录长度和耗时，不打印完整 prompt，避免把用户输入或密钥相关上下文写进控制台。
     */
    public Optional<String> generate(String systemPrompt, String userPrompt) {
        Optional<ChatModel> chatModel = chatModel();
        if (chatModel.isEmpty()) {
            log.info("[AI] AI Key 未配置，跳过 LLM 调用 systemPromptLength={} userPromptLength={}",
                length(systemPrompt), length(userPrompt));
            return Optional.empty();
        }
        long startedAt = System.currentTimeMillis();
        log.info("[AI] 开始调用 Spring AI Alibaba systemPromptLength={} userPromptLength={} modelClass={}",
            length(systemPrompt), length(userPrompt), chatModel.get().getClass().getSimpleName());
        try {
            String content = ChatClient.create(chatModel.get())
                .prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();
            if (content == null || content.isBlank()) {
                log.warn("[AI] Spring AI Alibaba 返回空内容 elapsedMs={}", System.currentTimeMillis() - startedAt);
                return Optional.empty();
            }
            log.info("[AI] Spring AI Alibaba 调用成功 responseLength={} elapsedMs={}",
                content.length(), System.currentTimeMillis() - startedAt);
            return Optional.of(content.trim());
        } catch (Exception ex) {
            log.warn("[AI] Spring AI Alibaba 调用失败 elapsedMs={} reason={}",
                System.currentTimeMillis() - startedAt, ex.getMessage(), ex);
            return Optional.empty();
        }
    }

    public Optional<ChatModel> chatModel() {
        Optional<String> apiKey = runtimeSettingsService.stringValue(TripstarSettingKeys.OPENAI_API_KEY);
        if (apiKey.isEmpty()) {
            return Optional.empty();
        }
        String model = runtimeSettingsService.stringValue(TripstarSettingKeys.OPENAI_MODEL).orElse("qwen-plus");
        String baseUrl = runtimeSettingsService.stringValue(TripstarSettingKeys.OPENAI_BASE_URL).orElse("");
        try {
            // Spring AI Alibaba 2.0.0-M1.1 默认只等待模型响应 180 秒。
            // 多轮工具调用结束后的总结可能超过这个时间，因此在创建 DashScopeApi 时明确设置为 5 分钟。
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(DASHSCOPE_CONNECT_TIMEOUT);
            requestFactory.setReadTimeout(DASHSCOPE_READ_TIMEOUT);

            DashScopeApi.Builder apiBuilder = DashScopeApi.builder()
                .apiKey(apiKey.get())
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory));
            if (!baseUrl.isBlank()) {
                apiBuilder.baseUrl(baseUrl);
            }
            DashScopeChatOptions options = DashScopeChatOptions.builder()
                .model(model)
                // TripStar 只维护一套模型配置，并且指定笔记模式必须识别图片。
                // 因此这里统一使用 DashScope multimodal-generation 端点。
                // 即使某次 Agent 只发送文本，qwen3.7-plus 这类多模态模型也必须走该端点，
                // 否则 DashScope 会因为“模型名称与 API 端点不匹配”返回 url error。
                .multiModel(true)
                .build();
            return Optional.of(DashScopeChatModel.builder()
                .dashScopeApi(apiBuilder.build())
                .defaultOptions(options)
                .build());
        } catch (Exception ex) {
            log.warn("[AI] 运行时 DashScope ChatModel 创建失败 reason={}", ex.getMessage(), ex);
            return Optional.empty();
        }
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }
}
