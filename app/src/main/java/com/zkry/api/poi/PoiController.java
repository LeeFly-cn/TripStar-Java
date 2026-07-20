package com.zkry.api.poi;

import com.zkry.content.service.TravelContentService;
import com.zkry.map.service.AmapPoiPhotoService;
import com.zkry.trip.dto.PoiPhotoResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/poi")
public class PoiController {

    private static final Logger log = LoggerFactory.getLogger(PoiController.class);

    private final TravelContentService travelContentService;
    private final AmapPoiPhotoService amapPoiPhotoService;

    public PoiController(
        TravelContentService travelContentService,
        AmapPoiPhotoService amapPoiPhotoService
    ) {
        this.travelContentService = travelContentService;
        this.amapPoiPhotoService = amapPoiPhotoService;
    }

    @GetMapping("/photo")
    public PoiPhotoResponse photo(@RequestParam String name, @RequestParam(required = false) String city) {
        long startedAt = System.currentTimeMillis();
        String keyword = (city == null || city.isBlank() ? name : city + " " + name) + " 风景";
        log.info("[POI] 收到景点图片请求 name={} city={} keyword={}", name, safe(city), keyword);
        String photoUrl = travelContentService.photo(keyword);
        String message = photoUrl.isBlank()
            ? "未从小红书找到可用图片。"
            : "获取图片成功";
        log.info("[POI] 景点图片请求完成 name={} city={} found={} elapsedMs={}",
            name, safe(city), !photoUrl.isBlank(), System.currentTimeMillis() - startedAt);
        return new PoiPhotoResponse(
            !photoUrl.isBlank(),
            message,
            new PoiPhotoResponse.PoiPhotoData(name, photoUrl)
        );
    }

    /**
     * 指定笔记模式使用的高德图片接口。
     *
     * <p>与原 {@code /photo} 完全分离，避免自主规划在没有显式选择时改变图片数据源。
     */
    @GetMapping("/photo/amap")
    public PoiPhotoResponse amapPhoto(@RequestParam String name, @RequestParam String city) {
        long startedAt = System.currentTimeMillis();
        log.info("[POI-AMAP] 收到景点图片请求 name={} city={}", name, city);
        String photoUrl = amapPoiPhotoService.photo(city, name);
        String message = photoUrl.isBlank()
            ? "未从高德 POI 找到可用图片。"
            : "获取高德 POI 图片成功";
        log.info("[POI-AMAP] 景点图片请求完成 name={} city={} found={} elapsedMs={}",
            name, city, !photoUrl.isBlank(), System.currentTimeMillis() - startedAt);
        return new PoiPhotoResponse(
            !photoUrl.isBlank(),
            message,
            new PoiPhotoResponse.PoiPhotoData(name, photoUrl)
        );
    }

    /** 浏览器导出攻略时通过该接口读取高德图片，避免远程图片污染 Canvas。 */
    @GetMapping("/photo/amap/proxy")
    public ResponseEntity<byte[]> amapPhotoProxy(@RequestParam String url) {
        AmapPoiPhotoService.PhotoContent photo = amapPoiPhotoService.proxy(url);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(photo.contentType()))
            .cacheControl(CacheControl.maxAge(Duration.ofHours(6)).cachePublic())
            .body(photo.bytes());
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
