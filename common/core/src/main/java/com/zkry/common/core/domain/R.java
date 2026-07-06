package com.zkry.common.core.domain;

import com.zkry.common.core.exception.CommonErrorCode;
import com.zkry.common.core.exception.ErrorCode;

public class R<T> {

    private int code;

    private String message;

    private T data;

    private String traceId;

    private long timestamp;

    public R() {
        this.timestamp = System.currentTimeMillis();
    }

    private R(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(CommonErrorCode.SUCCESS.code(), CommonErrorCode.SUCCESS.message(), data);
    }

    public static <T> R<T> fail(String message) {
        return fail(CommonErrorCode.BUSINESS_ERROR, message);
    }

    public static <T> R<T> fail(ErrorCode errorCode) {
        return fail(errorCode, errorCode.message());
    }

    public static <T> R<T> fail(ErrorCode errorCode, String message) {
        return new R<>(errorCode.code(), message, null);
    }

    public R<T> traceId(String traceId) {
        this.traceId = traceId;
        return this;
    }

    public boolean isSuccess() {
        return CommonErrorCode.SUCCESS.code() == code;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
