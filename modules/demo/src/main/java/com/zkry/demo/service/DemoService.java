package com.zkry.demo.service;

import com.zkry.demo.domain.Demo;

/**
 * Demo 业务服务接口。
 *
 * <p>业务模块对 app 暴露接口，具体实现放在 service.impl 中。
 */
public interface DemoService {

    /**
     * 获取一条最小示例数据。
     */
    Demo info();
}
