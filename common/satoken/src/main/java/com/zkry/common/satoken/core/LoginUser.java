package com.zkry.common.satoken.core;

import java.io.Serial;
import java.io.Serializable;
import java.util.Set;

/**
 * 存储在 Sa-Token 会话中的最小登录用户快照。
 *
 * <p>这个 record 不是数据库实体，也不应该和用户表一一绑定。
 * 它只保存“每次请求经常需要用到”的认证上下文信息，例如用户 ID、用户名、角色、权限。
 *
 * <p>为什么只保存快照：
 *
 * <p>1. 认证模块不需要依赖 identity/user 模块里的 domain，模块边界更干净。
 *
 * <p>2. 避免把密码、盐值、手机号、邮箱等敏感字段放入 Sa-Token Session。
 *
 * <p>3. 高频接口可以直接从会话里拿角色和权限，减少不必要的数据库查询。
 *
 * <p>如果用户角色或权限发生变化，业务模块需要主动刷新登录会话，或者要求用户重新登录。
 */
public record LoginUser(
    /**
     * 用户主键 ID。
     *
     * <p>这里通常对应用户表主键，也是 Sa-Token 的 loginId。
     */
    Long userId,

    /**
     * 用户名或展示名。
     *
     * <p>用于日志、审计、页面展示等轻量场景，不建议放入手机号、邮箱等敏感标识。
     */
    String username,

    /**
     * 当前用户拥有的角色编码。
     *
     * <p>例如 admin、user、operator。角色是粗粒度身份，适合判断“是否管理员”等场景。
     */
    Set<String> roles,

    /**
     * 当前用户拥有的权限编码。
     *
     * <p>例如 user:list、credit:adjust。权限是细粒度能力，适合控制具体菜单、按钮和接口。
     */
    Set<String> permissions
) implements Serializable {

    /**
     * 序列化版本号。
     *
     * <p>LoginUser 会被放入 Sa-Token Session，后续如果接入 Redis 等持久化会话存储，
     * 保留 serialVersionUID 可以减少序列化兼容性问题。
     */
    @Serial
    private static final long serialVersionUID = 1L;
}
