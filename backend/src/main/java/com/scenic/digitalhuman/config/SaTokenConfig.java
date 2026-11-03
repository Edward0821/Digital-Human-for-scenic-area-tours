package com.scenic.digitalhuman.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Sa-Token 路由拦截配置（CORS 沿用 WebConfig，不重复配置）。
 *
 * 策略：登录校验按路径白名单（受保护前缀强制 checkLogin）+ 细粒度权限用注解
 * （@SaCheckPermission("perm_code")，权限码即 sys_permission.perm_code，权限资源=URL）。
 * 游客可选登录接口（qa/ask、files/simple、qa/records 等）不拦截，业务里用 getLoginIdDefaultNull。
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    /** 强制登录的路径前缀（管理端 + 个人中心）；files 模块单独分支（simple 直传放行，其余 ADMIN） */
    private static final List<String> PROTECTED_PREFIXES = List.of(
            "/api/v1/knowledge-docs",   // KB 模块（ADMIN，方法内另有 @SaCheckPermission 细分）
            "/api/v1/digital-humans",   // DH 模块（ADMIN）
            "/api/v1/reports",          // RPT 报告（ADMIN）
            "/api/v1/dashboard",        // 大屏（ADMIN）
            "/api/v1/visitors",         // 游客档案（用户本人，水平越权防护示例）
            "/api/v1/permissions",      // 权限管理（ADMIN）
            "/api/v1/users"             // 用户管理（ADMIN）
    );

    /** 公开/可选登录路径（匿名可访问，业务侧按需取登录态） */
    private static final List<String> PUBLIC_PREFIXES = List.of(
            "/api/v1/auth",             // 注册/登录/验证码/刷新/重置 全匿名
            "/api/health",
            "/api/v1/scenic-spots",
            "/api/v1/routes",
            "/api/v1/qa",
            "/api/v1/dicts",
            "/api/v1/files/simple"      // FILE-5 小文件直传（可选登录）
    );

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> {
                    String path = cn.dev33.satoken.context.SaHolder.getRequest().getRequestPath();
                    if (isProtected(path)) {
                        StpUtil.checkLogin();
                    }
                }))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/error");
    }

    private boolean isProtected(String path) {
        // files 模块：FILE-1/3/4（init/complete/{id}）ADMIN 保护；FILE-5（simple 直传）游客可选
        if (path.startsWith("/api/v1/files/")) {
            return !path.startsWith("/api/v1/files/simple");
        }
        for (String pub : PUBLIC_PREFIXES) {
            if (path.startsWith(pub)) {
                return false;
            }
        }
        for (String p : PROTECTED_PREFIXES) {
            if (path.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}
