package com.zkry.ai.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AI 调用明细落盘服务。
 *
 * <p>控制台日志适合看流程，但不适合放完整系统提示词、用户提示词和模型原始输出。
 * 这些内容通常很长，也可能包含用户备注、小红书正文等调试材料，所以单独写到
 * {@code logs/ai-trace} 目录，按 threadId 查起来更清楚。
 */
@Service
public class AiPromptTraceService {

    private static final Logger log = LoggerFactory.getLogger(AiPromptTraceService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final boolean enabled;
    private final String traceDir;
    private final String logPath;

    public AiPromptTraceService(
        @Value("${tripstar.ai.trace.enabled:true}") boolean enabled,
        @Value("${tripstar.ai.trace.dir:}") String traceDir,
        @Value("${logging.file.path:./logs}") String logPath
    ) {
        this.enabled = enabled;
        this.traceDir = traceDir;
        this.logPath = logPath;
    }

    public void writeSuccess(
        String agentName,
        String threadId,
        String instruction,
        String userPrompt,
        String tools,
        String response,
        long elapsedMs
    ) {
        write(agentName, threadId, instruction, userPrompt, tools, response, "SUCCESS", null, elapsedMs);
    }

    public void writeFailure(
        String agentName,
        String threadId,
        String instruction,
        String userPrompt,
        String tools,
        String response,
        String error,
        long elapsedMs
    ) {
        write(agentName, threadId, instruction, userPrompt, tools, response, "FAILED", error, elapsedMs);
    }

    private void write(
        String agentName,
        String threadId,
        String instruction,
        String userPrompt,
        String tools,
        String response,
        String status,
        String error,
        long elapsedMs
    ) {
        if (!enabled) {
            return;
        }
        try {
            Path dir = traceRoot().resolve(LocalDate.now().format(DAY));
            Files.createDirectories(dir);
            Path file = dir.resolve(fileName(agentName, threadId));
            Files.writeString(
                file,
                markdown(agentName, threadId, instruction, userPrompt, tools, response, status, error, elapsedMs),
                StandardCharsets.UTF_8
            );
            log.info("[AI-TRACE] Agent 调用明细已写入 file={}", file.toAbsolutePath());
        } catch (Exception ex) {
            log.warn("[AI-TRACE] Agent 调用明细写入失败 agent={} threadId={} reason={}",
                agentName, threadId, ex.getMessage());
        }
    }

    private Path traceRoot() throws IOException {
        String configured = traceDir == null ? "" : traceDir.trim();
        if (!configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of(logPath == null || logPath.isBlank() ? "./logs" : logPath).resolve("ai-trace");
    }

    private String fileName(String agentName, String threadId) {
        String time = LocalDateTime.now().format(FILE_TIME);
        return time + "_" + clean(threadId) + "_" + clean(agentName) + ".md";
    }

    private String clean(String value) {
        String source = value == null || value.isBlank() ? "unknown" : value;
        return source.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String markdown(
        String agentName,
        String threadId,
        String instruction,
        String userPrompt,
        String tools,
        String response,
        String status,
        String error,
        long elapsedMs
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("# TripStar AI Trace\n\n");
        builder.append("- time: ").append(LocalDateTime.now()).append('\n');
        builder.append("- agent: ").append(nullToEmpty(agentName)).append('\n');
        builder.append("- threadId: ").append(nullToEmpty(threadId)).append('\n');
        builder.append("- status: ").append(status).append('\n');
        builder.append("- elapsedMs: ").append(elapsedMs).append('\n');
        builder.append("- tools: ").append(nullToEmpty(tools)).append("\n\n");
        if (error != null && !error.isBlank()) {
            appendBlock(builder, "Error", error);
        }
        appendBlock(builder, "System Prompt", instruction);
        appendBlock(builder, "User Prompt", userPrompt);
        appendBlock(builder, "Model Output", response);
        return builder.toString();
    }

    private void appendBlock(StringBuilder builder, String title, String value) {
        builder.append("## ").append(title).append("\n\n");
        builder.append("~~~text\n");
        builder.append(nullToEmpty(value)).append('\n');
        builder.append("~~~\n\n");
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
