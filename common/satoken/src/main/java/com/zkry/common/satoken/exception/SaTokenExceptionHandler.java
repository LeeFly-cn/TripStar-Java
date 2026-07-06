package com.zkry.common.satoken.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.zkry.common.core.domain.R;
import com.zkry.common.core.exception.CommonErrorCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 将 Sa-Token 鉴权异常转换为统一的 R 返回结构。
 *
 * <p>Sa-Token 默认会抛出自己的异常类型。这里做一层统一转换，保证前端无论遇到业务异常、
 * 参数异常还是鉴权异常，拿到的响应结构都是 {@link R}。
 *
 * <p>当前映射规则：
 *
 * <p>1. 未登录：HTTP 401，对应 CommonErrorCode.UNAUTHORIZED。
 *
 * <p>2. 无角色或无权限：HTTP 403，对应 CommonErrorCode.FORBIDDEN。
 *
 * <p>这样前端可以很明确地处理登录过期、跳转登录页、展示无权限提示等场景。
 */
@RestControllerAdvice
public class SaTokenExceptionHandler {

    /**
     * 处理未登录异常。
     *
     * <p>典型触发场景：未携带 token、token 过期、token 被踢下线、会话不存在。
     */
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<R<Void>> handleNotLoginException(NotLoginException ex) {
        return ResponseEntity.status(CommonErrorCode.UNAUTHORIZED.httpStatus())
            .body(R.fail(CommonErrorCode.UNAUTHORIZED, CommonErrorCode.UNAUTHORIZED.message()));
    }

    /**
     * 处理无权限或无角色异常。
     *
     * <p>用户已经登录，但当前账号没有访问目标资源所需的角色或权限时，返回 403。
     */
    @ExceptionHandler({NotPermissionException.class, NotRoleException.class})
    public ResponseEntity<R<Void>> handleForbiddenException(RuntimeException ex) {
        return ResponseEntity.status(CommonErrorCode.FORBIDDEN.httpStatus())
            .body(R.fail(CommonErrorCode.FORBIDDEN, CommonErrorCode.FORBIDDEN.message()));
    }
}
