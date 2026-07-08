package com.zkry.ai.service;

import com.zkry.ai.agent.TripstarAgent;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

@Service
public class AiStructuredOutputService {

    private static final Logger log = LoggerFactory.getLogger(AiStructuredOutputService.class);

    private final AiAgentService aiAgentService;

    public AiStructuredOutputService(AiAgentService aiAgentService) {
        this.aiAgentService = aiAgentService;
    }

    /**
     * 生成 Spring AI 结构化输出格式说明。
     *
     * <p>调用方把这个字符串填进 prompt 的 {@code {{format}}}，模型就知道应该输出
     * 哪些字段、字段类型是什么。
     */
    public <T> String format(Class<T> type) {
        String format = new BeanOutputConverter<>(type).getFormat();
        log.debug("[AI-STRUCTURED] 生成结构化输出格式 type={} formatLength={}", type.getSimpleName(), format.length());
        return format;
    }

    public <T> String format(ParameterizedTypeReference<T> type) {
        String format = new BeanOutputConverter<>(type).getFormat();
        log.debug("[AI-STRUCTURED] 生成结构化输出格式 type={} formatLength={}", type.getType(), format.length());
        return format;
    }

    /**
     * 调用 Agent 并把返回文本转换成指定 DTO。
     *
     * <p>这是 PlannerAgent、ReviewAgent、ResearchAgent 的推荐入口，避免业务层
     * 自己截取 Markdown code block 或手写 JSON 修复逻辑。
     */
    public <T> Optional<T> callForObject(
        TripstarAgent agent,
        Class<T> type,
        String instruction,
        String userPrompt,
        String threadId,
        Object... methodTools
    ) {
        return callAndConvert(agent, new BeanOutputConverter<>(type), instruction, userPrompt, threadId, methodTools);
    }

    public <T> Optional<T> callForType(
        TripstarAgent agent,
        ParameterizedTypeReference<T> type,
        String instruction,
        String userPrompt,
        String threadId,
        Object... methodTools
    ) {
        return callAndConvert(agent, new BeanOutputConverter<>(type), instruction, userPrompt, threadId, methodTools);
    }

    /**
     * 统一的“调用 Agent -> BeanOutputConverter 转对象”流程。
     *
     * <p>转换失败时只返回 Optional.empty()，由上层决定是否失败、重试或提示用户。
     */
    private <T> Optional<T> callAndConvert(
        TripstarAgent agent,
        BeanOutputConverter<T> converter,
        String instruction,
        String userPrompt,
        String threadId,
        Object... methodTools
    ) {
        long startedAt = System.currentTimeMillis();
        log.info("[AI-STRUCTURED] 开始结构化 Agent 调用 agent={} threadId={} promptLength={} toolCount={}",
            agent.id(),
            threadId,
            userPrompt == null ? 0 : userPrompt.length(),
            methodTools == null ? 0 : methodTools.length);
        Optional<String> response = aiAgentService.call(agent, instruction, userPrompt, threadId, methodTools);
        if (response.isEmpty()) {
            log.warn("[AI-STRUCTURED] Agent 未返回可解析文本 agent={} threadId={} elapsedMs={}",
                agent.id(), threadId, System.currentTimeMillis() - startedAt);
            return Optional.empty();
        }
        try {
            T converted = converter.convert(response.get());
            log.info("[AI-STRUCTURED] 结构化输出解析成功 agent={} threadId={} responseLength={} elapsedMs={}",
                agent.id(), threadId, response.get().length(), System.currentTimeMillis() - startedAt);
            return Optional.ofNullable(converted);
        } catch (Exception ex) {
            log.warn("[AI-STRUCTURED] 结构化输出解析失败 agent={} threadId={} responseLength={} elapsedMs={} reason={}",
                agent.id(), threadId, response.get().length(), System.currentTimeMillis() - startedAt, ex.getMessage());
            log.debug("[AI-STRUCTURED] 结构化输出解析失败原文片段 agent={} threadId={} response={}",
                agent.id(), threadId, snippet(response.get()));
            return Optional.empty();
        }
    }

    private String snippet(String text) {
        if (text == null || text.length() <= 1000) {
            return text;
        }
        return text.substring(0, 1000) + "...";
    }
}
