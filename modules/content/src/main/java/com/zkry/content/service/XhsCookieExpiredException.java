package com.zkry.content.service;

public class XhsCookieExpiredException extends RuntimeException {

    public XhsCookieExpiredException(String message) {
        super(message);
    }

    public XhsCookieExpiredException(String message, Throwable cause) {
        super(message, cause);
    }
}
