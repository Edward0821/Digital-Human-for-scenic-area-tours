package com.scenic.digitalhuman.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 权限资源表（第九周：权限资源=URL，粒度=菜单+按钮两级，数据级不做——老师口径）。
 * perm_code 即鉴权注解的权限码（如 kb:list、menu:admin），呼应禅道"权限资源里存的一定有 URL"。
 */
@Data
@TableName("sys_permission")
public class SysPermission {

    /** 权限 ID（主键列名 perm_id，非 id，显式映射） */
    @TableId(value = "perm_id", type = IdType.AUTO)
    private Long permId;

    /** 权限码（鉴权注解使用） */
    private String permCode;

    /** 粒度：1=菜单 2=按钮 */
    private Integer permType;

    private String permName;

    /** 关联接口路径（权限资源=URL） */
    private String url;

    /** 父权限 ID（0=顶级菜单） */
    private Long parentId;

    private Integer sort;

    /** 1=启用 2=停用 */
    private Integer status;

    private LocalDateTime createdAt;
}
