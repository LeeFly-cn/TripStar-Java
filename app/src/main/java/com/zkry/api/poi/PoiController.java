package com.zkry.api.poi;

import com.zkry.trip.dto.PoiPhotoResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/poi")
public class PoiController {

    @GetMapping("/photo")
    public PoiPhotoResponse photo(@RequestParam String name, @RequestParam(required = false) String city) {
        return new PoiPhotoResponse(
            true,
            "Java 后端 mock 图片接口，当前返回空图，前端会使用默认占位图。",
            new PoiPhotoResponse.PoiPhotoData(name, "")
        );
    }
}
