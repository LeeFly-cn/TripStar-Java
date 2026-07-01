package com.zkry.demo.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.zkry.common.mybatis.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Demo 示例实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("demo")
public class Demo extends BaseEntity {

    /**
     * 示例名称。
     */
    private String name;

    /**
     * 示例描述。
     */
    private String description;
}
