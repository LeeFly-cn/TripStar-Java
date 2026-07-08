package com.zkry.content.service;

/**
 * 小红书接口返回的普通业务失败。
 *
 * <p>它和 {@link XhsCookieExpiredException} 分开，是为了让调用方能区分：
 * Cookie/风控类错误需要直接提示用户更新配置；单条笔记不存在这类错误会原样暴露给
 * 小红书详情阶段，由阶段校验决定是否终止。
 */
public class XhsApiException extends RuntimeException {

    private final String code;
    private final String responseMessage;

    public XhsApiException(String code, String responseMessage) {
        super("小红书接口返回失败 (code=" + safe(code) + "): " + safe(responseMessage));
        this.code = code;
        this.responseMessage = responseMessage;
    }

    public String code() {
        return code;
    }

    public String responseMessage() {
        return responseMessage;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
