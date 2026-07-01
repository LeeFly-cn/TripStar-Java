package com.zkry.common.core.exception;

public record SimpleErrorCode(int code, String message, int httpStatus) implements ErrorCode {
}
