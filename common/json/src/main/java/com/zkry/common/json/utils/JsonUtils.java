package com.zkry.common.json.utils;

import com.zkry.common.json.config.JacksonConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON 工具类。
 *
 * <p>这个类用于业务代码中少量需要手动 JSON 转换的场景，例如：
 *
 * <p>1. 把第三方接口返回的 JSON 字符串转换成对象。
 *
 * <p>2. 把缓存、消息、日志里的对象转换成 JSON 字符串。
 *
 * <p>3. 在 Redis 反序列化后，把 Map/List 这类中间结构再转换成明确的业务类型。
 *
 * <p>注意：Controller 的返回值不需要手动调用这个工具类，Spring MVC 会自动使用容器中的 ObjectMapper。
 */
public final class JsonUtils {

    /**
     * 兜底 ObjectMapper。
     *
     * <p>正常运行时，工具类会优先使用 Spring 容器里的 ObjectMapper。
     * 但在单元测试、静态工具调用、Spring 容器尚未初始化等场景下，可能拿不到容器对象。
     * 此时使用这个 fallback mapper，至少保证时间格式、大数字策略和项目主配置保持一致。
     */
    private static final ObjectMapper FALLBACK_MAPPER = JsonMapper.builder()
        .addModule(JacksonConfig.buildCommonJsonModule())
        .defaultTimeZone(TimeZone.getDefault())
        .build();

    /**
     * 工具类不允许实例化。
     */
    private JsonUtils() {
    }

    /**
     * 获取 ObjectMapper。
     *
     * <p>优先使用 Spring 容器里的 ObjectMapper，因为它会包含 Spring Boot、common-json 以及后续其他模块注册的扩展配置。
     * 如果当前不在 Spring 环境中，则使用 fallback mapper。
     */
    public static ObjectMapper getObjectMapper() {
        ObjectMapper objectMapper = SpringContextHolder.getBean(ObjectMapper.class);
        return objectMapper == null ? FALLBACK_MAPPER : objectMapper;
    }

    /**
     * 将对象序列化为 JSON 字符串。
     *
     * <p>传入 null 时返回 null，调用方可以用它区分“没有值”和“空 JSON 对象”。
     */
    public static String toJsonString(Object object) {
        if (object == null) {
            return null;
        }
        try {
            return getObjectMapper().writeValueAsString(object);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to serialize object to JSON", ex);
        }
    }

    /**
     * 将 JSON 字符串反序列化为指定类型。
     *
     * <p>适合普通 Java Bean、DTO、VO 等非泛型对象。
     */
    public static <T> T parseObject(String text, Class<T> clazz) {
        if (isBlank(text)) {
            return null;
        }
        try {
            return getObjectMapper().readValue(text, clazz);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to parse JSON string", ex);
        }
    }

    /**
     * 将 JSON 字符串反序列化为复杂泛型类型。
     *
     * <p>适合 {@code List<UserDTO>}、{@code Map<String, UserDTO>} 这类泛型结构。
     */
    public static <T> T parseObject(String text, TypeReference<T> typeReference) {
        if (isBlank(text)) {
            return null;
        }
        try {
            return getObjectMapper().readValue(text, typeReference);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to parse JSON string", ex);
        }
    }

    /**
     * 将 JSON 字节数组反序列化为指定类型。
     *
     * <p>适合处理网络、文件、消息队列等场景中拿到的原始字节数据。
     */
    public static <T> T parseObject(byte[] bytes, Class<T> clazz) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            return getObjectMapper().readValue(bytes, clazz);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to parse JSON bytes", ex);
        }
    }

    /**
     * 将 JSON 数组字符串反序列化为 List。
     *
     * <p>空字符串返回空 List，而不是 null，方便调用方直接遍历，减少空指针判断。
     */
    public static <T> List<T> parseArray(String text, Class<T> clazz) {
        if (isBlank(text)) {
            return new ArrayList<>();
        }
        try {
            // 使用 JavaType 明确告诉 Jackson 集合元素类型，避免 List 内部元素被反序列化成 Map。
            JavaType javaType = getObjectMapper().getTypeFactory().constructCollectionType(List.class, clazz);
            return getObjectMapper().readValue(text, javaType);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to parse JSON array", ex);
        }
    }

    /**
     * 将 JSON 对象字符串反序列化为 Map。
     *
     * <p>适合字段结构不固定的场景，例如第三方平台回调、扩展参数、动态配置等。
     */
    public static Map<String, Object> parseMap(String text) {
        if (isBlank(text)) {
            return null;
        }
        return parseObject(text, new TypeReference<>() {
        });
    }

    /**
     * 将 JSON 字符串解析为 JsonNode 树。
     *
     * <p>适合只读取部分字段，或者 JSON 结构不稳定、不适合直接定义 DTO 的场景。
     */
    public static JsonNode parseTree(String text) {
        if (isBlank(text)) {
            return null;
        }
        try {
            return getObjectMapper().readTree(text);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to parse JSON tree", ex);
        }
    }

    /**
     * 将一个对象转换为指定类型。
     *
     * <p>这个方法不是先转字符串再解析，而是使用 ObjectMapper 的内存转换能力。
     * 常用于把 Map 转 DTO、把 Redis 反序列化出的 LinkedHashMap 转为业务对象。
     */
    public static <T> T convertValue(Object value, Class<T> clazz) {
        if (value == null) {
            return null;
        }
        return getObjectMapper().convertValue(value, clazz);
    }

    /**
     * 将一个对象转换为复杂泛型类型。
     *
     * <p>例如把 Object 转成 {@code List<UserDTO>} 时，就需要使用 TypeReference 保留泛型信息。
     */
    public static <T> T convertValue(Object value, TypeReference<T> typeReference) {
        if (value == null) {
            return null;
        }
        return getObjectMapper().convertValue(value, typeReference);
    }

    /**
     * 判断字符串是否是合法 JSON。
     *
     * <p>对象、数组、字符串、数字等合法 JSON 值都会返回 true。
     */
    public static boolean isJson(String text) {
        if (isBlank(text)) {
            return false;
        }
        try {
            getObjectMapper().readTree(text);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * 判断字符串是否是 JSON 对象。
     *
     * <p>只有形如 {@code {"name":"test"}} 的对象结构才返回 true，数组和普通字符串会返回 false。
     */
    public static boolean isJsonObject(String text) {
        if (isBlank(text)) {
            return false;
        }
        try {
            JsonNode jsonNode = getObjectMapper().readTree(text);
            return jsonNode != null && jsonNode.isObject();
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * 判断字符串是否是 JSON 数组。
     *
     * <p>只有形如 {@code [1,2,3]} 或 {@code [{"id":1}]} 的数组结构才返回 true。
     */
    public static boolean isJsonArray(String text) {
        if (isBlank(text)) {
            return false;
        }
        try {
            JsonNode jsonNode = getObjectMapper().readTree(text);
            return jsonNode != null && jsonNode.isArray();
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * 判断字符串是否为空白。
     *
     * <p>这里不引入额外工具包，避免 common-json 为了一个简单判断再增加依赖。
     */
    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }
}
