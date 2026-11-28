package com.scenic.digitalhuman.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.scenic.digitalhuman.common.BizException;
import com.scenic.digitalhuman.common.ErrorCode;
import com.scenic.digitalhuman.common.Result;
import com.scenic.digitalhuman.service.QaService;
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
import java.util.Set;

/**
 * 问答接口（QA-1 打样，第十周高并发链路入口）。
 *
 * 链路：游客提问 → [题1 Nginx 限流（部署层，反代前挡超额）] → 本接口：
 * Redis 热点缓存命中直接秒回（题2），未命中生成答案后写入缓存；
 * 问答记录经 Kafka 异步落库（题3），不阻塞游客响应。
 * 公开接口：匿名可问（user_id NULL，session_id 关联），登录用户自动关联 user_id。
 */
@Validated
@RestController
@RequestMapping("/api/v1/qa")
@RequiredArgsConstructor
public class QaController {

    private static final Set<String> INTERACTION_TYPES = Set.of("VOICE", "TEXT", "IMAGE");

    private final QaService qaService;

    /** 游客提问（QA-1）：同步返回答案，问答记录异步落库 */
    @com.scenic.digitalhuman.aop.OperationLog(action = "qa:ask", targetType = "qa", targetId = "#p0.sessionId()")
    @PostMapping
    public Result<Map<String, Object>> ask(@Validated @RequestBody QaReq req) {
        // 课件口径：问题超 500 字 → 10002（不用 @Size 是为了让业务码精确到 BAD_PAYLOAD）
        if (req.question() == null || req.question().length() > 500) {
            throw new BizException(ErrorCode.BAD_PAYLOAD, "问题不能为空且不能超过 500 字");
        }
        String interactionType = req.interactionType() == null || req.interactionType().isBlank()
                ? "TEXT"
                : req.interactionType().trim().toUpperCase();
        if (!INTERACTION_TYPES.contains(interactionType)) {
            throw new BizException(ErrorCode.BAD_FORMAT, "交互类型只能是 VOICE/TEXT/IMAGE");
        }

        // 匿名可问：未登录 user_id 为 NULL，登录用户自动关联（水平越权无虞：只读自己的登录态）
        Long userId = null;
        try {
            Object loginId = StpUtil.getLoginIdDefaultNull();
            if (loginId != null) {
                userId = Long.valueOf(loginId.toString());
            }
        } catch (Exception ignore) {
            // 未登录场景：Sa-Token 上下文异常按匿名处理
        }

        return Result.success(qaService.answer(req.sessionId(), userId, req.question(),
                interactionType, "IMAGE".equals(interactionType) ? req.imageUrl() : null));
    }

    /**
     * 断线补拉（QA-sync，第十一周）：WS 推送丢失/断线重连后，按 seq 游标对账补齐。
     * 契约：GET /api/v1/qa/sync?sessionId=<uuid>&afterSeq=<n>
     * 响应 data：{ messages: [{seq,question,answer,latencyMs}], lastSeq }，按 seq 升序，LIMIT 500。
     * 公开接口（游客可用，口径同 /qa 提问）。
     */
    @GetMapping("/sync")
    public Result<Map<String, Object>> sync(
            @RequestParam(required = false) String sessionId,
            @RequestParam(defaultValue = "0") long afterSeq) {
        // 不用 @NotBlank：参数缺失时业务码精确到 BAD_PAYLOAD，而非兜底 50000（同 ask() 口径）
        if (sessionId == null || sessionId.isBlank()) {
            throw new BizException(ErrorCode.BAD_PAYLOAD, "sessionId 不能为空");
        }
        if (afterSeq < 0) {
            throw new BizException(ErrorCode.BAD_FORMAT, "afterSeq 不能为负数");
        }
        return Result.success(qaService.sync(sessionId, afterSeq));
    }

    public record QaReq(
            @NotBlank(message = "sessionId 不能为空") String sessionId,
            @NotBlank(message = "问题不能为空") String question,
            String interactionType,
            String imageUrl) {
    }
}
