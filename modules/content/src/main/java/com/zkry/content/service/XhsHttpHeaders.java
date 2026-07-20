package com.zkry.content.service;

/**
 * 小红书公开页面和图片请求共用的浏览器请求头。
 *
 * <p>集中定义可以保证短链、页面和图片请求保持一致，也避免各 Service 硬编码不同版本。
 */
public final class XhsHttpHeaders {

    /** 模拟普通桌面 Chrome，仅用于公开页面读取，不包含 Cookie 或用户身份信息。 */
    public static final String BROWSER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/131.0.0.0 Safari/537.36";

    private XhsHttpHeaders() {
    }
}
