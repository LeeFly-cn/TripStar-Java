package com.zkry.content.service;

import com.zkry.common.core.exception.BizException;
import com.zkry.content.dto.xhsnote.XhsNoteLink;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 从长链接、短链或 App 分享文案中解析公开小红书笔记链接。
 *
 * <p>解析过程只允许访问 {@code xhslink.com} 和 {@code xiaohongshu.com} 官方域名。
 * 短链采用手动跟随跳转，确保每一跳都重新经过域名校验，避免用户输入任意 URL 造成 SSRF。
 */
@Service
public class XhsNoteLinkResolver {

    private static final Logger log = LoggerFactory.getLogger(XhsNoteLinkResolver.class);
    /** 从完整 App 分享口令中提取所有 http/https URL。 */
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\\u3000]+", Pattern.CASE_INSENSITIVE);
    /** 小红书公开笔记 ID 当前是 24 位十六进制字符串。 */
    private static final Pattern NOTE_ID_PATTERN = Pattern.compile("/(?:explore|discovery/item)/([0-9a-fA-F]{24})(?:/|$)");
    /** 部分短链返回 200 HTML 而不是 30x，此表达式用于从 HTML 中继续提取最终长链接。 */
    private static final Pattern HTML_XHS_URL_PATTERN = Pattern.compile(
        "https?://(?:www\\.)?xiaohongshu\\.com/(?:explore|discovery/item)/[0-9a-fA-F]{24}[^\\s\\\"'<>]*",
        Pattern.CASE_INSENSITIVE
    );
    private static final Set<Integer> REDIRECT_STATUS = Set.of(301, 302, 303, 307, 308);
    private static final int MAX_REDIRECTS = 5;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    /**
     * 解析一段分享文案中的全部笔记链接，并按 noteId 去重。
     *
     * @param shareText 单个链接、多个链接，或包含中文口令的完整分享文本
     */
    public List<XhsNoteLink> resolveAll(String shareText) {
        List<String> urls = extractSupportedUrls(shareText);
        if (urls.isEmpty()) {
            throw new BizException("未找到小红书链接，请粘贴包含链接的完整分享内容。");
        }

        // LinkedHashMap 同时完成去重和顺序保持，模型最终看到的笔记顺序与用户输入一致。
        Map<String, XhsNoteLink> uniqueNotes = new LinkedHashMap<>();
        for (String url : urls) {
            XhsNoteLink link = resolveOne(shareText, url);
            if (!link.readable()) {
                throw new BizException("无法从小红书链接解析笔记 ID：" + maskUrl(url));
            }
            uniqueNotes.putIfAbsent(link.noteId(), link);
        }
        log.info("[XHS-NOTE-LINK] 分享内容解析完成 extractedLinks={} uniqueNotes={}",
            urls.size(), uniqueNotes.size());
        return List.copyOf(uniqueNotes.values());
    }

    /** 展开单个短链并提取 noteId、xsec_token 等页面参数。 */
    private XhsNoteLink resolveOne(String originalInput, String extractedUrl) {
        // 在发起任何网络请求前先校验源地址域名。
        URI source = validateXhsUri(extractedUrl);
        URI target = isShortLink(source) ? followShortLink(source) : source;
        // 短链每次跳转都校验，这里再校验最终地址，防止跳到非小红书域名。
        validateXhsUri(target.toString());
        String noteId = extractNoteId(target);
        return new XhsNoteLink(
            originalInput,
            extractedUrl,
            target.toString(),
            noteId,
            queryValue(target, "xsec_token"),
            queryValue(target, "xsec_source")
        );
    }

    /** 从分享文本中提取官方域名 URL，忽略无效 URL 和其他网站链接。 */
    private List<String> extractSupportedUrls(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> urls = new ArrayList<>();
        Matcher matcher = URL_PATTERN.matcher(text);
        while (matcher.find()) {
            // 中文分享文案常在链接后紧跟句号、括号或引号，创建 URI 前需要先移除。
            String url = trimTrailingPunctuation(matcher.group());
            try {
                URI uri = URI.create(url);
                if (isSupportedHost(uri.getHost())) {
                    urls.add(url);
                }
            } catch (IllegalArgumentException ignored) {
                // 单个坏链接不妨碍继续检查同一段分享文案里的其他链接。
            }
        }
        return urls.stream().distinct().toList();
    }

    /**
     * 手动跟随小红书短链。
     *
     * <p>不启用 HttpClient 自动跳转，是为了每一跳都执行 {@link #validateXhsUri(String)}。
     */
    private URI followShortLink(URI source) {
        URI current = source;
        for (int i = 0; i < MAX_REDIRECTS; i++) {
            HttpRequest request = HttpRequest.newBuilder(current)
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", XhsHttpHeaders.BROWSER_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .GET()
                .build();
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (REDIRECT_STATUS.contains(status)) {
                    String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new BizException("小红书短链返回跳转状态但没有 Location。"));
                    // Location 可能是相对地址，先以当前 URI 为基准补全，再校验新域名。
                    current = validateXhsUri(current.resolve(location).toString());
                    if (!isShortLink(current)) {
                        return current;
                    }
                    continue;
                }
                if (status >= 200 && status < 300) {
                    // 某些 xhslink 页面通过 HTML/脚本暴露长链接，而不是 HTTP Location。
                    Matcher urlMatcher = HTML_XHS_URL_PATTERN.matcher(response.body());
                    if (urlMatcher.find()) {
                        return validateXhsUri(urlMatcher.group());
                    }
                    throw new BizException("小红书短链没有返回可识别的笔记地址。");
                }
                throw new BizException("小红书短链解析失败，HTTP status=" + status);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new BizException("小红书短链解析被中断。");
            } catch (IOException ex) {
                throw new BizException("小红书短链解析失败：" + ex.getMessage());
            }
        }
        throw new BizException("小红书短链跳转次数超过限制。");
    }

    /** 校验协议和官方域名，并返回可继续使用的 URI。 */
    private URI validateXhsUri(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new BizException("只支持 http 或 https 小红书链接。");
            }
            if (!isSupportedHost(uri.getHost())) {
                throw new BizException("链接不是小红书官方域名：" + maskUrl(value));
            }
            return uri;
        } catch (IllegalArgumentException ex) {
            throw new BizException("小红书链接格式无效：" + maskUrl(value));
        }
    }

    /** 只接受小红书主域名及其子域名。 */
    private boolean isSupportedHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalized = host.toLowerCase();
        return normalized.equals("xhslink.com")
            || normalized.endsWith(".xhslink.com")
            || normalized.equals("xiaohongshu.com")
            || normalized.endsWith(".xiaohongshu.com");
    }

    /** 判断 URI 是否仍然是需要展开的 xhslink 短链。 */
    private boolean isShortLink(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        return host.equals("xhslink.com") || host.endsWith(".xhslink.com");
    }

    /** 从 /explore/{id} 或 /discovery/item/{id} 两种公开页面路径提取笔记 ID。 */
    private String extractNoteId(URI uri) {
        Matcher matcher = NOTE_ID_PATTERN.matcher(uri.getPath() == null ? "" : uri.getPath());
        return matcher.find() ? matcher.group(1) : "";
    }

    /** 读取查询参数，并保留 token 中真实的加号字符。 */
    private String queryValue(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return "";
        }
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            String rawName = separator < 0 ? pair : pair.substring(0, separator);
            if (!name.equals(URLDecoder.decode(rawName, StandardCharsets.UTF_8))) {
                continue;
            }
            String rawValue = separator < 0 ? "" : pair.substring(separator + 1);
            // URLDecoder 默认把 '+' 当作空格；xsec_token 中的 '+' 必须先转义后再解码。
            return URLDecoder.decode(rawValue.replace("+", "%2B"), StandardCharsets.UTF_8);
        }
        return "";
    }

    /** 去掉分享文案中粘在 URL 末尾、但并不属于 URL 的中英文标点。 */
    private String trimTrailingPunctuation(String value) {
        String result = value;
        String punctuation = ").,;!?，。；！？】》」』\"'";
        while (!result.isEmpty() && punctuation.indexOf(result.charAt(result.length() - 1)) >= 0) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /** 错误日志只显示 URL 前缀，避免把很长的 token 完整写入日志。 */
    private String maskUrl(String value) {
        if (value == null || value.length() <= 80) {
            return value;
        }
        return value.substring(0, 80) + "...";
    }
}
