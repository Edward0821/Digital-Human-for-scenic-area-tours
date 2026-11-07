package com.scenic.digitalhuman.controller;

import com.scenic.digitalhuman.common.Result;
import com.scenic.digitalhuman.service.AuthService;
import com.scenic.digitalhuman.service.CaptchaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证接口（第九周 RBAC 全套：注册/登录/登出/验证码/刷新/找回密码/当前用户）。
 *
 * 登录方式 ≥2 种（老师要求）：PASSWORD（用户名+密码+图形验证码）/ EMAIL_CODE（邮箱+验证码）。
 * 安全口径对应第九周课件 slide8/9/12：BCrypt 慢哈希、统一提示、常数时间、限流、登出=服务端动作。
 */
@Validated
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final CaptchaService captchaService;

    /** 注册（AUTH-1）：事务内建 sys_user + visitor + sys_user_role 三行 */
    @PostMapping("/register")
    public Result<Map<String, Object>> register(@Validated @RequestBody RegisterReq req) {
        return Result.success(authService.register(req.username(), req.password(), req.nickname(), req.email()));
    }

    /** 登录（AUTH-2）：PASSWORD（用户名+密码+图形验证码）或 EMAIL_CODE（邮箱+验证码） */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Validated @RequestBody LoginReq req, HttpServletRequest request) {
        return Result.success(authService.login(req.loginType(), req.username(), req.password(),
                req.email(), req.emailCode(), req.captchaId(), req.captchaCode(),
                clientIp(request)));
    }

    /** 登出（AUTH-3）：服务端销毁会话 + 吊销 refresh（slide12：登出是服务端动作） */
    @PostMapping("/logout")
    public Result<Map<String, Object>> logout() {
        authService.logout();
        return Result.success(Map.of("message", "已退出登录"));
    }

    /** 刷新令牌：refresh 换新 access（slide12：续期不重输密码） */
    @PostMapping("/refresh")
    public Result<Map<String, Object>> refresh(@RequestBody RefreshReq req) {
        return Result.success(authService.refresh(req.refreshToken()));
    }

    /** 当前用户信息（AUTH-4） */
    @GetMapping("/me")
    public Result<Map<String, Object>> me() {
        return Result.success(authService.currentUser());
    }

    /** 图形验证码（PASSWORD 登录方式配套）：返回 captchaId + base64 图片 */
    @GetMapping("/captcha")
    public Result<CaptchaService.CaptchaImage> captcha() {
        return Result.success(captchaService.generate());
    }

    /** 发送邮箱验证码（EMAIL_CODE 登录 / RESET 重置密码 两用） */
    @PostMapping("/email-code")
    public Result<Map<String, Object>> emailCode(@Validated @RequestBody EmailCodeReq req) {
        authService.sendEmailCode(req.email(), req.scene());
        return Result.success(Map.of("message", "验证码已发送（10 分钟内有效）"));
    }

    /** 忘记密码：邮箱验证码 + 新密码（slide 老师要求：有注册必有忘记密码） */
    @PostMapping("/reset-password")
    public Result<Map<String, Object>> resetPassword(@Validated @RequestBody ResetReq req) {
        authService.resetPassword(req.email(), req.emailCode(), req.newPassword());
        return Result.success(Map.of("message", "密码已重置"));
    }

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    // ================= 请求体 =================

    public record RegisterReq(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password,
            String nickname,
            String email) {
    }

    public record LoginReq(
            @NotBlank(message = "登录方式不能为空") String loginType, // PASSWORD | EMAIL_CODE
            String username,
            String password,
            String email,
            String emailCode,
            String captchaId,
            String captchaCode) {
    }

    public record RefreshReq(@NotBlank(message = "refreshToken 不能为空") String refreshToken) {
    }

    public record EmailCodeReq(
            @NotBlank(message = "邮箱不能为空") String email,
            @NotBlank(message = "场景不能为空") String scene) { // LOGIN | RESET
    }

    public record ResetReq(
            @NotBlank(message = "邮箱不能为空") String email,
            @NotBlank(message = "邮箱验证码不能为空") String emailCode,
            @NotBlank(message = "新密码不能为空") String newPassword) {
    }
}
