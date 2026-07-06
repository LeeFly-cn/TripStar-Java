package com.zkry.common.redis.util;

import com.zkry.common.json.utils.JsonUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 常用操作封装。
 *
 * <p>这个工具类基于 Spring Boot 官方的 Spring Data Redis，只保留脚手架最常用的基础缓存能力。
 *
 * <p>设计约定：
 *
 * <p>1. 普通对象、List、Set、Hash 使用 {@link RedisTemplate}，value 默认走 JSON 序列化。
 *
 * <p>2. 字符串和计数器使用 {@link StringRedisTemplate}，避免计数 key 被 JSON 序列化后无法执行 INCR/DECR。
 *
 * <p>3. 不在基础脚手架里封装分布式锁、限流器、延迟队列等高级能力；后续业务确实需要时，可以单独增加扩展模块。
 *
 * <p>4. key 建议使用业务前缀，例如 {@code user:token:用户ID}、{@code credit:sign-in:用户ID}。
 */
public class RedisUtils {

    /**
     * 对象缓存模板。
     *
     * <p>用于保存 Java 对象、List、Set、Hash 等结构，value 走 JSON 序列化。
     */
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 字符串缓存模板。
     *
     * <p>用于保存纯字符串和计数器。计数器必须使用字符串序列化，否则 Redis INCR/DECR 无法正确处理 JSON 值。
     */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 构造 Redis 工具类。
     *
     * <p>由 RedisConfig 统一创建 Bean，业务代码直接注入 RedisUtils 即可。
     */
    public RedisUtils(RedisTemplate<String, Object> redisTemplate,
                      StringRedisTemplate stringRedisTemplate) {
        this.redisTemplate = redisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 获取对象 RedisTemplate。
     *
     * <p>当工具类没有覆盖某个低频操作时，业务代码可以直接使用它，但不要在业务里重新创建 RedisTemplate。
     */
    public RedisTemplate<String, Object> getRedisTemplate() {
        return redisTemplate;
    }

    /**
     * 获取字符串 RedisTemplate。
     *
     * <p>适合操作纯字符串、验证码、计数器等不需要 JSON 序列化的数据。
     */
    public StringRedisTemplate getStringRedisTemplate() {
        return stringRedisTemplate;
    }

    /**
     * 缓存普通对象，不设置过期时间。
     *
     * <p>适合系统配置、枚举字典等长期有效数据。用户状态、验证码、临时任务结果建议使用带过期时间的重载方法。
     */
    public void setObject(String key, Object value) {
        redisTemplate.opsForValue().set(key, value);
    }

    /**
     * 缓存普通对象，并设置过期时间。
     *
     * <p>这是业务中最常用的对象缓存方式，例如登录态、任务临时状态、接口幂等标记等。
     */
    public void setObject(String key, Object value, Duration timeout) {
        redisTemplate.opsForValue().set(key, value, Objects.requireNonNull(timeout, "timeout must not be null"));
    }

    /**
     * key 不存在时写入，常用于防重复提交、第三方回调幂等处理。
     *
     * @return true 表示写入成功；false 表示 key 已存在
     */
    public boolean setIfAbsent(String key, Object value, Duration timeout) {
        Boolean result = redisTemplate.opsForValue()
            .setIfAbsent(key, value, Objects.requireNonNull(timeout, "timeout must not be null"));
        return Boolean.TRUE.equals(result);
    }

    /**
     * key 已存在时写入，避免在缓存缺失时误创建新 key。
     *
     * @return true 表示写入成功；false 表示 key 不存在
     */
    public boolean setIfExists(String key, Object value, Duration timeout) {
        Boolean result = redisTemplate.opsForValue()
            .setIfPresent(key, value, Objects.requireNonNull(timeout, "timeout must not be null"));
        return Boolean.TRUE.equals(result);
    }

    /**
     * 获取普通对象缓存。
     *
     * <p>如果 key 不存在，返回 null。调用方知道具体类型时，优先使用带 Class 参数的重载方法。
     */
    public Object getObject(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * 获取普通对象缓存，并转换为指定类型。
     *
     * <p>Redis JSON 反序列化后可能得到 Map/List 等中间结构，这里统一通过 common-json 再转换一次，调用侧更稳定。
     */
    public <T> T getObject(String key, Class<T> clazz) {
        return JsonUtils.convertValue(getObject(key), clazz);
    }

    /**
     * 缓存字符串，不设置过期时间。
     */
    public void setString(String key, String value) {
        stringRedisTemplate.opsForValue().set(key, value);
    }

    /**
     * 缓存字符串，并设置过期时间。
     */
    public void setString(String key, String value, Duration timeout) {
        stringRedisTemplate.opsForValue().set(key, value, Objects.requireNonNull(timeout, "timeout must not be null"));
    }

    /**
     * 获取字符串缓存。
     */
    public String getString(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    /**
     * 删除单个 key。
     *
     * @return true 表示 key 存在且删除成功；false 表示 key 原本不存在
     */
    public boolean delete(String key) {
        return Boolean.TRUE.equals(redisTemplate.delete(key));
    }

    /**
     * 批量删除 key。
     *
     * <p>空集合直接返回 0，避免无意义调用 Redis。
     *
     * @return 实际删除的 key 数量
     */
    public long delete(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        Long count = redisTemplate.delete(keys);
        return count == null ? 0 : count;
    }

    /**
     * 判断 key 是否存在。
     */
    public boolean hasKey(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /**
     * 设置 key 过期时间。
     *
     * <p>常用于先写入集合/Hash，再统一补 TTL 的场景。
     */
    public boolean expire(String key, Duration timeout) {
        Boolean result = redisTemplate.expire(key, Objects.requireNonNull(timeout, "timeout must not be null"));
        return Boolean.TRUE.equals(result);
    }

    /**
     * 获取 key 剩余过期时间，单位毫秒。
     *
     * <p>遵循 Redis 语义：-2 表示 key 不存在，-1 表示 key 存在但没有设置过期时间。
     */
    public long getTimeToLive(String key) {
        Long ttl = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
        return ttl == null ? -2 : ttl;
    }

    /**
     * 覆盖写入 List 缓存。
     *
     * <p>空集合会删除旧缓存，避免旧列表残留。
     *
     * @return 写入的元素数量
     */
    public long setList(String key, Collection<?> values) {
        delete(key);
        if (values == null || values.isEmpty()) {
            return 0;
        }
        Long count = redisTemplate.opsForList().rightPushAll(key, new ArrayList<>(values));
        return count == null ? 0 : count;
    }

    /**
     * 向 List 尾部追加一个元素。
     *
     * @return 追加后的列表长度
     */
    public long rightPush(String key, Object value) {
        Long count = redisTemplate.opsForList().rightPush(key, value);
        return count == null ? 0 : count;
    }

    /**
     * 读取 List 指定范围。
     *
     * <p>start/end 使用 Redis List 下标语义，例如 0 到 -1 表示完整列表。
     */
    public List<Object> getListRange(String key, long start, long end) {
        List<Object> values = redisTemplate.opsForList().range(key, start, end);
        return values == null ? Collections.emptyList() : values;
    }

    /**
     * 读取 List 指定范围，并转换元素类型。
     */
    public <T> List<T> getListRange(String key, long start, long end, Class<T> clazz) {
        return getListRange(key, start, end).stream()
            .map(value -> JsonUtils.convertValue(value, clazz))
            .collect(Collectors.toList());
    }

    /**
     * 向 Set 添加元素。
     *
     * @return 实际新增的元素数量；元素已存在时不会重复新增
     */
    public long addSet(String key, Object... values) {
        Long count = redisTemplate.opsForSet().add(key, values);
        return count == null ? 0 : count;
    }

    /**
     * 获取完整 Set。
     *
     * <p>只适合元素数量可控的小集合；大集合不要一次性全部拉回应用内存。
     */
    public Set<Object> getSet(String key) {
        Set<Object> values = redisTemplate.opsForSet().members(key);
        return values == null ? Collections.emptySet() : values;
    }

    /**
     * 获取完整 Set，并转换元素类型。
     */
    public <T> Set<T> getSet(String key, Class<T> clazz) {
        return getSet(key).stream()
            .map(value -> JsonUtils.convertValue(value, clazz))
            .collect(Collectors.toSet());
    }

    /**
     * 写入 Hash 单个字段。
     */
    public void putHash(String key, String hashKey, Object value) {
        redisTemplate.opsForHash().put(key, hashKey, value);
    }

    /**
     * 批量写入 Hash 字段。
     */
    public void putHashAll(String key, Map<String, ?> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        redisTemplate.opsForHash().putAll(key, values);
    }

    /**
     * 获取 Hash 单个字段。
     */
    public Object getHash(String key, String hashKey) {
        return redisTemplate.opsForHash().get(key, hashKey);
    }

    /**
     * 获取 Hash 单个字段，并转换为指定类型。
     */
    public <T> T getHash(String key, String hashKey, Class<T> clazz) {
        return JsonUtils.convertValue(getHash(key, hashKey), clazz);
    }

    /**
     * 获取完整 Hash。
     *
     * <p>只建议用于字段数量可控的小 Hash。
     */
    public Map<Object, Object> getHashAll(String key) {
        return redisTemplate.opsForHash().entries(key);
    }

    /**
     * 删除 Hash 字段。
     *
     * @return 实际删除的字段数量
     */
    public long deleteHash(String key, Object... hashKeys) {
        Long count = redisTemplate.opsForHash().delete(key, hashKeys);
        return count == null ? 0 : count;
    }

    /**
     * 字符串计数器原子递增。
     *
     * <p>计数器 key 不要和对象缓存 key 混用，否则可能因为序列化格式不同导致 Redis INCR 失败。
     */
    public long increment(String key) {
        return increment(key, 1);
    }

    /**
     * 字符串计数器按指定步长原子递增。
     */
    public long increment(String key, long delta) {
        Long value = stringRedisTemplate.opsForValue().increment(key, delta);
        return value == null ? 0 : value;
    }

    /**
     * 字符串计数器原子递减。
     */
    public long decrement(String key) {
        return decrement(key, 1);
    }

    /**
     * 字符串计数器按指定步长原子递减。
     */
    public long decrement(String key, long delta) {
        Long value = stringRedisTemplate.opsForValue().decrement(key, delta);
        return value == null ? 0 : value;
    }

    /**
     * 发布 Redis 频道消息。
     *
     * <p>Redis Pub/Sub 适合同一系统内的轻量通知，不是可靠消息队列；重要业务事件建议后续接入 MQ。
     *
     * @return 接收到消息的订阅者数量
     */
    public long publish(String channel, Object message) {
        Long count = redisTemplate.convertAndSend(channel, message);
        return count == null ? 0 : count;
    }
}
