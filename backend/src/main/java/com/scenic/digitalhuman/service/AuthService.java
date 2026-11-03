package com.scenic.digitalhuman.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.scenic.digitalhuman.common.BizException;
import com.scenic.digitalhuman.common.ErrorCode;
import com.scenic.digitalhuman.entity.SysRole;
import com.scenic.digitalhuman.entity.SysUser;
import com.scenic.digitalhuman.entity.SysUserRole;
import com.scenic.digitalhuman.entity.Visitor;
import com.scenic.digitalhuman.mapper.SysRoleMapper;
import com.scenic.digitalhuman.mapper.SysUserMapper;
import com.scenic.digitalhuman.mapper.SysUserRoleMapper;
import com.scenic.digitalhuman.mapper.VisitorMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 认证服务（第九周：注册/登录/登出/验证码/找回密码 全套）。
 *
 * 对应课件 slide8/9 的全部工程要点：
 * - BCrypt 慢哈希 + 每用户随机盐（盐内嵌，同密码不同密文）
 * - 登录失败统一提示"账号或密码错误"（不区分账号不存在/密码错误，防撞库探测）
 * - 账号不存在时仍执行一次哈希再失败（防时序攻击）
 * - 登录加图形验证码 + IP 维度限流（5 次/分钟），防爆破
 * - 会话：Sa-Token + Redis（登出=服务端销毁会话，slide12"登出是服务端动作"）
 * - 双令牌：access（2h，Sa-Token 会话）+ refresh（7d，Redis），续期不重输密码
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String REFRESH_PREFIX = "scenic:auth:refresh:";
    private static final String EMAIL_CODE_PREFIX = "scenic:emailcode:";
    private static final String RATE_LIMIT_PREFIX = "scenic:ratelimit:auth:";
    private static final Duration REFRESH_TTL = Duration.ofDays(7);
    private static final Duration EMAIL_CODE_TTL = Duration.ofMinutes(10);

    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final VisitorMapper visitorMapper;
    private final StringRedisTemplate redis;
    private final CaptchaService captchaService;
    private final SecureRandom random = new SecureRandom();

    /** BCryptPasswordEncoder：每次 encode 生成独立随机盐（盐内嵌密文），线程安全可复用 */
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    // ================= 注册：事务内 sys_user + visitor + sys_user_role 三行 =================

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> register(String username, String password, String nickname, String email) {
        Long exists = sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (exists > 0) {
            throw new BizException(ErrorCode.USERNAME_EXISTS, "用户名已存在");
        }
        if (email != null && !email.isBlank()) {
            Long emailExists = sysUserMapper.selectCount(
                    new LambdaQueryWrapper<SysUser>().eq(SysUser::getEmail, email));
            if (emailExists > 0) {
                throw new BizException(ErrorCode.USERNAME_EXISTS, "该邮箱已被注册");
            }
        }

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(encoder.encode(password)); // BCrypt：随机盐内嵌，同密码不同密文
        user.setRole("VISITOR");
        user.setEmail(email);
        sysUserMapper.insert(user);

        // 游客档案：与 sys_user 一对一共享主键（项目决策：visitor.id = sys_user.id）
        Visitor visitor = new Visitor();
        visitor.setId(user.getId());
        visitor.setUserId(user.getId());
        visitor.setNickname(nickname == null || nickname.isBlank() ? username + "的旅程" : nickname);
        visitor.setInterestTags(List.of());
        visitorMapper.insert(visitor);

        // 绑定 VISITOR 角色
        SysRole visitorRole = sysRoleMapper.selectOne(
                new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, "VISITOR").last("LIMIT 1"));
        SysUserRole ur = new SysUserRole();
        ur.setUserId(user.getId());
        ur.setRoleId(visitorRole.getId());
        sysUserRoleMapper.insert(ur);

        Map<String, Object> data = new HashMap<>();
        data.put("userId", user.getId());
        data.put("username", user.getUsername());
        return data;
    }

    // ================= 登录：双方式 + 验证码 + 统一提示 + 常数时间 =================

    public Map<String, Object> login(String loginType, String username, String password,
                                     String email, String emailCode,
                                     String captchaId, String captchaCode, String clientIp) {
        // 认证维度限流：登录 5 次/分·IP（外部评审第 9 条补的缺口，slide9"加限流防爆破"）
        rateLimit(clientIp);

        SysUser user;
        if ("EMAIL_CODE".equals(loginType)) {
            // 邮箱验证码登录
            verifyEmailCode(email, emailCode, "LOGIN");
            user = sysUserMapper.selectOne(
                    new LambdaQueryWrapper<SysUser>().eq(SysUser::getEmail, email).last("LIMIT 1"));
            if (user == null) {
                // 统一提示：不暴露"邮箱未注册"
                throw new BizException(ErrorCode.BAD_CREDENTIALS, "账号或密码错误");
            }
        } else {
            // 用户名密码登录：先校验图形验证码（防爆破，slide9）
            captchaService.verify(captchaId, captchaCode);
            user = sysUserMapper.selectOne(
                    new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username).last("LIMIT 1"));
            if (user == null) {
                // slide9：账号不存在时仍执行一次哈希再失败，防时序攻击
                encoder.matches(password, DUMMY_HASH);
                throw new BizException(ErrorCode.BAD_CREDENTIALS, "账号或密码错误");
            }
            if (!encoder.matches(password, user.getPassword())) {
                // 统一提示，不区分"密码错误/账号不存在"
                throw new BizException(ErrorCode.BAD_CREDENTIALS, "账号或密码错误");
            }
        }

        // Sa-Token 会话：token 存 Redis，登出即失效（slide12"登出是服务端动作"）
        StpUtil.login(user.getId());
        String refreshToken = issueRefreshToken(user.getId());

        Map<String, Object> data = new HashMap<>();
        data.put("tokenName", StpUtil.getTokenName());
        data.put("tokenValue", StpUtil.getTokenValue());
        data.put("refreshToken", refreshToken);
        data.put("userId", user.getId());
        data.put("username", user.getUsername());
        data.put("role", user.getRole());
        return data;
    }

    /** 登出：服务端销毁会话 + 吊销 refresh（slide12：只清前端存储是学生坑） */
    public void logout() {
        Object loginId = StpUtil.getLoginIdDefaultNull();
        if (loginId != null) {
            redis.delete(REFRESH_PREFIX + loginId);
        }
        StpUtil.logout();
    }

    /** 刷新令牌：refresh 换新 access（slide12：续期不重输密码） */
    public Map<String, Object> refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "refresh token 缺失");
        }
        String key = REFRESH_PREFIX + refreshToken;
        String loginId = redis.opsForValue().get(key);
        if (loginId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "refresh token 已失效，请重新登录");
        }
        redis.delete(key); // 旋转：旧 refresh 作废，发新的
        StpUtil.login(Long.valueOf(loginId));
        String newRefresh = issueRefreshToken(Long.valueOf(loginId));
        Map<String, Object> data = new HashMap<>();
        data.put("tokenName", StpUtil.getTokenName());
        data.put("tokenValue", StpUtil.getTokenValue());
        data.put("refreshToken", newRefresh);
        return data;
    }

    /** 当前登录用户信息（AUTH-4） */
    public Map<String, Object> currentUser() {
        long userId = StpUtil.getLoginIdAsLong();
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("userId", user.getId());
        data.put("username", user.getUsername());
        data.put("role", user.getRole());
        data.put("email", user.getEmail()); // 用户本人可见自己的邮箱（脱敏边界：他人不可见）
        return data;
    }

    // ================= 邮箱验证码（邮箱登录 / 忘记密码两用） =================

    public void sendEmailCode(String email, String scene) {
        String code = String.format("%06d", random.nextInt(1_000_000));
        redis.opsForValue().set(EMAIL_CODE_PREFIX + scene + ":" + email, code, EMAIL_CODE_TTL);
        boolean mailSent = trySendMail(email, code, scene);
        if (!mailSent) {
            // SMTP 未配置时降级：验证码打进日志（课设演示环境），生产必配邮件服务
            log.info("[邮箱验证码降级] scene={} email={} code={}", scene, email, code);
        }
    }

    public void resetPassword(String email, String emailCode, String newPassword) {
        verifyEmailCode(email, emailCode, "RESET");
        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getEmail, email).last("LIMIT 1"));
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "该邮箱未绑定账号");
        }
        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setPassword(encoder.encode(newPassword));
        sysUserMapper.updateById(update);
    }

    /** 校验邮箱验证码（一次性） */
    private void verifyEmailCode(String email, String code, String scene) {
        if (email == null || code == null) {
            throw new BizException(ErrorCode.CAPTCHA_INVALID, "邮箱验证码无效或已过期");
        }
        String key = EMAIL_CODE_PREFIX + scene + ":" + email;
        String saved = redis.opsForValue().get(key);
        redis.delete(key);
        if (saved == null || !saved.equals(code.trim())) {
            throw new BizException(ErrorCode.CAPTCHA_INVALID, "邮箱验证码无效或已过期");
        }
    }

    /** 尝试真实发信；SMTP 未配置返回 false（调用方降级到日志） */
    private boolean trySendMail(String to, String code, String scene) {
        var mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            return false;
        }
        try {
            var msg = mailSender.createMimeMessage();
            var helper = new org.springframework.mail.javamail.MimeMessageHelper(msg, false, "UTF-8");
            helper.setTo(to);
            helper.setSubject("景区导览 AI 数字人 · 验证码");
            helper.setText("您的验证码为：" + code + "（10 分钟内有效，场景：" + scene + "）。如非本人操作请忽略。");
            mailSender.send(msg);
            return true;
        } catch (Exception e) {
            log.warn("邮件发送失败（将降级为日志输出验证码）：{}", e.getMessage());
            return false;
        }
    }

    private String issueRefreshToken(long userId) {
        String token = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set(REFRESH_PREFIX + userId, token, REFRESH_TTL);
        return token;
    }

    /** 认证维度限流：同 IP 每分钟 5 次登录尝试 */
    private void rateLimit(String clientIp) {
        String key = RATE_LIMIT_PREFIX + (clientIp == null ? "unknown" : clientIp);
        Long cnt = redis.opsForValue().increment(key);
        if (cnt != null && cnt == 1) {
            redis.expire(key, Duration.ofMinutes(1));
        }
        if (cnt != null && cnt > 5) {
            throw new BizException(ErrorCode.RATE_LIMITED, "尝试过于频繁，请稍后再试");
        }
    }

    /** 供 AuthController 注入 JavaMailSender（可选依赖：未配置 SMTP 也能启动，getIfAvailable 返回 null） */
    private final ObjectProvider<org.springframework.mail.javamail.JavaMailSender> mailSenderProvider;

    /** 固定 dummy hash：账号不存在时也执行一次 matches，保持常数时间（slide9） */
    private static final String DUMMY_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
}
