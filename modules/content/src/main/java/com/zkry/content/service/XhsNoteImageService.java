package com.zkry.content.service;

import com.zkry.common.core.exception.BizException;
import com.zkry.content.dto.xhsnote.XhsNoteImage;
import com.zkry.content.dto.xhsnote.XhsNoteRawContent;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 下载指定笔记的全部图片，供多模态模型读取。
 *
 * <p>下载结果写入项目根目录下的 {@code temp/xhs-note-images/{taskId}}。本地学习环境默认
 * 保留图片，方便结合 AI trace 检查多模态输入；生产环境可以通过配置在研究结束后自动删除。
 * 单图、单笔记和单任务都设置明确上限。任意一张图片下载失败都会立即终止任务，保证交给
 * 多模态模型的是完整笔记图片集合。
 */
@Service
public class XhsNoteImageService {

    private static final Logger log = LoggerFactory.getLogger(XhsNoteImageService.class);
    /** 小红书常规图文笔记的图片上限，同时控制单篇模型输入规模。 */
    private static final int MAX_IMAGES_PER_NOTE = 18;
    /** 多篇笔记合计上限，防止一次请求产生过大的多模态上下文。 */
    private static final int MAX_IMAGES_PER_TASK = 40;
    /** 单张图片最大 10MB，HTTP 响应超过该值不写入磁盘。 */
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private final Path imageRoot;
    private final boolean cleanupEnabled;

    public XhsNoteImageService(
        @Value("${tripstar.content.xhs.note-image-dir:./temp/xhs-note-images}") String imageRootDir,
        @Value("${tripstar.content.xhs.note-image-cleanup-enabled:false}") boolean cleanupEnabled
    ) {
        String configuredDir = imageRootDir == null || imageRootDir.isBlank()
            ? "./temp/xhs-note-images"
            : imageRootDir.trim();
        // 相对路径以应用启动目录为基准；当前 IntelliJ 配置的启动目录就是 backend_java 项目根目录。
        this.imageRoot = Path.of(configuredDir).toAbsolutePath().normalize();
        this.cleanupEnabled = cleanupEnabled;
        log.info("[XHS-NOTE-IMAGE] 临时图片配置 root={} cleanupEnabled={}", imageRoot, cleanupEnabled);
    }

    /**
     * 下载本次任务所有笔记图片，并返回带本地路径和下载状态的新笔记对象。
     */
    public List<XhsNoteRawContent> downloadAll(String taskId, List<XhsNoteRawContent> notes) {
        // 在创建目录和发起网络请求前先计算规模，超限时尽早失败。
        int totalImages = notes.stream().mapToInt(note -> note.safeImages().size()).sum();
        if (notes.stream().anyMatch(note -> note.safeImages().size() > MAX_IMAGES_PER_NOTE)) {
            throw new BizException("单篇笔记图片超过 " + MAX_IMAGES_PER_NOTE + " 张，当前多模态流程无法完整处理。");
        }
        if (totalImages > MAX_IMAGES_PER_TASK) {
            throw new BizException("本次笔记图片共 " + totalImages + " 张，超过当前上限 " + MAX_IMAGES_PER_TASK + " 张。");
        }

        // 每个任务使用独立目录，避免并发任务出现同名图片覆盖。
        Path taskDir = taskDirectory(taskId);
        log.info("[XHS-NOTE-IMAGE] 准备下载笔记图片 taskId={} notes={} total={} taskDir={}",
            taskId, notes.size(), totalImages, taskDir);
        try {
            Files.createDirectories(taskDir);
        } catch (IOException ex) {
            throw new BizException("创建小红书图片临时目录失败：" + ex.getMessage());
        }

        List<XhsNoteRawContent> result = new ArrayList<>();
        for (XhsNoteRawContent note : notes) {
            // 返回新 record 而不是修改原对象，原始远程数据和下载后数据边界更清晰。
            List<XhsNoteImage> images = note.safeImages().stream()
                .map(image -> downloadOne(taskDir, note, image))
                .toList();
            XhsNoteRawContent downloaded = new XhsNoteRawContent(
                note.noteId(), note.sourceUrl(), note.title(), note.description(), note.author(), images
            );
            // 无图的纯文本笔记仍然可用；有图片时 downloadOne 已保证每张都成功。
            if (!downloaded.hasUsableContent()) {
                throw new BizException("笔记正文为空且图片全部下载失败，noteId=" + note.noteId());
            }
            result.add(downloaded);
        }
        log.info("[XHS-NOTE-IMAGE] 图片下载完成 taskId={} notes={} total={} success={} taskDir={}",
            taskId, result.size(), totalImages, totalImages, taskDir);
        return List.copyOf(result);
    }

