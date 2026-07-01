package com.zkry.api.user;

import com.zkry.common.core.domain.R;
import com.zkry.demo.domain.Demo;
import com.zkry.demo.service.DemoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo 用户端接口。
 *
 * <p>Controller 统一放在 app 模块，业务逻辑委托给 modules/demo。
 */
@RestController
@RequestMapping("/api/public/demo")
public class DemoController {

    private final DemoService demoService;

    public DemoController(DemoService demoService) {
        this.demoService = demoService;
    }

    /**
     * 获取 Demo 示例信息。
     */
    @GetMapping
    public R<Demo> info() {
        return R.ok(demoService.info());
    }
}
