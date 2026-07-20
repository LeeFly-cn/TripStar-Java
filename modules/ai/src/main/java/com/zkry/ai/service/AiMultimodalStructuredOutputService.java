package com.zkry.ai.service;

import com.alibaba.cloud.ai.dashscope.chat.MessageFormat;
import com.alibaba.cloud.ai.dashscope.common.DashScopeApiConstants;
import com.zkry.ai.agent.TripstarAgent;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

/**
 * 多模态结构化输出入口。
 *
 * <p>它和 {@link AiStructuredOutputService} 的区别只有一个：这里允许 UserMessage
 * 携带图片。该调用不需要工具，所以直接使用 ChatModel，避免为媒体输入再包装一层 Agent。
 * 提示词、图片数量、原始响应和失败原因仍会写入提示词追踪日志，便于排查多模态识别问题。
 */
@Service
public class AiMultimodalStructuredOutputService {

    private static final Logger log = LoggerFactory.getLogger(AiMultimodalStructuredOutputService.class);

    private final AiTextService aiTextService;
    private final AiPromptTraceService promptTraceService;

    public AiMultimodalStructuredOutputService(
        AiTextService aiTextService,
        AiPromptTraceService promptTraceService
    ) {
        this.aiTextService = aiTextService;
        this.promptTraceService = promptTraceService;
    }

    /**
     * 根据目标 Java 类型生成模型需要遵守的 Structured Output 格式说明。
     */
    public <T> String format(Class<T> outputType) {
        return new BeanOutputConverter<>(outputType).getFormat();
    }

    /**
     * 调用多模态模型并把响应转换为指定 Java 类型。
     *
     * @param operation 业务操作标识，用于日志和提示词追踪，不代表这里创建了 ReactAgent
     * @param outputType 结构化输出目标类型
     * @param systemPrompt 系统提示词
     * @param userPrompt 已渲染变量的用户提示词
     * @param media 要附加到 UserMessage 的图片列表，允许为空
     * @param mediaDescription 图片序号、来源、本地路径、MIME 类型和大小等可读输入说明
     * @param threadId 本次调用的唯一追踪 ID
     */
    public <T> Optional<T> callForObject(
        TripstarAgent operation,
        Class<T> outputType,
        String systemPrompt,
        String userPrompt,
        List<Media> media,
        String mediaDescription,
        String threadId
    ) {
        // ChatModel 由 AiTextService 统一创建，可继续复用页面运行时配置中的模型和 API Key。
        Optional<ChatModel> model = aiTextService.chatModel();
        if (model.isEmpty()) {
            log.warn("[AI-MULTIMODAL] ChatModel 不可用 operation={} threadId={}", operation.id(), threadId);
            return Optional.empty();
        }

        // 对 null 做一次归一化，后面创建 UserMessage 和记录日志时无需反复判空。
        List<Media> safeMedia = media == null ? List.of() : media;
        long startedAt = System.currentTimeMillis();
        log.info("[AI-MULTIMODAL] 开始调用 operation={} threadId={} promptLength={} imageCount={} multiModel=true",
            operation.id(), threadId, length(userPrompt), safeMedia.size());

        String responseText = "";
        try {
            // DashScope 通过消息元数据判断媒体类型。metadata 必须是可变 Map，后面才能写入 IMAGE 标记。
            UserMessage userMessage = UserMessage.builder()
                .text(userPrompt)
                .media(safeMedia)
                .metadata(new HashMap<>())
                .build();
            userMessage.getMetadata().put(DashScopeApiConstants.MESSAGE_FORMAT, MessageFormat.IMAGE);

            // 系统约束、用户文本和图片放在同一次请求中。
            // multimodal-generation 端点已由 AiTextService 的模型默认选项统一开启，
            // 因而普通 ChatModel 调用和 ReactAgent 工具调用会使用完全相同的模型端点配置。
            ChatResponse response = model.get().call(new Prompt(List.of(
                new SystemMessage(systemPrompt),
                userMessage
            )));
            // 部分模型 SDK 在失败时可能返回空 ChatResponse，因此先转成空字符串并统一报错。
            responseText = response == null || response.getResult() == null
                ? ""
                : response.getResult().getOutput().getText();
            if (responseText == null || responseText.isBlank()) {
                throw new IllegalStateException("多模态模型返回空内容");
            }

            // BeanOutputConverter 负责从模型文本中提取 JSON 并映射 record/class，替代手写 JSON 解析器。
            T result = new BeanOutputConverter<>(outputType).convert(responseText);
            if (result == null) {
                throw new IllegalStateException("多模态结构化输出转换结果为空");
            }
            long elapsedMs = System.currentTimeMillis() - startedAt;
            // 成功和失败都落盘，调试时可以直接对照 system prompt、user prompt、图片数和模型原文。
            promptTraceService.writeSuccess(
                operation.id(), threadId, systemPrompt, userPrompt,
                traceMedia(safeMedia, mediaDescription), responseText, elapsedMs
            );
            log.info("[AI-MULTIMODAL] 结构化输出成功 operation={} threadId={} responseLength={} elapsedMs={}",
                operation.id(), threadId, responseText.length(), elapsedMs);
            return Optional.of(result);
        } catch (Exception ex) {
            long elapsedMs = System.currentTimeMillis() - startedAt;
            promptTraceService.writeFailure(
                operation.id(), threadId, systemPrompt, userPrompt,
                traceMedia(safeMedia, mediaDescription), responseText, ex.getMessage(), elapsedMs
            );
            log.warn("[AI-MULTIMODAL] 调用失败 operation={} threadId={} imageCount={} elapsedMs={} reason={}",
                operation.id(), threadId, safeMedia.size(), elapsedMs, ex.getMessage(), ex);
            // 上层使用 Optional 决定具体业务错误文案，本层只负责保留完整技术日志。
            return Optional.empty();
        }
    }

    /** 日志使用的空安全字符串长度计算。 */
    private int length(String value) {
        return value == null ? 0 : value.length();
    }

    /**
     * 复用 AI trace 的 tools 字段记录多模态媒体输入；图片二进制本身仍保存在任务临时目录。
     */
    private String traceMedia(List<Media> media, String mediaDescription) {
        String description = mediaDescription == null ? "" : mediaDescription.trim();
        return "mediaCount=" + media.size()
            + (description.isBlank() ? "" : "; media=" + description);
    }
}
