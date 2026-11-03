package com.scenic.digitalhuman.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.scenic.digitalhuman.common.Result;
import com.scenic.digitalhuman.entity.SysPermission;
import com.scenic.digitalhuman.service.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 权限管理接口（第九周：授权管理页面后端，老师要求"要有一个页面来管理角色、用户"）。
 *
 * 粒度口径：菜单 + 按钮两级（数据级明说不做）；权限资源=URL（禅道口径）。
 * 权限码体系：sys_permission.perm_code ↔ 注解 @SaCheckPermission 的值一一对应。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PermController {

    private final PermissionService permissionService;

    /** 权限列表（平铺带 parent_id，前端组装菜单/按钮树） */
    @SaCheckPermission("perm:manage")
    @GetMapping("/permissions")
    public Result<List<SysPermission>> permissions() {
        return Result.success(permissionService.listPermissions());
    }

    /** 新增权限资源（菜单或按钮） */
    @SaCheckPermission("perm:manage")
    @PostMapping("/permissions")
    public Result<SysPermission> createPermission(@RequestBody SysPermission p) {
        return Result.success(permissionService.createPermission(p));
    }

    /** 查角色已有权限 ID 列表 */
    @SaCheckPermission("perm:manage")
    @GetMapping("/roles/{roleId}/permissions")
    public Result<List<Long>> rolePermissions(@PathVariable Long roleId) {
        return Result.success(permissionService.rolePermissionIds(roleId));
    }

    /** 角色赋权（全量替换） */
    @SaCheckPermission("perm:manage")
    @PutMapping("/roles/{roleId}/permissions")
    public Result<Map<String, Object>> assignRolePermissions(@PathVariable Long roleId,
                                                             @RequestBody AssignPermsReq req) {
        permissionService.assignRolePermissions(roleId, req.permIds());
        return Result.success(Map.of("message", "角色权限已更新"));
    }

    /** 用户列表（脱敏，无 password 字段） */
    @SaCheckPermission("perm:manage")
    @GetMapping("/users")
    public Result<List<Map<String, Object>>> users() {
        return Result.success(permissionService.listUsers());
    }

    /** 用户赋角色（sys_user_role 与 role 快照同事务双写） */
    @SaCheckPermission("perm:manage")
    @PutMapping("/users/{userId}/roles")
    public Result<Map<String, Object>> assignUserRole(@PathVariable Long userId,
                                                      @RequestBody AssignRoleReq req) {
        permissionService.assignUserRole(userId, req.roleId());
        return Result.success(Map.of("message", "用户角色已更新"));
    }

    public record AssignPermsReq(List<Long> permIds) {
    }

    public record AssignRoleReq(Long roleId) {
    }
}
