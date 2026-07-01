package com.zkry.common.web.exception;

import com.zkry.common.core.domain.R;
import com.zkry.common.core.exception.BizException;
import com.zkry.common.core.exception.CommonErrorCode;
import com.zkry.common.core.exception.ErrorCode;
import com.zkry.common.core.exception.SimpleErrorCode;
import com.zkry.common.web.filter.TraceIdFilter;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> handleBizException(BizException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        log.warn("Business exception: {}", ex.getMessage());
        return fail(errorCode, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> handleMethodArgumentNotValidException(MethodArgumentNotValidException ex) {
        return fail(CommonErrorCode.VALIDATION_FAILED, bindingMessage(ex.getBindingResult().getAllErrors()));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<R<Void>> handleBindException(BindException ex) {
        return fail(CommonErrorCode.VALIDATION_FAILED, bindingMessage(ex.getBindingResult().getAllErrors()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<R<Void>> handleConstraintViolationException(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
            .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
            .collect(Collectors.joining("; "));
        return fail(CommonErrorCode.VALIDATION_FAILED, message);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<R<Void>> handleMissingServletRequestParameterException(
        MissingServletRequestParameterException ex) {
        return fail(CommonErrorCode.BAD_REQUEST, "Missing request parameter: " + ex.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<R<Void>> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException ex) {
        return fail(CommonErrorCode.BAD_REQUEST, "Invalid request parameter: " + ex.getName());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<R<Void>> handleHttpMessageNotReadableException(HttpMessageNotReadableException ex) {
        return fail(CommonErrorCode.BAD_REQUEST, "Request body is invalid");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<R<Void>> handleHttpRequestMethodNotSupportedException(
        HttpRequestMethodNotSupportedException ex) {
        return fail(CommonErrorCode.METHOD_NOT_ALLOWED, ex.getMessage());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<R<Void>> handleHttpMediaTypeNotSupportedException(HttpMediaTypeNotSupportedException ex) {
        return fail(CommonErrorCode.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<R<Void>> handleNoResourceFoundException(NoResourceFoundException ex) {
        return fail(CommonErrorCode.NOT_FOUND, "Resource not found");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<R<Void>> handleResponseStatusException(ResponseStatusException ex) {
        String message = ex.getReason() == null ? ex.getStatusCode().toString() : ex.getReason();
        int status = ex.getStatusCode().value();
        return fail(new SimpleErrorCode(status, message, status), message);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleException(Exception ex) {
        log.error("Unhandled exception", ex);
        return fail(CommonErrorCode.INTERNAL_ERROR, CommonErrorCode.INTERNAL_ERROR.message());
    }

    private ResponseEntity<R<Void>> fail(ErrorCode errorCode, String message) {
        return fail(errorCode, message, errorCode.httpStatus());
    }

    private ResponseEntity<R<Void>> fail(ErrorCode errorCode, String message, int httpStatus) {
        R<Void> body = R.<Void>fail(errorCode, message).traceId(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY));
        return ResponseEntity.status(HttpStatusCode.valueOf(httpStatus)).body(body);
    }

    private String bindingMessage(Iterable<ObjectError> errors) {
        String message = toStream(errors)
            .map(this::objectErrorMessage)
            .collect(Collectors.joining("; "));
        return message.isBlank() ? CommonErrorCode.VALIDATION_FAILED.message() : message;
    }

    private java.util.stream.Stream<ObjectError> toStream(Iterable<ObjectError> errors) {
        return java.util.stream.StreamSupport.stream(errors.spliterator(), false);
    }

    private String objectErrorMessage(ObjectError error) {
        if (error instanceof FieldError fieldError) {
            return fieldError.getField() + " " + fieldError.getDefaultMessage();
        }
        return error.getDefaultMessage();
    }
}
