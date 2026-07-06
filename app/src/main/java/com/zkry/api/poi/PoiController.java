package com.zkry.api.poi;

import com.zkry.content.service.TravelContentService;
import com.zkry.trip.dto.PoiPhotoResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/poi")
public class PoiController {

    private static final Logger log = LoggerFactory.getLogger(PoiController.class);

    private final TravelContentService travelContentService;

    public PoiController(TravelContentService travelContentService) {
        this.travelContentService = travelContentService;
    }

    @GetMapping("/photo")
    public PoiPhotoResponse photo(@RequestParam String name, @RequestParam(required = false) String city) {
        long startedAt = System.currentTimeMillis();
        String keyword = (city == null || city.isBlank() ? name : city + " " + name) + " 风景";
        log.info("[POI] 收到景点图片请求 name={} city={} keyword={}", name, safe(city), keyword);
        String photoUrl = travelContentService.photo(keyword);
        String message = photoUrl.isBlank()
            ? "未从小红书找到图片，前端会使用默认占位图。"
            : "获取图片成功";
        log.info("[POI] 景点图片请求完成 name={} city={} found={} elapsedMs={}",
            name, safe(city), !photoUrl.isBlank(), System.currentTimeMillis() - startedAt);
        return new PoiPhotoResponse(
            !photoUrl.isBlank(),
            message,
            new PoiPhotoResponse.PoiPhotoData(name, photoUrl)
        );
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
