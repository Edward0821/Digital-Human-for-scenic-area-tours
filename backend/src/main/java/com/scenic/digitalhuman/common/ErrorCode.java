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
    /** 401 未认证（JWT/令牌缺失、无效或过期）—— Sa-Token NotLoginException 映射 */
    public static final int UNAUTHORIZED = 10004;
    /** 400 验证码无效或已过期（图形验证码 / 邮箱验证码） */
    public static final int CAPTCHA_INVALID = 10005;
    /** 400 用户名已存在 */
    public static final int USERNAME_EXISTS = 20001;
    /** 401 账号或密码错误（统一提示防撞库探测，slide9） */
    public static final int BAD_CREDENTIALS = 20002;
    /** 403 无权访问该资源（水平/垂直越权拦截，IDOR 口诀落地） */
    public static final int FORBIDDEN = 20003;
    /** 403 角色权限不足（非 ADMIN 访问管理接口）—— Sa-Token NotPermissionException 映射 */
    public static final int ROLE_DENIED = 20004;
    /** 429 请求过频（限流触发） */
    public static final int RATE_LIMITED = 20005;
    /** 404 资源不存在 */
    public static final int NOT_FOUND = 20006;
    /** 409 状态冲突（未传完就 complete / 重复 complete） */
    public static final int STATE_CONFLICT = 20007;
    /** 500 文件合并 md5 校验失败 */
    public static final int MERGE_MD5_MISMATCH = 50003;
    /** 500 系统内部错误 */
    public static final int SYSTEM_ERROR = 50000;
}
