package com.zkry.common.json.utils;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * Spring 上下文持有器。
 *
 * <p>这个类的作用是把 Spring 容器里的 Bean 暴露给少量静态工具类使用。
 *
 * <p>在这个项目里，主要是给 {@link JsonUtils} 获取 Spring Boot 创建好的 ObjectMapper。
 * 这样静态工具方法和 Spring MVC 返回 JSON 时使用的是同一个 ObjectMapper，
 * 不会出现“接口返回一个格式，工具类转换又是另一个格式”的问题。
 *
 * <p>注意：这个类只应该用于基础设施工具类，业务代码不要依赖它到处静态取 Bean。
 * 业务服务仍然应该优先使用构造器注入，依赖关系会更清晰，也更方便测试。
 */
public class SpringContextHolder implements ApplicationContextAware {

    /**
     * Spring 应用上下文。
     *
     * <p>应用启动后由 Spring 回调 {@link #setApplicationContext(ApplicationContext)} 注入。
     */
    private static ApplicationContext applicationContext;

    /**
     * Spring 容器初始化时自动回调。
     *
     * <p>这里保存 ApplicationContext，供静态工具方法在需要时读取容器 Bean。
     */
    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        SpringContextHolder.applicationContext = applicationContext;
    }

    /**
     * 根据类型获取 Spring Bean。
     *
     * <p>如果 Spring 容器还没有初始化，返回 null。调用方需要自己决定是否使用兜底实现。
     *
     * @param beanType Bean 类型
     * @return Spring Bean；容器未初始化时返回 null
     */
    public static <T> T getBean(Class<T> beanType) {
        if (applicationContext == null) {
            return null;
        }
        return applicationContext.getBean(beanType);
    }
}