    /**
     * 根据配置删除或保留任务临时目录。
     *
     * <p>关闭自动清理时只记录保留目录；开启时先规范化并校验目录仍位于固定根目录内，
     * 再执行递归删除，避免路径越界。
     */
    public void cleanup(String taskId) {
        Path taskDir = imageRoot.resolve(taskId).normalize();
        if (!taskDir.startsWith(imageRoot) || !Files.exists(taskDir)) {
            return;
        }
        if (!cleanupEnabled) {
            log.info("[XHS-NOTE-IMAGE] 保留任务图片用于调试 taskId={} taskDir={}", taskId, taskDir);
            return;
        }
        try (Stream<Path> paths = Files.walk(taskDir)) {
            // 必须先删除文件和子目录，最后才能删除任务根目录，因此使用逆序。
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    log.warn("[XHS-NOTE-IMAGE] 临时文件删除失败 path={} reason={}", path, ex.getMessage());
                }
            });
        } catch (IOException ex) {
            log.warn("[XHS-NOTE-IMAGE] 临时目录清理失败 taskId={} reason={}", taskId, ex.getMessage());
        }
    }

    /** 下载单张图片；任意异常都会带笔记 ID 和图片序号向上抛出。 */
    private XhsNoteImage downloadOne(Path taskDir, XhsNoteRawContent note, XhsNoteImage image) {
        long startedAt = System.currentTimeMillis();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(image.sourceUrl()))
                .timeout(Duration.ofSeconds(25))
                .header("User-Agent", XhsHttpHeaders.BROWSER_USER_AGENT)
                // 图片 CDN 可能检查来源页面，Referer 使用当前笔记公开地址。
                .header("Referer", note.sourceUrl())
                .GET()
                .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            // MIME 类型既用于校验响应，也会传给 Spring AI Media。
            String mimeType = response.headers().firstValue("Content-Type")
                .orElse("").split(";", 2)[0].trim();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("HTTP status=" + response.statusCode());
            }
            if (!mimeType.startsWith("image/")) {
                throw new IOException("响应不是图片，contentType=" + mimeType);
            }
            if (response.body().length > MAX_IMAGE_BYTES) {
                throw new IOException("图片超过 10MB");
            }
            // noteId + 图片序号在任务目录内唯一，扩展名按真实 Content-Type 生成。
            Path file = taskDir.resolve(note.noteId() + "-" + image.index() + extension(mimeType));
            Files.write(file, response.body());
            log.debug("[XHS-NOTE-IMAGE] 图片下载成功 noteId={} index={} bytes={} elapsedMs={}",
                note.noteId(), image.index(), response.body().length, System.currentTimeMillis() - startedAt);
            return new XhsNoteImage(image.index(), image.sourceUrl(), file.toString(), mimeType, true, "");
        } catch (InterruptedException ex) {
            // 恢复线程中断标记，再终止整个任务。
            Thread.currentThread().interrupt();
            throw imageDownloadException(note, image, "图片下载被中断");
        } catch (Exception ex) {
            log.warn("[XHS-NOTE-IMAGE] 图片下载失败 noteId={} index={} elapsedMs={} reason={}",
                note.noteId(), image.index(), System.currentTimeMillis() - startedAt, ex.getMessage());
            throw imageDownloadException(note, image, ex.getMessage());
        }
    }

    /** 统一生成前端可直接显示的图片下载失败错误。 */
    private BizException imageDownloadException(
        XhsNoteRawContent note,
        XhsNoteImage image,
        String reason
    ) {
        String detail = reason == null || reason.isBlank() ? "未知错误" : reason;
        return new BizException(
            "小红书图片下载失败，noteId=" + note.noteId()
                + "，imageIndex=" + image.index()
                + "，reason=" + detail
        );
    }

    /** 生成当前任务的规范化临时目录路径。 */
    private Path taskDirectory(String taskId) {
        Path taskDir = imageRoot.resolve(taskId).normalize();
        if (!taskDir.startsWith(imageRoot)) {
            throw new BizException("小红书图片临时目录越界，taskId=" + taskId);
        }
        return taskDir;
    }

    /** 把常见图片 MIME 类型转换为文件扩展名，未知图片格式按 jpg 保存。 */
    private String extension(String mimeType) {
        return switch (mimeType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".jpg";
        };
    }
}
