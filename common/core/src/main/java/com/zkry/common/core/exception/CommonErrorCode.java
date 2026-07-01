package com.zkry.common.core.exception;

public enum CommonErrorCode implements ErrorCode {

    SUCCESS(0, "success", 200),
    BAD_REQUEST(400, "Bad request", 400),
    UNAUTHORIZED(401, "Unauthorized", 401),
    FORBIDDEN(403, "Forbidden", 403),
    NOT_FOUND(404, "Not found", 404),
    METHOD_NOT_ALLOWED(405, "Method not allowed", 405),
    VALIDATION_FAILED(422, "Validation failed", 422),
    BUSINESS_ERROR(10000, "Business error", 400),
    INTERNAL_ERROR(500, "Internal server error", 500);

    private final int code;
    private final String message;
    private final int httpStatus;

    CommonErrorCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    @Override
    public int code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }
}
