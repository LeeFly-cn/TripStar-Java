package com.zkry.common.json.handler;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.annotation.JacksonStdImpl;
import tools.jackson.databind.ser.jdk.NumberSerializer;

/**
 * 大数字序列化器。
 *
 * <p>为什么需要这个类：
 *
 * <p>Java 后端常用 Long 作为主键，雪花 ID 这类值通常会超过 JavaScript 的安全整数范围。
 * 如果后端直接把超大 Long 输出成 JSON 数字，前端用 Number 接收时可能丢精度，
 * 例如 ID 最后几位被四舍五入，最终导致详情查询、编辑、删除等接口传回错误 ID。
 *
 * <p>这个序列化器的策略是：
 *
 * <p>1. 在 JavaScript 安全整数范围内的数字，仍按 JSON number 输出，方便前端做普通数值计算。
 *
 * <p>2. 超出安全整数范围的数字，按 JSON string 输出，保证 ID、金额等关键值不会丢精度。
 */
@JacksonStdImpl
public class BigNumberSerializer extends NumberSerializer {

    private static final long serialVersionUID = 1L;

    /**
     * JavaScript Number 能安全表示的最大整数：2^53 - 1。
     */
    private static final long MAX_SAFE_INTEGER = 9007199254740991L;

    /**
     * JavaScript Number 能安全表示的最小整数：-(2^53 - 1)。
     */
    private static final long MIN_SAFE_INTEGER = -9007199254740991L;

    /**
     * 全局单例。
     *
     * <p>序列化器本身没有可变状态，复用一个实例即可，避免每次注册模块时重复创建对象。
     */
    public static final BigNumberSerializer INSTANCE = new BigNumberSerializer(Number.class);

    public BigNumberSerializer(Class<? extends Number> rawType) {
        super(rawType);
    }

    /**
     * 序列化数字。
     *
     * <p>小数字保持原生 JSON number；大数字转 JSON string，保护前端精度。
     */
    @Override
    public void serialize(Number value, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
        long longValue = value.longValue();
        if (longValue > MIN_SAFE_INTEGER && longValue < MAX_SAFE_INTEGER) {
            super.serialize(value, gen, ctxt);
            return;
        }
        gen.writeString(value.toString());
    }
}
