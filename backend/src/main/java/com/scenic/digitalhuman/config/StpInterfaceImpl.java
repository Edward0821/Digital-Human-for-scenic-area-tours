package com.scenic.digitalhuman.config;

import cn.dev33.satoken.stp.StpInterface;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.scenic.digitalhuman.entity.SysPermission;
import com.scenic.digitalhuman.entity.SysRole;
import com.scenic.digitalhuman.entity.SysRolePermission;
import com.scenic.digitalhuman.entity.SysUser;
import com.scenic.digitalhuman.entity.SysUserRole;
import com.scenic.digitalhuman.mapper.SysPermissionMapper;
import com.scenic.digitalhuman.mapper.SysRoleMapper;
import com.scenic.digitalhuman.mapper.SysRolePermissionMapper;
import com.scenic.digitalhuman.mapper.SysUserMapper;
import com.scenic.digitalhuman.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sa-Token 权限数据加载源（RBAC 落地的核心一环）。
 *
 * 链路：请求 → 注解 @SaCheckPermission("perm_code") → Sa-Token 调本接口取当前用户的权限/角色列表 → 比对。
 * ADMIN → 全部启用权限码；VISITOR → 无权限码（游客端接口匿名/登录即可用，不挂权限码）。
 */
@Component
@RequiredArgsConstructor
public class StpInterfaceImpl implements StpInterface {

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysPermissionMapper permissionMapper;

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        SysUser user = userMapper.selectById(Long.valueOf(loginId.toString()));
        if (user == null) {
            return List.of();
        }
        // 角色→权限码；ADMIN 直连全部启用权限（种子数据保证）
        List<Long> roleIds = userRoleMapper.selectList(
                        new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, user.getId()))
                .stream().map(SysUserRole::getRoleId).toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return rolePermissionMapper.selectList(
                        new LambdaQueryWrapper<SysRolePermission>().in(SysRolePermission::getRoleId, roleIds))
                .stream()
                .map(SysRolePermission::getPermId)
                .distinct()
                .map(pid -> permissionMapper.selectById(pid))
                .filter(p -> p != null && p.getStatus() != null && p.getStatus() == 1)
                .map(SysPermission::getPermCode)
                .toList();
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        SysUser user = userMapper.selectById(Long.valueOf(loginId.toString()));
        if (user == null) {
            return List.of();
        }
        List<Long> roleIds = userRoleMapper.selectList(
                        new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, user.getId()))
                .stream().map(SysUserRole::getRoleId).toList();
        if (roleIds.isEmpty()) {
            return user.getRole() != null ? List.of(user.getRole()) : List.of();
        }
        return roleMapper.selectBatchIds(roleIds).stream().map(SysRole::getRoleCode).toList();
    }
}
