package com.scenic.digitalhuman.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.scenic.digitalhuman.common.BizException;
import com.scenic.digitalhuman.common.ErrorCode;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * RBAC 权限管理服务（第九周：授权管理页面后端）。
 * 权限资源=URL（禅道口径），粒度=菜单+按钮（老师明说数据级可不做）。
 * 口径：角色改权限 → 事务内删旧插新；用户改角色 → sys_user_role + sys_user.role 快照同事务双写（DDL 注释要求）。
 */
@Service
@RequiredArgsConstructor
public class PermissionService {

    private final SysPermissionMapper permissionMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;

    /** 全部权限（平铺返回带 parent_id，前端组装树） */
    public List<SysPermission> listPermissions() {
        return permissionMapper.selectList(
                new LambdaQueryWrapper<SysPermission>().orderByAsc(SysPermission::getSort));
    }

    /** 新增权限资源 */
    public SysPermission createPermission(SysPermission p) {
        Long exists = permissionMapper.selectCount(
                new LambdaQueryWrapper<SysPermission>().eq(SysPermission::getPermCode, p.getPermCode()));
        if (exists > 0) {
            throw new BizException(ErrorCode.USERNAME_EXISTS, "权限码已存在：" + p.getPermCode());
        }
        p.setStatus(p.getStatus() == null ? 1 : p.getStatus());
        p.setParentId(p.getParentId() == null ? 0L : p.getParentId());
        permissionMapper.insert(p);
        return p;
    }

    /** 某角色的权限 ID 列表 */
    public List<Long> rolePermissionIds(Long roleId) {
        return rolePermissionMapper.selectList(
                        new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleId, roleId))
                .stream().map(SysRolePermission::getPermId).toList();
    }

    /** 角色赋权：全量替换（事务内删旧插新） */
    @Transactional(rollbackFor = Exception.class)
    public void assignRolePermissions(Long roleId, List<Long> permIds) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "角色不存在");
        }
        rolePermissionMapper.delete(
                new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleId, roleId));
        for (Long permId : permIds) {
            SysRolePermission rp = new SysRolePermission();
            rp.setRoleId(roleId);
            rp.setPermId(permId);
            rolePermissionMapper.insert(rp);
        }
    }

    /** 用户列表（脱敏：绝不返回 password 字段） */
    public List<Map<String, Object>> listUsers() {
        return userMapper.selectList(new LambdaQueryWrapper<SysUser>().orderByDesc(SysUser::getId))
                .stream().map(u -> Map.<String, Object>of(
                        "userId", u.getId(),
                        "username", u.getUsername(),
                        "role", u.getRole(),
                        "email", u.getEmail() == null ? "" : u.getEmail(),
                        "createdAt", u.getCreatedAt() == null ? "" : u.getCreatedAt().toString()))
                .toList();
    }

    /** 用户赋角色：sys_user_role 与 sys_user.role 快照同事务双写（防快照漂移） */
    @Transactional(rollbackFor = Exception.class)
    public void assignUserRole(Long userId, Long roleId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "角色不存在");
        }
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
        SysUserRole ur = new SysUserRole();
        ur.setUserId(userId);
        ur.setRoleId(roleId);
        userRoleMapper.insert(ur);

        SysUser update = new SysUser();
        update.setId(userId);
        update.setRole(role.getRoleCode());
        userMapper.updateById(update);
    }
}
