package com.zkry.common.satoken.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token Web 层配置。
 *
 * <p>这个配置类只负责“哪些接口需要登录态”这一类横切规则，不负责具体登录流程。
 * 例如账号密码校验、验证码校验、第三方登录绑定等业务逻辑，应该放在具体业务模块里。
 *
 * <p>当前脚手架的约定：
 *
 * <p>1. {@code /api/**} 默认都需要登录，先用保守策略保护接口。
 *
 * <p>2. 登录、注册、验证码、公开接口等少数入口通过 excludePathPatterns 放行。
 *
 * <p>3. 用户端和管理端可以分别使用 {@code /api/user/auth/**} 和 {@code /api/admin/auth/**} 实现自己的登录入口。
 *
 * <p>后续如果要做更细粒度的权限控制，可以在业务接口上使用 Sa-Token 注解，
 * 或者在这里继续扩展角色、权限、租户等拦截规则。
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    /**
     * 注册 Sa-Token 登录校验拦截器。
     *
     * <p>{@link StpUtil#checkLogin()} 会检查当前请求是否已经登录。
     * 未登录时，Sa-Token 会抛出 NotLoginException，最终由 SaTokenExceptionHandler 转换成统一的 401 返回。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
            // 默认保护所有业务 API，避免新接口忘记加鉴权导致裸奔。
            .addPathPatterns("/api/**")
            // 认证入口和公开接口放行；这些路径内部是否还需要验证码、限流等，由具体业务自己控制。
            .excludePathPatterns(
                "/api/auth/**",
                "/api/user/auth/**",
                "/api/admin/auth/**",
                "/api/public/**",
                "/api/trip/**",
                "/api/chat/**",
                "/api/poi/**",
                "/api/settings/**"
            );
    }
}
