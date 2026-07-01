package com.zkry.common.core.exception;

/**
 * Error code contract shared by common and business modules.
 */
public interface ErrorCode {

    int code();

    String message();

    int httpStatus();
}
