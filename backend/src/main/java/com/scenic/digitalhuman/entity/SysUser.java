package com.scenic.digitalhuman.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户表（第九周 RBAC 全套：注册/登录主体）。
 * password 只存 BCrypt 哈希（slide8：慢哈希+随机盐，盐内嵌，同密码不同密文）。
 * role 为冗余快照：免 join 判权，变更时与 sys_user_role 同事务双写。
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** BCrypt 哈希，接口层永不返回（slide20：明文存密码=库被拖即全员泄露） */
    private String password;

    /** 角色冗余快照：ADMIN / VISITOR */
    private String role;

    /** 邮箱（邮箱验证码登录 / 忘记密码） */
    private String email;

    private LocalDateTime createdAt;
}
