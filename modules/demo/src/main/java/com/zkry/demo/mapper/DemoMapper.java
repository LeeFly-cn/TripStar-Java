package com.zkry.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zkry.demo.domain.Demo;
import org.apache.ibatis.annotations.Mapper;

/**
 * Demo Mapper 接口。
 */
@Mapper
public interface DemoMapper extends BaseMapper<Demo> {
}
