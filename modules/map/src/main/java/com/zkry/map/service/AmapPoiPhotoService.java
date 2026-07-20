package com.zkry.map.service;

import com.zkry.common.core.exception.BizException;
import com.zkry.map.dto.MapPoi;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 根据城市和景点名称查询高德 POI 图片。
 *
 * <p>该服务用于结果页图片展示，不参与 Planner 推理。前端虽然已经限制并发，但多个用户
 * 同时打开结果页时请求仍可能叠加，因此后端再使用 {@link Semaphore} 限制全局并发，并用
 * 内存缓存减少同一景点的重复查询。底层 {@link AmapMapContextService} 仍会执行统一的
 * 高德请求间隔控制和限流重试。
 */
@Service
public class AmapPoiPhotoService {

    private static final Logger log = LoggerFactory.getLogger(AmapPoiPhotoService.class);
    private static final int SEARCH_LIMIT = 5;
    private static final int MAX_CACHE_ENTRIES = 1000;
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;

    private final AmapMapContextService amapMapContextService;
    private final HttpClient imageHttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    private final Semaphore requestSlots;
    private final long cacheTtlMs;
    private final long acquireTimeoutMs;
    private final Map<String, CachedPhoto> cache = new ConcurrentHashMap<>();

    public AmapPoiPhotoService(
        AmapMapContextService amapMapContextService,
        @Value("${tripstar.map.amap.photo-max-concurrency:3}") int maxConcurrency,
        @Value("${tripstar.map.amap.photo-cache-hours:6}") long cacheHours,
        @Value("${tripstar.map.amap.photo-acquire-timeout-ms:5000}") long acquireTimeoutMs
    ) {
        this.amapMapContextService = amapMapContextService;
        this.requestSlots = new Semaphore(Math.max(1, maxConcurrency), true);
        this.cacheTtlMs = TimeUnit.HOURS.toMillis(Math.max(1L, cacheHours));
        this.acquireTimeoutMs = Math.max(1000L, acquireTimeoutMs);
        log.info("[AMap-Photo] 图片查询配置 maxConcurrency={} cacheHours={} acquireTimeoutMs={}",
            Math.max(1, maxConcurrency), Math.max(1L, cacheHours), this.acquireTimeoutMs);
    }

