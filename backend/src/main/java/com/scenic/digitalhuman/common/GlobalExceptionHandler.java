package com.scenic.digitalhuman.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：所有异常统一转成 Result 返回，堆栈只进日志不进响应（安全自检第 10 条）。
 * 参数错 → 10001；业务异常透传错误码；系统错 → 50000 + traceId（可观测第 9 条）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidException(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : "参数校验失败";
        return Result.fail(ErrorCode.BAD_PARAM, message);
    }

    @ExceptionHandler(BizException.class)
    public Result<Object> handleBizException(BizException e) {
        return Result.fail(e.getCode(), e.getMessage(), e.getData());
    }

    // Sa-Token 异常映射（全项目口径统一为 HTTP 200 + 业务码，前端拦截器按 code 处理）
    @ExceptionHandler(cn.dev33.satoken.exception.NotLoginException.class)
    public Result<Object> handleNotLogin(cn.dev33.satoken.exception.NotLoginException e) {
        return Result.fail(ErrorCode.UNAUTHORIZED, "未登录或登录已过期，请重新登录");
    }

    @ExceptionHandler(cn.dev33.satoken.exception.NotPermissionException.class)
    public Result<Object> handleNotPermission(cn.dev33.satoken.exception.NotPermissionException e) {
        return Result.fail(ErrorCode.ROLE_DENIED, "无权限执行该操作");
    }

    @ExceptionHandler(cn.dev33.satoken.exception.NotRoleException.class)
    public Result<Object> handleNotRole(cn.dev33.satoken.exception.NotRoleException e) {
        return Result.fail(ErrorCode.ROLE_DENIED, "角色权限不足");
    }

    // Spring 6.1+ 对未匹配路径抛 NoResourceFoundException，不单独处理会被下方
    // Exception 兜底成 50000"系统内部错误"——前端调错路径时无从排查
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNoResource(NoResourceFoundException e) {
        return Result.fail(ErrorCode.NOT_FOUND, "请求的接口不存在，请检查路径拼写");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("未捕获异常", e);
        return Result.fail(ErrorCode.SYSTEM_ERROR, "系统内部错误，请稍后重试");
    }
}
