package com.zkry.common.satoken.core;

import cn.dev33.satoken.stp.StpUtil;

/**
 * 登录态工具类。
 *
 * <p>这个类是对 Sa-Token {@link StpUtil} 的轻量封装，目的不是隐藏 Sa-Token，
 * 而是给业务模块提供一组稳定、简单、符合项目约定的登录态 API。
 *
 * <p>为什么不建议业务代码到处直接调用 StpUtil：
 *
 * <p>1. 登录态里保存哪些字段，需要项目统一约定，否则后面管理端、用户端会各写一套。
 *
 * <p>2. 后续如果切换登录 ID 类型、增加租户 ID、增加账号类型，统一封装会更容易改。
 *
 * <p>3. Controller/Service 中调用 LoginHelper，可读性比散落一堆 StpUtil 细节更好。
 *
 * <p>注意：这个类只维护认证上下文，不直接查询用户表，也不负责账号密码校验。
 * 用户是否存在、密码是否正确、账号是否禁用，应该由 identity/user 等业务模块处理。
 */
public final class LoginHelper {

    /**
     * Sa-Token Session 中保存登录用户快照的 key。
     *
     * <p>这里保存的是 LoginUser，不是完整用户实体。
     * 这样可以避免认证模块依赖具体业务表结构，也避免把密码、手机号等敏感字段放进会话。
     */
    private static final String LOGIN_USER_KEY = "loginUser";

    /**
     * 工具类不允许实例化。
     */
    private LoginHelper() {
    }

    /**
     * 使用用户 ID 作为登录标识，并把完整登录用户快照写入会话。
     *
     * <p>Sa-Token 的 loginId 只需要一个唯一标识，这里选用 userId。
     * 角色、权限、用户名等展示和鉴权辅助信息放在 LoginUser 快照里，避免每次请求都查数据库。
     *
     * @param loginUser 当前登录用户快照
     */
    public static void login(LoginUser loginUser) {
        StpUtil.login(loginUser.userId());
        StpUtil.getSession().set(LOGIN_USER_KEY, loginUser);
    }

    /**
     * 当前会话退出登录。
     *
     * <p>普通用户端“退出登录”按钮、管理端退出登录都可以调用这个方法。
     */
    public static void logout() {
        StpUtil.logout();
    }

    /**
     * 判断当前请求是否已登录。
     *
     * <p>适合在可匿名访问的接口里做分支逻辑，例如“未登录也能看详情，登录后额外返回是否收藏”。
     */
    public static boolean isLogin() {
        return StpUtil.isLogin();
    }

    /**
     * 获取当前登录用户 ID。
     *
     * <p>如果当前请求未登录，Sa-Token 会抛出未登录异常。
     * 业务代码调用这个方法前，一般应该确保当前接口已经经过登录拦截。
     */
    public static Long getUserId() {
        return StpUtil.getLoginIdAsLong();
    }

    /**
     * 只有会话存在但未写入 LoginUser 快照时才会返回 null。
     *
     * <p>正常通过 {@link #login(LoginUser)} 登录时，会话里一定会写入 LoginUser。
     * 返回 null 通常说明登录流程没有使用 LoginHelper，或者会话数据被手动清理过。
     */
    public static LoginUser getLoginUser() {
        Object value = StpUtil.getSession().get(LOGIN_USER_KEY);
        if (value instanceof LoginUser loginUser) {
            return loginUser;
        }
        return null;
    }
}
