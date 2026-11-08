package com.scenic.digitalhuman.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 角色表（RBAC：用户→角色→权限，权限绑在角色上不绑在人上——slide14） */
@Data
@TableName("sys_role")
public class SysRole {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色编码：ADMIN / VISITOR */
    private String roleCode;

    private String roleName;

    private String description;

    private LocalDateTime createdAt;
}
