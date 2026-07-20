package com.zkry.content.service;

import com.zkry.common.core.exception.BizException;
import com.zkry.content.dto.xhsnote.XhsNoteLink;
import com.zkry.content.dto.xhsnote.XhsNoteRawContent;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 无 Cookie 读取用户指定的小红书公开笔记。
 *
 * <p>它访问的是浏览器可公开打开的笔记页面，而不是需要签名的搜索/Feed API。因此该模式
 * 不读取运行时配置中的小红书 Cookie；如果公开页面触发登录或安全验证，会直接返回明确错误。
 */
@Service
public class XhsNoteReaderService {

    private static final Logger log = LoggerFactory.getLogger(XhsNoteReaderService.class);

    private final XhsNoteLinkResolver linkResolver;
    private final XhsNotePageParser pageParser;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    public XhsNoteReaderService(XhsNoteLinkResolver linkResolver, XhsNotePageParser pageParser) {
        this.linkResolver = linkResolver;
        this.pageParser = pageParser;
    }

    /**
     * 读取分享文本中全部笔记的 HTML，并解析成正文和远程图片地址。
     */
    public List<XhsNoteRawContent> readAll(String shareText) {
        // 链接展开和去重统一交给 resolver，本服务只负责页面请求和内容校验。
        List<XhsNoteLink> links = linkResolver.resolveAll(shareText);
        List<XhsNoteRawContent> notes = new ArrayList<>();
        for (XhsNoteLink link : links) {
            long startedAt = System.currentTimeMillis();
            log.info("[XHS-NOTE] 开始读取公开笔记 noteId={} host={}",
                link.noteId(), URI.create(link.finalUrl()).getHost());
            String html = requestPage(link.finalUrl());
            // parser 只解析页面内嵌状态，不在这里使用字符串拼接提取正文。
            XhsNoteRawContent note = pageParser.parse(link, html);
            // 标题、正文、图片至少有一种可用，否则继续调用多模态模型也没有意义。
            if ((note.title() == null || note.title().isBlank())
                && (note.description() == null || note.description().isBlank())
                && note.safeImages().isEmpty()) {
                throw new BizException("小红书公开页面没有可用正文或图片，noteId=" + link.noteId());
            }
            notes.add(note);
            log.info("[XHS-NOTE] 公开笔记读取成功 noteId={} titleLength={} descLength={} imageCount={} elapsedMs={}",
                note.noteId(),
                length(note.title()),
                length(note.description()),
                note.safeImages().size(),
                System.currentTimeMillis() - startedAt);
        }
        return List.copyOf(notes);
    }

    /** 使用普通浏览器请求头读取公开 HTML 页面。 */
    private String requestPage(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", XhsHttpHeaders.BROWSER_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .GET()
            .build();
        try {
            HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            // 公开页面只有 2xx 才进入解析；重定向由 HttpClient.NORMAL 自动处理。
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException("小红书公开页面读取失败，HTTP status=" + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException ex) {
            // 恢复中断标记，避免线程池吞掉上层发出的取消/关闭信号。
            Thread.currentThread().interrupt();
            throw new BizException("小红书公开页面读取被中断。");
        } catch (IOException ex) {
            throw new BizException("小红书公开页面读取失败：" + ex.getMessage());
        }
    }

    /** 日志使用的空安全字符串长度计算。 */
    private int length(String value) {
        return value == null ? 0 : value.length();
    }
}
