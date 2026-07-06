package com.zkry.demo.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zkry.demo.domain.Demo;
import com.zkry.demo.mapper.DemoMapper;
import com.zkry.demo.service.DemoService;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

/**
 * Demo 业务服务实现。
 */
@Service
public class DemoServiceImpl extends ServiceImpl<DemoMapper, Demo> implements DemoService {

    /**
     * 返回一条内存示例数据，用来展示 app 调用 modules/demo 的分层关系。
     */
    @Override
    public Demo info() {
        Demo demo = new Demo();
        demo.setId(1L);
        demo.setName("demo");
        demo.setDescription("这是来自 modules/demo 业务层的数据");
        demo.setCreateTime(LocalDateTime.now());
        return demo;
    }
}
