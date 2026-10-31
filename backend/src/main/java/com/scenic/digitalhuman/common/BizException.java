package com.scenic.digitalhuman.common;

import lombok.Getter;

/**
 * 业务异常：携带错误码，由全局异常处理器统一转成 Result 返回。
 * data 可携带附加信息（如"缺失分片号数组"，用于通知前端重传哪些分片）。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;
    private final transient Object data;

    public BizException(int code, String message) {
        this(code, message, null);
    }

    public BizException(int code, String message, Object data) {
        super(message);
        this.code = code;
        this.data = data;
    }
}
