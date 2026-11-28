package com.scenic.digitalhuman.aop;

import cn.dev33.satoken.stp.StpUtil;
import com.scenic.digitalhuman.entity.SysOperationLog;
import com.scenic.digitalhuman.mapper.SysOperationLogMapper;
import com.scenic.digitalhuman.service.LoginLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

/**
 * 操作日志 AOP 切面（第十二周任务 A2 核心）：@Around 自动记录写操作。
 *
 * 关键口径：
 * 1. 审计绝不能影响主业务——切面里 try-catch 全吞，落库失败只记 ERROR 日志，
 *    原业务方法照常返回（pjp.proceed() 的结果原样吐回去）。
 * 2. 用户身份从 Sa-Token 取（不信任请求体里的 userId——防水平越权伪造审计主体）。
 * 3. IP/URI 从 HttpServletRequest 取；targetId 用 SpEL 从方法参数解析。
 * 4. @Around 而非 @AfterReturning：既能拿到执行结果，也能在业务抛异常时照常记录尝试。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OperationLogAspect {

    private final SysOperationLogMapper operationLogMapper;
    private final LoginLogService loginLogService;

    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer paramDiscoverer = new DefaultParameterNameDiscoverer();

    @Around("@annotation(opLog)")
    public Object around(ProceedingJoinPoint pjp, OperationLog opLog) throws Throwable {
        // finally 里记审计：业务正常返回或抛异常都留下"尝试过"的痕迹；
        // 审计自身的异常全吞，不改变业务返回值与异常语义。
        try {
            return pjp.proceed();
        } finally {
            record(pjp, opLog);
        }
    }

    private void record(ProceedingJoinPoint pjp, OperationLog opLog) {
        try {
            SysOperationLog entity = new SysOperationLog();
            entity.setAction(opLog.action());
            entity.setTargetType(opLog.targetType());
            entity.setTargetId(resolveTargetId(pjp, opLog.targetId()));

            try {
                Object loginId = StpUtil.getLoginIdDefaultNull();
                if (loginId != null) {
                    entity.setUserId(Long.valueOf(loginId.toString()));
                }
            } catch (Exception ignore) {
                // 游客操作：匿名写操作无登录态，userId 留 NULL
            }

            HttpServletRequest request = currentRequest();
            if (request != null) {
                entity.setUri(request.getRequestURI());
                entity.setMethod(request.getMethod());
                entity.setIp(loginLogService.clientIp(request));
            }
            // 用户名冗余快照：只在需要时查一次（避免每次操作多一次 DB 查询）
            if (entity.getUserId() != null) {
                entity.setUsername(loginLogService.usernameOf(entity.getUserId()));
            }
            operationLogMapper.insert(entity);
        } catch (Exception e) {
            // 审计失败不影响业务：异常对象作为最后一个参数传（demo2 考点，保留完整堆栈）
            log.error("[审计] 操作日志落库失败（不影响主业务）：action={}", opLog.action(), e);
        }
    }

    /** 从方法参数解析 targetId 的 SpEL 表达式；解析失败返回 null，不抛异常影响业务 */
    private String resolveTargetId(ProceedingJoinPoint pjp, String expression) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        try {
            MethodSignature sig = (MethodSignature) pjp.getSignature();
            Method method = sig.getMethod();
            String[] names = paramDiscoverer.getParameterNames(method);
            if (names == null) {
                return null;
            }
            EvaluationContext ctx = new StandardEvaluationContext();
            Object[] args = pjp.getArgs();
            for (int i = 0; i < names.length && i < args.length; i++) {
                ctx.setVariable(names[i], args[i]);
                // 兼容未开 -parameters 的构建：同时暴露 #p0/#p1 索引变量
                ctx.setVariable("p" + i, args[i]);
            }
            for (int i = 0; i < args.length; i++) {
                ctx.setVariable("p" + i, args[i]);
            }
            Object value = parser.parseExpression(expression).getValue(ctx);
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            log.debug("[审计] targetId SpEL 解析失败，留空：expr={} err={}", expression, e.getMessage());
            return null;
        }
    }

    private HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes sra ? sra.getRequest() : null;
    }
}
