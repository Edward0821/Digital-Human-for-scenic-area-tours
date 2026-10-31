package com.scenic.digitalhuman.common;

/**
 * 业务错误码常量 —— 与《接口设计说明书》第 4 章"统一返回编码表"严格一致。
 * 分段：0 成功 / 1xxxx 参数错（不重试）/ 2xxxx 业务错 / 5xxxx 系统错（可重试）。
 */
public final class ErrorCode {

    private ErrorCode() {}

    /** 成功 */
    public static final int OK = 0;

    /** 400 参数校验失败（@Valid / 分片 md5 校验失败） */
    public static final int BAD_PARAM = 10001;
    /** 400 payload 超限（问题超 500 字 / 文件超限） */
    public static final int BAD_PAYLOAD = 10002;
    /** 400 格式错（md5 非 32 位 hex、文件类型不在白名单、缺少分片） */
    public static final int BAD_FORMAT = 10003;
    /** 404 资源不存在 */
    public static final int NOT_FOUND = 20006;
    /** 409 状态冲突（未传完就 complete / 重复 complete） */
    public static final int STATE_CONFLICT = 20007;
    /** 500 文件合并 md5 校验失败 */
    public static final int MERGE_MD5_MISMATCH = 50003;
    /** 500 系统内部错误 */
    public static final int SYSTEM_ERROR = 50000;
}