    /**
     * 查询一张最匹配的景点图片。
     *
     * @return 高德图片 URL；没有带图的 POI 时返回空字符串
     */
    public String photo(String city, String name) {
        String safeCity = required(city, "景点图片查询城市不能为空。");
        String safeName = required(name, "景点图片查询名称不能为空。");
        String cacheKey = normalize(safeCity) + "|" + normalize(safeName);

        CachedPhoto cached = validCache(cacheKey);
        if (cached != null) {
            log.debug("[AMap-Photo] 命中图片缓存 city={} name={} found={}",
                safeCity, safeName, !cached.photoUrl().isBlank());
            return cached.photoUrl();
        }

        boolean acquired = false;
        long startedAt = System.currentTimeMillis();
        try {
            // 限制的是所有用户共享的外部高德请求并发，而不是单个页面的 worker 数量。
            acquired = requestSlots.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new BizException("高德景点图片查询繁忙，请稍后重试。");
            }

            // 等待信号量期间可能已有其他请求写入缓存，进入槽位后再检查一次。
            cached = validCache(cacheKey);
            if (cached != null) {
                return cached.photoUrl();
            }

            log.info("[AMap-Photo] 开始查询景点图片 city={} name={} waitingRequests={}",
                safeCity, safeName, requestSlots.getQueueLength());
            List<MapPoi> pois = amapMapContextService.searchPois(safeCity, safeName, SEARCH_LIMIT);
            String photoUrl = selectPhoto(safeName, pois);
            putCache(cacheKey, photoUrl);
            log.info("[AMap-Photo] 景点图片查询完成 city={} name={} candidates={} found={} elapsedMs={}",
                safeCity, safeName, pois.size(), !photoUrl.isBlank(), System.currentTimeMillis() - startedAt);
            return photoUrl;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BizException("高德景点图片查询被中断。");
        } catch (IOException ex) {
            throw new BizException("高德景点图片查询失败：" + ex.getMessage());
        } finally {
            if (acquired) {
                requestSlots.release();
            }
        }
    }

    /**
     * 下载高德 POI 图片，供浏览器通过 Java 服务同源读取。
     *
     * <p>高德图片可以直接显示在 img 标签中，但通常没有 html2canvas 所需的跨域响应头。
     * 这里仅允许高德和 AutoNavi 官方图片域名，避免把接口变成任意 URL 代理。
     */
    public PhotoContent proxy(String photoUrl) {
        URI uri = allowedPhotoUri(photoUrl);
        long startedAt = System.currentTimeMillis();
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "image/*")
                .header("User-Agent", "Mozilla/5.0 TripStar-Java")
                .GET()
                .build();
            HttpResponse<byte[]> response = imageHttpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException("高德景点图片下载失败，HTTP " + response.statusCode());
            }

            byte[] bytes = response.body();
            if (bytes == null || bytes.length == 0) {
                throw new BizException("高德景点图片内容为空。");
            }
            if (bytes.length > MAX_IMAGE_BYTES) {
                throw new BizException("高德景点图片超过 8MB，拒绝代理。");
            }

            String contentType = response.headers()
                .firstValue("Content-Type")
                .orElse("image/jpeg")
                .split(";", 2)[0]
                .trim();
            if (!contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
                throw new BizException("高德景点图片响应类型无效：" + contentType);
            }

            log.info("[AMap-Photo] 图片代理完成 host={} bytes={} elapsedMs={}",
                uri.getHost(), bytes.length, System.currentTimeMillis() - startedAt);
            return new PhotoContent(bytes, contentType);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BizException("高德景点图片下载被中断。");
        } catch (IOException ex) {
            throw new BizException("高德景点图片下载失败：" + ex.getMessage());
        }
    }

    /** 优先选择同名 POI，其次选择名称互相包含的带图候选。 */
    private String selectPhoto(String attractionName, List<MapPoi> pois) {
        String normalizedName = normalize(attractionName);
        return pois.stream()
            .filter(poi -> poi != null && poi.photoUrl() != null && !poi.photoUrl().isBlank())
            .min(Comparator.comparingInt(poi -> matchScore(normalizedName, normalize(poi.name()))))
            .map(MapPoi::photoUrl)
            .orElse("");
    }

    private int matchScore(String attractionName, String poiName) {
        if (attractionName.equals(poiName)) {
            return 0;
        }
        if (attractionName.contains(poiName) || poiName.contains(attractionName)) {
            return 1;
        }
        return 2;
    }

    /** 返回未过期缓存；过期数据在读取时立即移除。 */
    private CachedPhoto validCache(String key) {
        CachedPhoto cached = cache.get(key);
        if (cached == null) {
            return null;
        }
        if (cached.expiresAt() <= System.currentTimeMillis()) {
            cache.remove(key, cached);
            return null;
        }
        return cached;
    }

    private void putCache(String key, String photoUrl) {
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            long now = System.currentTimeMillis();
            cache.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
            if (cache.size() >= MAX_CACHE_ENTRIES) {
                log.info("[AMap-Photo] 图片缓存达到上限，清空旧缓存 size={}", cache.size());
                cache.clear();
            }
        }
        cache.put(key, new CachedPhoto(photoUrl == null ? "" : photoUrl, System.currentTimeMillis() + cacheTtlMs));
    }

    private String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BizException(message);
        }
        return value.trim();
    }

    private URI allowedPhotoUri(String photoUrl) {
        String safeUrl = required(photoUrl, "高德景点图片 URL 不能为空。");
        try {
            URI uri = new URI(safeUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || host == null) {
                throw new BizException("高德景点图片 URL 无效。");
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            boolean allowed = normalizedHost.equals("amap.com")
                || normalizedHost.endsWith(".amap.com")
                || normalizedHost.equals("autonavi.com")
                || normalizedHost.endsWith(".autonavi.com");
            if (!allowed) {
                throw new BizException("只允许代理高德官方图片域名。");
            }
            return uri;
        } catch (URISyntaxException ex) {
            throw new BizException("高德景点图片 URL 无效。");
        }
    }

    private String normalize(String value) {
        return value == null
            ? ""
            : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private record CachedPhoto(String photoUrl, long expiresAt) {
    }

    public record PhotoContent(byte[] bytes, String contentType) {
    }
}
