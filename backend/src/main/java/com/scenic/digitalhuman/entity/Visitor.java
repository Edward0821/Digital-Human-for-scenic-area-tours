package com.scenic.digitalhuman.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 游客档案表（注册后建档，与 sys_user 一对一共享主键）。
 * 匿名游客不建档，兴趣标签存 Redis 会话（项目决策：免登录方案 B）。
 */
@Data
@TableName(value = "visitor", autoResultMap = true)
public class Visitor {

    /** 与 sys_user.id 同值（一对一，注册事务内同值写入） */
    @TableId(type = IdType.INPUT)
    private Long id;

    private Long userId;

    private String nickname;

    /** 兴趣标签集合（HISTORY/CULTURE/NATURE/FOOD/FAMILY） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> interestTags;

    /** 偏好补充说明 */
    private String preference;

    private LocalDateTime createdAt;
}
