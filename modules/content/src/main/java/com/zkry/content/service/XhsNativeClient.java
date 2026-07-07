package com.zkry.content.service;

import com.zkry.common.json.utils.JsonUtils;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class XhsNativeClient {

    private static final Logger log = LoggerFactory.getLogger(XhsNativeClient.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .build();

    private final XhsSignService signService;

    @Value("${tripstar.content.xhs.base-url:https://edith.xiaohongshu.com}")
    private String baseUrl;

    @Value("${tripstar.content.xhs.timeout-seconds:20}")
    private long timeoutSeconds;

    public XhsNativeClient(XhsSignService signService) {
        this.signService = signService;
    }

    /**
     * 调用小红书 Web 搜索接口。
     *
     * <p>这里保持接近 Python 版 TripStar 的请求结构；签名、Cookie 归 {@link XhsSignService} 处理，
     * 返回的原始 JSON 交给 {@link XhsContentService} 做笔记过滤和 LLM 提炼。
     */
    public JsonNode searchNotes(String cookie, String keyword, int page, int sortType, int pageSize) {
        log.info("[XHS-API] 准备搜索笔记 keyword={} page={} sortType={} pageSize={}",
            keyword, page, sortType, pageSize);
        String api = "/api/sns/web/v1/search/notes";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("keyword", keyword);
        data.put("page", page);
        data.put("page_size", pageSize);
        data.put("search_id", traceId(21));
        data.put("sort", sort(sortType));
        data.put("note_type", 0);
        data.put("ext_flags", List.of());
        data.put("filters", List.of(
            Map.of("tags", List.of(sort(sortType)), "type", "sort_type"),
            Map.of("tags", List.of("不限"), "type", "filter_note_type"),
            Map.of("tags", List.of("不限"), "type", "filter_note_time"),
            Map.of("tags", List.of("不限"), "type", "filter_note_range"),
            Map.of("tags", List.of("不限"), "type", "filter_pos_distance")
        ));
        data.put("geo", "");
        data.put("image_formats", List.of("jpg", "webp", "avif"));
        return post(cookie, api, data);
    }

    public JsonNode noteDetail(String cookie, String noteId, String xsecToken, String xsecSource) {
        log.info("[XHS-API] 准备获取笔记详情 noteId={} xsecSource={}", noteId, xsecSource);
        String api = "/api/sns/web/v1/feed";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source_note_id", noteId);
        data.put("image_formats", List.of("jpg", "webp", "avif"));
        data.put("extra", Map.of("need_body_topic", "1"));
        data.put("xsec_source", xsecSource == null || xsecSource.isBlank() ? "pc_search" : xsecSource);
        data.put("xsec_token", xsecToken == null ? "" : xsecToken);
        return post(cookie, api, data);
    }

    /**
     * 发送已签名的小红书 POST 请求。
     *
     * <p>日志只保留 API、状态码、耗时、body 长度等排障信息，不输出 Cookie、x-s、x-s-common。
     */
    private JsonNode post(String cookie, String api, Map<String, Object> data) {
        long startedAt = System.currentTimeMillis();
        log.debug("[XHS-API] 开始签名 api={} payloadKeys={}", api, data == null ? List.of() : data.keySet());
        XhsSignService.SignedRequest signedRequest = signService.sign(cookie, api, data, "POST");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(api))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .POST(HttpRequest.BodyPublishers.ofString(signedRequest.body(), StandardCharsets.UTF_8));
        signedRequest.headers().forEach((name, value) -> {
            if (!isRestrictedHeader(name)) {
                builder.header(name, value);
            }
        });

        try {
            log.info("[XHS-API] 发送请求 api={} bodyLength={}", api, signedRequest.body() == null ? 0 : signedRequest.body().length());
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode root = JsonUtils.parseTree(response.body());
            log.info("[XHS-API] 收到响应 api={} status={} elapsedMs={} bodyLength={}",
                api, response.statusCode(), System.currentTimeMillis() - startedAt, response.body() == null ? 0 : response.body().length());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new XhsCookieExpiredException("小红书 HTTP 状态异常: " + response.statusCode());
            }
            if (root == null) {
                throw new XhsCookieExpiredException("小红书响应为空");
            }
            if (!root.path("success").asBoolean(false)) {
                String code = root.path("code").asText("");
                String msg = root.path("msg").asText("");
                log.warn("[XHS-API] 接口返回失败 api={} code={} msg={}", api, code, msg);
                if ("300011".equals(code) || msg.contains("异常") || msg.contains("登录")) {
                    throw new XhsCookieExpiredException("小红书 Cookie 已失效或被风控拦截 (code=" + code + "): " + msg);
                }
                throw new XhsApiException(code, msg);
            }
            log.debug("[XHS-API] 接口调用成功 api={} hasData={}", api, !root.path("data").isMissingNode());
            return root;
        } catch (IOException ex) {
            log.warn("[XHS-API] 请求 IO 失败 api={} elapsedMs={} reason={}", api, System.currentTimeMillis() - startedAt, ex.getMessage());
            throw new XhsCookieExpiredException("小红书请求失败", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("[XHS-API] 请求被中断 api={} elapsedMs={}", api, System.currentTimeMillis() - startedAt);
            throw new XhsCookieExpiredException("小红书请求被中断", ex);
        }
    }

    private URI uri(String api) {
        String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(normalizedBaseUrl + api);
    }

    private String sort(int sortType) {
        return switch (sortType) {
            case 1 -> "time_descending";
            case 2 -> "popularity_descending";
            case 3 -> "comment_descending";
            case 4 -> "collect_descending";
            default -> "general";
        };
    }

    private String traceId(int length) {
        String chars = "abcdef0123456789";
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append(chars.charAt((int) Math.floor(16 * Math.random())));
        }
        return value.toString();
    }

    private boolean isRestrictedHeader(String name) {
        String lowerName = name == null ? "" : name.toLowerCase();
        return "authority".equals(lowerName)
            || "host".equals(lowerName)
            || "connection".equals(lowerName)
            || "content-length".equals(lowerName)
            || "expect".equals(lowerName)
            || "upgrade".equals(lowerName);
    }
}
