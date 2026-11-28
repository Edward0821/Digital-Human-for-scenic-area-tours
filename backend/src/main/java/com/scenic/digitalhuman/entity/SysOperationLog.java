package com.scenic.digitalhuman.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作日志（第十二周任务 A2）：AOP 切面自动记录，不手埋。
 * 点赞/收藏这类写操作挂 @OperationLog 注解即入库，业务代码零侵入。
 */
@Data
@TableName("sys_operation_log")
public class SysOperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 用户名冗余快照：改名后仍可追溯 */
    private String username;

    /** 动作码（like / favorite / qa:ask / file:upload） */
    private String action;

    /** 目标类型（spot / qa / file / knowledge_doc） */
    private String targetType;

    /** 目标标识（字符串兼容 UUID 与数字 ID） */
    private String targetId;

    /** 操作详情（不入敏感字段） */
    private String detail;

    private String uri;

    /** HTTP 方法 */
    private String method;

    private String ip;

    private LocalDateTime createdAt;
}
