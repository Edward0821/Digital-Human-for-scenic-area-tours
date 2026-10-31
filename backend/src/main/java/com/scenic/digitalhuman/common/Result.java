package com.scenic.digitalhuman.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应四件套 —— 与《接口设计说明书》第 2 章"全局约定"一致：
 * code=0 成功；1xxxx 参数错（不重试）；2xxxx 业务错；5xxxx 系统错（可重试）。
 * traceId：5xxxx 时必填，贯穿日志便于排障；成功时为 null。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    private Integer code;
    private String message;
    private T data;
    private String traceId;

    public static <T> Result<T> success(T data) {
        return new Result<>(ErrorCode.OK, "success", data, null);
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> fail(int code, String message) {
        return fail(code, message, null);
    }

    public static <T> Result<T> fail(int code, String message, T data) {
        String traceId = code >= 50000 ? java.util.UUID.randomUUID().toString().replace("-", "") : null;
        return new Result<>(code, message, data, traceId);
    }
}
