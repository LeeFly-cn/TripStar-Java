package com.zkry.trip.service;

import com.zkry.common.core.exception.BizException;
import com.zkry.content.dto.ContentPlanningContext;
import com.zkry.content.dto.xhsnote.XhsNoteRawContent;
import com.zkry.content.service.XhsNoteImageService;
import com.zkry.content.service.XhsNoteReaderService;
import com.zkry.map.dto.MapPlanningContext;
import com.zkry.trip.constant.TripTaskMessages;
import com.zkry.trip.dto.TripRequest;
import com.zkry.trip.dto.xhsnote.XhsNotePlanRequest;
import com.zkry.trip.dto.xhsnote.XhsNoteResearchContext;
import com.zkry.trip.dto.xhsnote.XhsNoteUnderstandingResult;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 指定小红书笔记模式的资料研究主流程。
 *
 * <p>这个类只负责编排，不直接解析 HTML、下载图片或调用高德接口。一次请求会依次经过：
 * 链接解析与正文读取、图片下载、多模态理解、旅行参数推导、内容上下文转换和高德研究。
 * 最终返回的 {@link XhsNoteResearchContext} 可以直接交给现有 Planner Agent，因而不需要改动
 * 原来的自主规划 Graph。
 */
@Service
public class XhsNoteResearchService {

    private static final Logger log = LoggerFactory.getLogger(XhsNoteResearchService.class);

    private final XhsNoteReaderService noteReaderService;
    private final XhsNoteImageService imageService;
    private final XhsNoteUnderstandingService understandingService;
    private final XhsNoteTripRequestFactory tripRequestFactory;
    private final XhsNoteContentContextFactory contentContextFactory;
    private final XhsNoteMapResearchService mapResearchService;

    public XhsNoteResearchService(
        XhsNoteReaderService noteReaderService,
        XhsNoteImageService imageService,
        XhsNoteUnderstandingService understandingService,
        XhsNoteTripRequestFactory tripRequestFactory,
        XhsNoteContentContextFactory contentContextFactory,
        XhsNoteMapResearchService mapResearchService
    ) {
        this.noteReaderService = noteReaderService;
        this.imageService = imageService;
        this.understandingService = understandingService;
        this.tripRequestFactory = tripRequestFactory;
        this.contentContextFactory = contentContextFactory;
        this.mapResearchService = mapResearchService;
    }

    /**
     * 完成指定笔记模式的全部资料研究工作。
     *
     * @param taskId 当前异步任务 ID，同时用于日志串联和图片临时目录隔离
     * @param request 前端提交的笔记链接、粘贴正文、额外要求和出发日期
     * @param reporter 向任务服务回报当前阶段，任务服务再通过 WebSocket 推给前端
     * @return Planner Agent 所需的旅行请求、内容上下文和地图上下文
     */
    public XhsNoteResearchContext research(
        String taskId,
        XhsNotePlanRequest request,
        TripResearchProgressReporter reporter
    ) {
        // 阶段 1：先解析分享文案中的长链接、短链，并读取公开笔记页面。
        reporter.report(
            TripTaskStage.XHS_NOTE_RESOLVE,
            TripTaskProgress.XHS_NOTE_RESOLVE,
            TripTaskMessages.XHS_NOTE_RESOLVE
        );

        List<XhsNoteRawContent> notes = new ArrayList<>();
        if (!request.safeShareText().isBlank()) {
            // 一段分享文案可以包含多条链接，readAll 会逐条解析并保持输入顺序。
            notes.addAll(noteReaderService.readAll(request.safeShareText()));
        }
        if (!request.safeNoteContent().isBlank()) {
            // 把用户直接粘贴的攻略包装成“虚拟笔记”，后面即可和远程笔记走同一套多模态输入构造逻辑。
            notes.add(new XhsNoteRawContent(
                "pasted-content-" + taskId,
                "pasted-content",
                "用户粘贴的攻略笔记",
                request.safeNoteContent(),
                "用户输入",
                List.of()
            ));
        }
        // 请求校验只保证用户填写了内容；这里进一步保证内容层确实产出了可处理的笔记对象。
        if (notes.isEmpty()) {
            throw new BizException("没有读取到任何小红书笔记内容。");
        }
        log.info("[XHS-NOTE] 笔记内容准备完成 taskId={} notes={} remoteNotes={} pasted={}",
            taskId,
            notes.size(),
            notes.stream().filter(note -> !"pasted-content".equals(note.sourceUrl())).count(),
            !request.safeNoteContent().isBlank());

        // 图片保存在任务专属临时目录中。无论后续成功还是失败，finally 都必须负责清理。
        try {
            // 阶段 2：下载笔记全部图片，转换为 Spring AI Media 可读取的本地资源。
            reporter.report(
                TripTaskStage.XHS_NOTE_IMAGE,
                TripTaskProgress.XHS_NOTE_IMAGE,
                TripTaskMessages.XHS_NOTE_IMAGE
            );
            List<XhsNoteRawContent> downloaded = imageService.downloadAll(taskId, notes);

            // 阶段 3：正文和图片一起交给多模态模型，得到城市、天数、Day 路线和地点清单。
            reporter.report(
                TripTaskStage.XHS_NOTE_UNDERSTANDING,
                TripTaskProgress.XHS_NOTE_UNDERSTANDING,
                TripTaskMessages.XHS_NOTE_UNDERSTANDING
            );
            XhsNoteUnderstandingResult understanding = understandingService.understand(
                taskId,
                request,
                downloaded
            );
            // 适配成旧版 TripRequest 后，Weather、Planner、Review 等已有能力可以原样复用。
            TripRequest inferredTrip = tripRequestFactory.create(request, understanding);
            log.info("[XHS-NOTE] 已推导旅行参数 taskId={} cities={} days={} date={}~{} transportation={} accommodation={}",
                taskId,
                inferredTrip.normalizedCities(),
                inferredTrip.safeTravelDays(),
                inferredTrip.start_date(),
                inferredTrip.end_date(),
                inferredTrip.safeTransportation(),
                inferredTrip.safeAccommodation());

            // ContentPlanningContext 保存“笔记说了什么”，MapPlanningContext 保存“高德校验和补充了什么”。
            ContentPlanningContext contentContext = contentContextFactory.create(inferredTrip, understanding);
            MapPlanningContext mapContext = mapResearchService.research(
                taskId,
                inferredTrip,
                understanding,
                reporter
            );
            // 把四类中间结果一起返回，便于任务服务继续规划，也便于以后调试或扩展结果展示。
            return new XhsNoteResearchContext(
                inferredTrip,
                contentContext,
                mapContext,
                understanding
            );
        } finally {
            // Media 读取发生在同步模型调用期间，research 返回时文件已经不再需要。
            imageService.cleanup(taskId);
        }
    }
}
