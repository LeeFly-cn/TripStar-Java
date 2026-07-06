package com.zkry.common.redis.config;

import com.zkry.common.json.config.JacksonConfig;
import com.zkry.common.redis.util.RedisUtils;
import java.util.TimeZone;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring Data Redis 基础配置。
 *
 * <p>这个模块定位为“基础缓存模块”，只提供 Spring 官方 Redis 能力。
 * 分布式锁、限流器、延迟队列等高级能力暂时不放在这里，避免脚手架一开始就引入过重依赖。
 *
 * <p>连接地址、端口、密码、database 等配置全部使用 Spring Boot 官方的
 * {@code spring.data.redis} 配置项，避免脚手架同时存在多套 Redis 配置源。
 *
 * <p>这里额外配置一个 {@code RedisTemplate<String, Object>}：
 *
 * <p>1. key 使用字符串序列化，方便在 redis-cli 或图形化客户端里查看。
 *
 * <p>2. value 使用 Spring Data Redis 的 Jackson JSON 序列化器，方便缓存普通 Java 对象。
 *
 * <p>3. JSON 时间格式复用 common-json 里的配置，保持 HTTP 返回和 Redis 缓存尽量一致。
 */
@Configuration
public class RedisConfig {

    /**
     * 配置项目默认的对象 RedisTemplate。
     *
     * <p>Spring Boot 默认会提供一个 RedisTemplate，但默认序列化方式通常不适合业务直接使用。
     * 这里显式指定 key 和 value 的序列化策略，让缓存数据在 Redis 客户端里可读，也方便跨模块复用。
     *
     * <p>{@link ConditionalOnMissingBean} 的作用是保留扩展空间：
     * 如果某个具体项目需要完全自定义 RedisTemplate，只要自己声明同名 Bean，就会覆盖这里的默认配置。
     *
     * @param connectionFactory Spring Boot 根据 spring.data.redis 自动创建的连接工厂
     * @return 项目默认对象 RedisTemplate
     */
    @Bean
    @ConditionalOnMissingBean(name = "redisTemplate")
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisSerializer<String> stringSerializer = RedisSerializer.string();
        GenericJacksonJsonRedisSerializer jsonSerializer = redisJsonSerializer();

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        // Redis key 使用字符串序列化，避免出现 JDK 序列化后的不可读二进制 key。
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        // Redis value 使用 JSON 序列化，方便缓存普通 Java 对象，也便于排查线上缓存内容。
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        // 手动创建 RedisTemplate 后需要初始化内部配置。
        template.afterPropertiesSet();
        return template;
    }

    /**
     * 注册 Redis 工具类。
     *
     * <p>RedisUtils 不使用 @Component，是为了让 common-redis 更像一个基础配置模块：
     * Bean 的创建入口集中在 RedisConfig，后续替换实现或扩展条件装配更直观。
     *
     * @param redisTemplate 对象缓存模板
     * @param stringRedisTemplate Spring Boot 默认提供的字符串模板
     * @return Redis 常用操作工具类
     */
    @Bean
    @ConditionalOnMissingBean
    public RedisUtils redisUtils(RedisTemplate<String, Object> redisTemplate,
                                 StringRedisTemplate stringRedisTemplate) {
        return new RedisUtils(redisTemplate, stringRedisTemplate);
    }

    /**
     * Redis 专用 JSON 序列化器。
     *
     * <p>Spring Data Redis 4 的 GenericJacksonJsonRedisSerializer 使用 Jackson 3，
     * 可以直接复用 common-json 里的 JavaTimeModule 配置。
     */
    private GenericJacksonJsonRedisSerializer redisJsonSerializer() {
        ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(JacksonConfig.buildCommonJsonModule())
            .defaultTimeZone(TimeZone.getDefault())
            .build();
        return new GenericJacksonJsonRedisSerializer(objectMapper);
    }
}
