package com.zkry.map.service;

import com.zkry.common.core.config.TripstarRuntimeSettingsService;
import com.zkry.common.core.config.TripstarSettingKeys;
import com.zkry.common.core.exception.BizException;
import com.zkry.common.json.utils.JsonUtils;
import com.zkry.map.dto.MapPoi;
import com.zkry.map.dto.MapPoint;
import com.zkry.map.dto.MapWeatherForecast;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 高德 REST API 访问层。
 *
 * <p>这里放确定性、可测试的 HTTP 能力：地理编码、POI、天气。上层通过
 * {@link AmapGeoPoiTools}、{@link AmapWeatherTools} 和 {@link AmapHotelTools}
 * 暴露给 ReactAgent 调用。这样 Tool 只是“外壳”，不会复制一套高德请求逻辑。
 */
@Service
public class AmapMapContextService {

    private static final Logger log = LoggerFactory.getLogger(AmapMapContextService.class);
    private static final String AMAP_RATE_LIMIT_INFOCODE = "10021";

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(4))
        .build();
    private final Object rateLimitMonitor = new Object();

    private final TripstarRuntimeSettingsService runtimeSettingsService;
    private long lastRequestAt;

    public AmapMapContextService(TripstarRuntimeSettingsService runtimeSettingsService) {
        this.runtimeSettingsService = runtimeSettingsService;
    }

    @Value("${tripstar.map.amap.enabled:false}")
    private boolean enabled;

    @Value("${tripstar.map.amap.base-url:https://restapi.amap.com}")
    private String baseUrl;

    @Value("${tripstar.map.amap.min-interval-ms:350}")
    private long minIntervalMs;

    @Value("${tripstar.map.amap.rate-limit-retries:2}")
    private int rateLimitRetries;

    @Value("${tripstar.map.amap.rate-limit-retry-delay-ms:1000}")
    private long rateLimitRetryDelayMs;

    public GeocodeResult geocode(String city) throws IOException, InterruptedException {
        validateReady();
        log.info("[AMap] 地理编码 city={}", city);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("address", city);
        params.put("city", city);
        params.put("output", "JSON");
        JsonNode root = get("/v3/geocode/geo", params);
        JsonNode geocodes = root.path("geocodes");
        if (!geocodes.isArray() || geocodes.isEmpty()) {
            log.info("[AMap] 地理编码无结果 city={}", city);
            return new GeocodeResult("", null);
        }
        JsonNode first = geocodes.get(0);
        GeocodeResult result = new GeocodeResult(text(first.path("adcode")), parsePoint(text(first.path("location"))));
        log.info("[AMap] 地理编码成功 city={} adcode={} location={}",
            city, result.adcode(), result.point() == null ? "-" : result.point().longitude() + "," + result.point().latitude());
        return result;
    }

    public List<MapPoi> searchPois(String city, String keywords, int limit) throws IOException, InterruptedException {
        validateReady();
        log.info("[AMap] POI 搜索 city={} keywords={} limit={}", city, keywords, limit);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("keywords", keywords);
        params.put("region", city);
        params.put("city_limit", "true");
        params.put("page_size", String.valueOf(limit));
        params.put("show_fields", "business,photos");
        params.put("output", "JSON");
        JsonNode root = get("/v5/place/text", params);
        JsonNode pois = root.path("pois");
        if (!pois.isArray()) {
            log.info("[AMap] POI 搜索无数组结果 city={} keywords={}", city, keywords);
            return List.of();
        }

        List<MapPoi> result = new ArrayList<>();
        for (JsonNode poi : pois) {
            if (result.size() >= limit) {
                break;
            }
            String name = text(poi.path("name"));
            if (name.isBlank()) {
                continue;
            }
            result.add(new MapPoi(
                name,
                text(poi.path("address")),
                parsePoint(text(poi.path("location"))),
                text(poi.path("type")),
                firstNonBlank(text(poi.path("business").path("rating")), text(poi.path("biz_ext").path("rating"))),
                text(poi.path("distance")),
                firstPhoto(poi.path("photos"))
            ));
        }
        log.info("[AMap] POI 搜索完成 city={} keywords={} resultCount={}", city, keywords, result.size());
        return result;
    }

    public List<MapWeatherForecast> weatherForecasts(String city, String adcode) throws IOException, InterruptedException {
        validateReady();
        String cityCode = firstNonBlank(adcode, city);
        if (cityCode.isBlank()) {
            log.info("[AMap] 天气查询跳过 city={} reason=cityCodeBlank", city);
            return List.of();
        }
        log.info("[AMap] 天气查询 city={} cityCode={}", city, cityCode);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("city", cityCode);
        params.put("extensions", "all");
        params.put("output", "JSON");
        JsonNode root = get("/v3/weather/weatherInfo", params);
        JsonNode forecasts = root.path("forecasts");
        if (!forecasts.isArray() || forecasts.isEmpty()) {
            log.info("[AMap] 天气查询无 forecasts city={}", city);
            return List.of();
        }
        JsonNode casts = forecasts.get(0).path("casts");
        if (!casts.isArray()) {
            log.info("[AMap] 天气查询无 casts city={}", city);
            return List.of();
        }

        List<MapWeatherForecast> result = new ArrayList<>();
        for (JsonNode cast : casts) {
            result.add(new MapWeatherForecast(
                text(cast.path("date")),
                city,
                text(cast.path("dayweather")),
                text(cast.path("nightweather")),
                parseInteger(text(cast.path("daytemp"))),
                parseInteger(text(cast.path("nighttemp"))),
                text(cast.path("daywind")),
                text(cast.path("daypower"))
            ));
        }
        log.info("[AMap] 天气查询完成 city={} forecastDays={}", city, result.size());
        return result;
    }

    private JsonNode get(String path, Map<String, String> params) throws IOException, InterruptedException {
        Map<String, String> finalParams = new LinkedHashMap<>();
        finalParams.put("key", apiKey());
        finalParams.putAll(params);
        URI requestUri = uri(path, finalParams);
        int maxAttempts = Math.max(1, rateLimitRetries + 1);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            waitForAmapSlot(path, attempt);
            HttpRequest request = HttpRequest.newBuilder(requestUri)
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
            long startedAt = System.currentTimeMillis();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            log.debug("[AMap] HTTP GET path={} status={} attempt={}/{} elapsedMs={} bodyLength={}",
                path,
                response.statusCode(),
                attempt,
                maxAttempts,
                System.currentTimeMillis() - startedAt,
                response.body() == null ? 0 : response.body().length());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("[AMap] HTTP 状态异常 path={} status={} attempt={}/{}",
                    path, response.statusCode(), attempt, maxAttempts);
                throw new BizException("高德 HTTP 状态异常 path=" + path + " status=" + response.statusCode());
            }
            JsonNode root = JsonUtils.getObjectMapper().readTree(response.body());
            if (!"0".equals(root.path("status").asText(""))) {
                return root;
            }
            String infocode = text(root.path("infocode"));
            String info = text(root.path("info"));
            if (isRateLimited(infocode) && attempt < maxAttempts) {
                log.warn("[AMap] 触发高德 QPS 限流，等待后重试 path={} infocode={} info={} attempt={}/{} retryDelayMs={}",
                    path, infocode, info, attempt, maxAttempts, safeRetryDelayMs());
                Thread.sleep(safeRetryDelayMs());
                continue;
            }
            log.warn("[AMap] 业务状态失败 path={} infocode={} info={} attempt={}/{}",
                path, infocode, info, attempt, maxAttempts);
            throw new BizException("高德接口返回失败 path=" + path
                + " infocode=" + infocode
                + " info=" + info);
        }
        throw new BizException("高德接口调用失败 path=" + path);
    }

    private void waitForAmapSlot(String path, int attempt) throws InterruptedException {
        long interval = Math.max(0, minIntervalMs);
        if (interval <= 0) {
            return;
        }
        synchronized (rateLimitMonitor) {
            long now = System.currentTimeMillis();
            long waitMs = lastRequestAt + interval - now;
            if (waitMs > 0) {
                log.debug("[AMap] 请求节流等待 path={} attempt={} waitMs={}", path, attempt, waitMs);
                Thread.sleep(waitMs);
                now = System.currentTimeMillis();
            }
            lastRequestAt = now;
        }
    }

    private boolean isRateLimited(String infocode) {
        return AMAP_RATE_LIMIT_INFOCODE.equals(infocode);
    }

    private long safeRetryDelayMs() {
        return Math.max(0, rateLimitRetryDelayMs);
    }

    private URI uri(String path, Map<String, String> params) {
        String query = params.entrySet().stream()
            .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
            .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
            .collect(Collectors.joining("&"));
        String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(normalizedBaseUrl + path + "?" + query);
    }

    private String apiKey() {
        return runtimeSettingsService.stringValue(TripstarSettingKeys.AMAP_WEB_KEY).orElse("");
    }

    public void validateReady() {
        String apiKey = apiKey();
        if (!enabled) {
            log.warn("[AMap] 高德地图未启用 enabled={}", enabled);
            throw new BizException("高德地图未启用，请检查 tripstar.map.amap.enabled 配置。");
        }
        if (apiKey.isBlank()) {
            log.warn("[AMap] 高德地图 Web Service Key 未配置");
            throw new BizException("高德地图 Web Service Key 未配置，请先在设置页填写“高德地图 Web Service Key”。");
        }
    }

    private MapPoint parsePoint(String location) {
        if (location == null || location.isBlank() || !location.contains(",")) {
            return null;
        }
        String[] parts = location.split(",");
        if (parts.length < 2) {
            return null;
        }
        try {
            return new MapPoint(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String firstPhoto(JsonNode photos) {
        if (photos == null || !photos.isArray() || photos.isEmpty()) {
            return "";
        }
        return text(photos.get(0).path("url"));
    }

    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode item : node) {
                String value = text(item);
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
            return String.join("、", values);
        }
        return node.asText("");
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second == null ? "" : second;
    }

    private Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record GeocodeResult(String adcode, MapPoint point) {
    }
}
