package com.zkry.common.core.exception;

public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(String message) {
        this(CommonErrorCode.BUSINESS_ERROR, message);
    }

    public BizException(ErrorCode errorCode) {
        this(errorCode, errorCode.message());
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
