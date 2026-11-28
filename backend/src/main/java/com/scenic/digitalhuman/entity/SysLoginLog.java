package com.scenic.digitalhuman.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录日志（第十二周任务 A2）：成功和失败都记。
 * 审计价值：同一 IP/用户名连续失败 = 爆破尝试；uri 字段区分不同登录方式入口。
 */
@Data
@TableName("sys_login_log")
public class SysLoginLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 尝试登录的用户名（失败时也可能是瞎猜的） */
    private String username;

    /** 登录成功用户 ID；失败/账号不存在时 NULL */
    private Long userId;

    /** 来源 IP（X-Forwarded-For 首段或 remoteAddr） */
    private String ip;

    /** 登录接口地址（老师点名：不同用户登录接口地址不一样） */
    private String uri;

    /** 是否成功（1 成功 / 0 失败） */
    private Integer success;

    /** 失败原因（验证码错/密码错/被限流，成功时 NULL） */
    private String failReason;

    /** 客户端 UA */
    private String userAgent;

    private LocalDateTime createdAt;
}
