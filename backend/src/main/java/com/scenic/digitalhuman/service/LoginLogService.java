package com.scenic.digitalhuman.service;

import cn.dev33.satoken.stp.StpUtil;
import com.scenic.digitalhuman.common.BizException;
import com.scenic.digitalhuman.entity.SysLoginLog;
import com.scenic.digitalhuman.entity.SysUser;
import com.scenic.digitalhuman.mapper.SysLoginLogMapper;
import com.scenic.digitalhuman.mapper.SysUserMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 登录日志服务（第十二周任务 A2）：成功和失败都落库。
 * 审计要能看出爆破尝试——同一 IP/用户名连续失败即信号。
 * 所有落库异常全吞：审计绝不能把成功的登录变成失败。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginLogService {

    private final SysLoginLogMapper loginLogMapper;
    private final SysUserMapper userMapper;

    /**
     * 记一条登录日志。成功/失败都调用；失败时 failReason 非空。
     * uri 从当前请求上下文取（不同登录方式入口 URI 可能不同——老师点名要点）。
     */
    public void record(String username, Long userId, String ip,
                       boolean success, String failReason) {
        try {
            SysLoginLog logEntity = new SysLoginLog();
            logEntity.setUsername(username == null || username.isBlank() ? "(unknown)" : username);
            logEntity.setUserId(userId);
            logEntity.setIp(ip);
            logEntity.setSuccess(success ? 1 : 0);
            logEntity.setFailReason(failReason);
            HttpServletRequest request = currentRequest();
            if (request != null) {
                logEntity.setUri(request.getRequestURI());
                String ua = request.getHeader("User-Agent");
                if (ua != null && ua.length() > 500) {
                    ua = ua.substring(0, 500);
                }
                logEntity.setUserAgent(ua);
            } else {
                logEntity.setUri("(no-request-context)");
            }
            loginLogMapper.insert(logEntity);
        } catch (Exception e) {
            // 异常对象作为最后一个参数传（demo2 考点）
            log.error("[审计] 登录日志落库失败（不影响登录主流程）：username={}", username, e);
        }
    }

    /** 从请求头取真实 IP：X-Forwarded-For 首段优先，兼容直连场景 */
    public String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String xri = request.getHeader("X-Real-IP");
        if (xri != null && !xri.isBlank()) {
            return xri.trim();
        }
        return request.getRemoteAddr();
    }

    /** 用户名冗余快照（供 AOP 切面用）；查不到返回 null，不抛异常 */
    public String usernameOf(Long userId) {
        try {
            SysUser user = userMapper.selectById(userId);
            return user == null ? null : user.getUsername();
        } catch (Exception e) {
            log.debug("[审计] 用户名快照查询失败：userId={} err={}", userId, e.getMessage());
            return null;
        }
    }

    /** 从 BizException 提取可读的失败原因（供 AuthService 埋点用） */
    public static String failReason(Exception e) {
        if (e instanceof BizException) {
            return e.getMessage();
        }
        return e.getClass().getSimpleName();
    }

    /** Sa-Token 当前登录 ID（供 AuthService 埋点用，匿名返回 null） */
    public static Long currentUserId() {
        try {
            Object id = StpUtil.getLoginIdDefaultNull();
            return id == null ? null : Long.valueOf(id.toString());
        } catch (Exception ignore) {
            return null;
        }
    }

    private HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes sra ? sra.getRequest() : null;
    }
}
