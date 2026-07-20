package com.zkry.trip.service;

import com.zkry.ai.agent.TripstarAgent;
import com.zkry.ai.prompt.TripstarPrompt;
import com.zkry.ai.prompt.TripstarPromptVariable;
import com.zkry.ai.service.AiMultimodalStructuredOutputService;
import com.zkry.ai.service.PromptResourceService;
import com.zkry.common.core.exception.BizException;
import com.zkry.content.dto.xhsnote.XhsNoteImage;
import com.zkry.content.dto.xhsnote.XhsNoteRawContent;
import com.zkry.trip.dto.xhsnote.XhsNotePlanRequest;
import com.zkry.trip.dto.xhsnote.XhsNoteUnderstandingResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.content.Media;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;

/**
 * 把指定笔记正文和已下载图片交给多模态模型，输出强类型的旅行信息。
 *
 * <p>这里使用普通 {@code ChatModel} 而不是 ReactAgent：这一阶段只需要“看懂材料并提取信息”，
 * 不需要自主选择工具。模型输出由 Spring AI {@code BeanOutputConverter} 转成
 * {@link XhsNoteUnderstandingResult}，后续 Java 代码无需手工截取 JSON。
 */
@Service
public class XhsNoteUnderstandingService {

    private static final Logger log = LoggerFactory.getLogger(XhsNoteUnderstandingService.class);

    private final AiMultimodalStructuredOutputService multimodalService;
    private final PromptResourceService promptResourceService;

    public XhsNoteUnderstandingService(
        AiMultimodalStructuredOutputService multimodalService,
        PromptResourceService promptResourceService
    ) {
        this.multimodalService = multimodalService;
        this.promptResourceService = promptResourceService;
    }

    /**
     * 执行一次多模态结构化理解。
     *
     * @param taskId 当前任务 ID，用于日志和提示词追踪文件关联
     * @param request 用户额外要求及出发日期
     * @param notes 已读取正文、并尝试下载图片的笔记列表
     * @return 模型识别出的城市、天数、每日路线、地点和缺失项
     */
    public XhsNoteUnderstandingResult understand(
        String taskId,
        XhsNotePlanRequest request,
        List<XhsNoteRawContent> notes
    ) {
        // 先把多篇笔记整理成一段带来源标识的文本，并按相同顺序收集图片 Media。
        MultimodalInput input = buildInput(notes);
        Map<String, String> variables = new LinkedHashMap<>();
        // 出发日期和额外要求是用户的硬约束，提示词要求模型优先遵守。
        variables.put(TripstarPromptVariable.START_DATE, request.safeStartDate());
        variables.put(TripstarPromptVariable.XHS_NOTE_REQUIREMENT, request.safeRequirement());
        variables.put(TripstarPromptVariable.XHS_NOTE_TEXT, input.noteText());
        // FORMAT 由 BeanOutputConverter 根据 record 字段生成，避免手写 JSON Schema。
        variables.put(
            TripstarPromptVariable.FORMAT,
            multimodalService.format(XhsNoteUnderstandingResult.class)
        );

        // 提示词统一放在 resources/prompts/tripstar 下，代码中只保留路径常量。
        String systemPrompt = promptResourceService.load(TripstarPrompt.XHS_NOTE_VISION_SYSTEM);
        String userPrompt = promptResourceService.render(TripstarPrompt.XHS_NOTE_VISION_USER, variables);
        log.info("[XHS-NOTE-AI] 开始多模态理解 taskId={} notes={} images={} textLength={} requirementLength={}",
            taskId,
            notes.size(),
            input.media().size(),
            input.noteText().length(),
            request.safeRequirement().length());

        XhsNoteUnderstandingResult result = multimodalService.callForObject(
            TripstarAgent.XHS_NOTE_UNDERSTANDING,
            XhsNoteUnderstandingResult.class,
            systemPrompt,
            userPrompt,
            input.media(),
            input.mediaDescription(),
            taskId + "-xhs-note-understanding"
        ).orElseThrow(() -> new BizException("多模态模型没有返回可解析的笔记理解结果。"));

        log.info("[XHS-NOTE-AI] 多模态理解完成 taskId={} city={} cityStays={} days={} routes={} attractions={} hotels={} restaurants={} excluded={} warnings={}",
            taskId,
            result.city(),
            result.safeCityStays(),
            result.resolvedTravelDays(),
            result.safeDayRoutes().size(),
            result.safeAttractions().size(),
            result.safeHotels().size(),
            result.safeRestaurants().size(),
            result.safeExcludedPlaces(),
            result.safeWarnings());
        return result;
    }

    /**
     * 构造同时包含文本和图片的 UserMessage 输入。
     *
     * <p>文本中的“媒体 N”与 {@code media} 列表顺序一致，便于模型把图片内容归回正确笔记。
     */
    private MultimodalInput buildInput(List<XhsNoteRawContent> notes) {
        List<Media> media = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        StringBuilder mediaDescription = new StringBuilder();
        int mediaIndex = 1;
        int noteIndex = 1;
        for (XhsNoteRawContent note : notes) {
            // 每篇笔记都保留 noteId、标题、作者和正文，模型可以据此处理多篇内容冲突。
            text.append("\n## 笔记 ").append(noteIndex++).append('\n');
            text.append("noteId: ").append(safe(note.noteId())).append('\n');
            text.append("标题: ").append(safe(note.title())).append('\n');
            text.append("作者: ").append(safe(note.author())).append('\n');
            text.append("正文:\n").append(safe(note.description())).append('\n');
            for (XhsNoteImage image : note.safeImages()) {
                // 下载失败的图片保留在日志和 DTO 中，但不能加入 Media，否则模型调用会读取不存在的文件。
                if (!image.downloaded() || image.localPath() == null || image.localPath().isBlank()) {
                    continue;
                }
                FileSystemResource resource = new FileSystemResource(image.localPath());
                // MIME 类型来自图片 HTTP 响应，Spring AI 会据此组装模型需要的多模态消息。
                media.add(new Media(MimeType.valueOf(image.mimeType()), resource));
                text.append("媒体 ").append(mediaIndex++)
                    .append(": noteId=").append(safe(note.noteId()))
                    .append(", imageIndex=").append(image.index())
                    .append('\n');
                // AI trace 不写图片二进制，但会记录每张输入图片的位置和基本属性，便于逐张核对。
                if (!mediaDescription.isEmpty()) {
                    mediaDescription.append(" | ");
                }
                mediaDescription.append("{mediaIndex=").append(media.size())
                    .append(", noteId=").append(safe(note.noteId()))
                    .append(", imageIndex=").append(image.index())
                    .append(", mimeType=").append(safe(image.mimeType()))
                    .append(", bytes=").append(fileSize(image.localPath()))
                    .append(", path=").append(image.localPath())
                    .append('}');
            }
        }
        return new MultimodalInput(
            text.toString().trim(),
            List.copyOf(media),
            mediaDescription.toString()
        );
    }

    /** 多模态文本输入中用“未提供”显式标记空字段。 */
    private String safe(String value) {
        return value == null || value.isBlank() ? "未提供" : value;
    }

    /** 读取 trace 中展示的图片字节数；读取失败时用 -1 表示，不影响真正的模型调用。 */
    private long fileSize(String localPath) {
        try {
            return Files.size(Path.of(localPath));
        } catch (IOException | RuntimeException ex) {
            return -1L;
        }
    }

    /** 仅在本服务内部使用的组合输入，避免把两个始终成对出现的参数分开传递。 */
    private record MultimodalInput(
        String noteText,
        List<Media> media,
        String mediaDescription
    ) {
    }
}
