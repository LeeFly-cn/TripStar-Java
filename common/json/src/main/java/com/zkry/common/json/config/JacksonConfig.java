package com.zkry.common.json.config;

import com.zkry.common.json.handler.BigNumberSerializer;
import com.zkry.common.json.utils.SpringContextHolder;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ext.javatime.deser.LocalDateDeserializer;
import tools.jackson.databind.ext.javatime.deser.LocalDateTimeDeserializer;
import tools.jackson.databind.ext.javatime.deser.LocalTimeDeserializer;
import tools.jackson.databind.ext.javatime.ser.LocalDateSerializer;
import tools.jackson.databind.ext.javatime.ser.LocalDateTimeSerializer;
import tools.jackson.databind.ext.javatime.ser.LocalTimeSerializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * Jackson 全局 JSON 配置。
 *
 * <p>这个类集中处理项目里最容易出现不一致的 JSON 规则：
 *
 * <p>1. Java 8+ 时间类型格式，例如 {@link LocalDateTime}、{@link LocalDate}、{@link LocalTime}。
 *
 * <p>2. 大数字序列化策略，例如 Long、BigInteger、BigDecimal。
 *
 * <p>3. Spring MVC 返回 JSON、工具类手动序列化、Redis JSON 序列化尽量复用同一套规则。
 *
 * <p>把这些规则放在 common-json 模块里，可以避免每个业务模块各自配置 ObjectMapper，
 * 后续如果要调整时间格式或大数字策略，只需要改这一处。
 */
@Configuration
public class JacksonConfig {

    /**
     * 日期时间格式。
     *
     * <p>用于 LocalDateTime，例如：2026-06-12 09:30:00。
     */
    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /**
     * 日期格式。
     *
     * <p>用于 LocalDate，例如：2026-06-12。
     */
    public static final String DATE_PATTERN = "yyyy-MM-dd";

    /**
     * 时间格式。
     *
     * <p>用于 LocalTime，例如：09:30:00。
     */
    public static final String TIME_PATTERN = "HH:mm:ss";

    /**
     * DateTimeFormatter 是线程安全的，可以作为静态常量复用。
     */
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);

    /**
     * DateTimeFormatter 是线程安全的，可以作为静态常量复用。
     */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern(DATE_PATTERN);

    /**
     * DateTimeFormatter 是线程安全的，可以作为静态常量复用。
     */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern(TIME_PATTERN);

    /**
     * 注册项目通用的 Jackson 模块。
     *
     * <p>Spring Boot 会自动把容器里的 {@link SimpleModule} 注册到应用的 ObjectMapper 中。
     * 这样 Controller 返回对象时，会自动使用这里定义的时间格式和大数字序列化规则。
     */
    @Bean
    public SimpleModule commonJsonModule() {
        return buildCommonJsonModule();
    }

    /**
     * 注册 Spring 上下文持有器。
     *
     * <p>{@code JsonUtils} 是静态工具类，不能通过构造器注入 ObjectMapper。
     * 这里注册一个轻量的 SpringContextHolder，让工具类优先拿 Spring 容器里的 ObjectMapper，
     * 保证手动 JSON 转换和 Web 层 JSON 转换使用同一套配置。
     */
    @Bean
    public SpringContextHolder springContextHolder() {
        return new SpringContextHolder();
    }

    /**
     * 构建项目通用 JSON 模块。
     *
     * <p>这里做成 static 方法，是为了让非 Spring 管理的场景也能复用同一套配置。
     * 例如 RedisConfig 自己创建 Redis JSON 序列化器时，就可以调用这个方法。
     *
     * @return 项目统一的 Jackson SimpleModule
     */
    public static SimpleModule buildCommonJsonModule() {
        SimpleModule module = new SimpleModule("common-json");
        // Long 和 long 可能超过前端 JavaScript 的安全整数范围，交给自定义序列化器判断是否转字符串。
        module.addSerializer(Long.class, BigNumberSerializer.INSTANCE);
        module.addSerializer(Long.TYPE, BigNumberSerializer.INSTANCE);
        // BigInteger 通常也可能超过前端安全整数范围，同样使用大数字序列化策略。
        module.addSerializer(BigInteger.class, BigNumberSerializer.INSTANCE);
        // BigDecimal 常用于金额、余额、积分等精确数字，转字符串可以避免前端浮点精度误差。
        module.addSerializer(BigDecimal.class, ToStringSerializer.instance);
        // 明确指定 Java 时间类型格式，避免默认输出数组或 ISO 字符串导致前后端约定不一致。
        module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME_FORMATTER));
        module.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(DATE_TIME_FORMATTER));
        module.addSerializer(LocalDate.class, new LocalDateSerializer(DATE_FORMATTER));
        module.addDeserializer(LocalDate.class, new LocalDateDeserializer(DATE_FORMATTER));
        module.addSerializer(LocalTime.class, new LocalTimeSerializer(TIME_FORMATTER));
        module.addDeserializer(LocalTime.class, new LocalTimeDeserializer(TIME_FORMATTER));
        return module;
    }

    /**
     * 定制 Spring Boot 自动创建的 JsonMapper。
     *
     * <p>这里使用 JVM 默认时区。项目部署到服务器后，需要确保服务器时区符合预期；
     * 如果未来要统一成固定时区，例如 Asia/Shanghai，也可以在这里集中调整。
     */
    @Bean
    public JsonMapperBuilderCustomizer commonJsonMapperBuilderCustomizer() {
        return builder -> builder.defaultTimeZone(TimeZone.getDefault());
    }
}
