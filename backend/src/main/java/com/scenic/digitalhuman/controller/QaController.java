package com.scenic.digitalhuman.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.scenic.digitalhuman.common.BizException;
import com.scenic.digitalhuman.common.ErrorCode;
import com.scenic.digitalhuman.common.Result;
import com.scenic.digitalhuman.service.QaHotQuestionService;
import com.scenic.digitalhuman.service.QaService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final QaHotQuestionService hotQuestionService;

    /** 游客提问（QA-1）：同步返回答案，问答记录异步落库 */
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
        Long userId = currentUserId();

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

    /**
     * QA-2 满意度评价（第十二周追加任务·位 C）：POST /api/v1/qa/records/{recordId}/feedback
     * 可选鉴权——登录用户按 user_id 归属，匿名游客按 session_id 归属（服务端校验，防 IDOR）。
     * sessionId 支持查询参数或 X-Session-Id 请求头两种携带方式（前端两种都在用）。
     * 重复评价 409 + 20007；越权 403 + 20003；满意度非 GOOD/BAD → 10003。
     */
    @PostMapping("/records/{recordId}/feedback")
    public Result<Void> feedback(@PathVariable Long recordId,
                                 @Validated @RequestBody FeedbackReq req,
                                 @RequestParam(required = false) String sessionId,
                                 @RequestHeader(value = "X-Session-Id", required = false) String headerSessionId) {
        if (recordId == null || recordId <= 0) {
            throw new BizException(ErrorCode.BAD_PARAM, "recordId 必须是正整数");
        }
        String sid = (sessionId != null && !sessionId.isBlank()) ? sessionId : headerSessionId;
        qaService.feedback(recordId, req.satisfaction(), currentUserId(), sid);
        return Result.success();
    }

    /**
     * QA-3 会话历史（第十二周追加任务·位 C）：GET /api/v1/qa/records?sessionId=&page=&size=
     * 可选鉴权——登录按 user_id（idx_created_at 时间窗 + 内存过滤），匿名按 session_id（idx_session_created）。
     * 匿名必须带 sessionId：否则没归属证据，直接 10001（与 QA-2 口径一致）。
     * 分页严格按契约 1.4：page/size 入参，total + has_more + list 出参。
     */
    @GetMapping("/records")
    public Result<Map<String, Object>> records(
            @RequestParam(required = false) String sessionId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        Long userId = currentUserId();
        if (userId == null && (sessionId == null || sessionId.isBlank())) {
            throw new BizException(ErrorCode.BAD_PARAM, "匿名查询必须带 sessionId");
        }
        if (page < 1 || size < 1 || size > 100) {
            throw new BizException(ErrorCode.BAD_PARAM, "page 从 1 开始，size 范围 1~100");
        }
        return Result.success(qaService.history(sessionId, userId, page, size));
    }

    /**
     * QA-4 热门问题榜（第十二周追加任务·位 C）：GET /api/v1/qa/hot-questions?week=&limit=
     * 公开接口（游客端 FAQ 展示）。读 Redis ZSet scenic:rank:hotq:{week}，
     * week 缺省取当前 ISO 周号（2026-W48 形式），显式传 week 可验证跨周 key 隔离。
     * limit 缺省 10，上限 50（防一次拉爆内存）。
     */
    @GetMapping("/hot-questions")
    public Result<Map<String, Object>> hotQuestions(
            @RequestParam(required = false) String week,
            @RequestParam(defaultValue = "10") int limit) {
        if (limit < 1 || limit > 50) {
            throw new BizException(ErrorCode.BAD_PARAM, "limit 范围 1~50");
        }
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        String effectiveWeek = (week == null || week.isBlank())
                ? QaHotQuestionService.currentWeek() : week;
        data.put("week", effectiveWeek);
        data.put("list", hotQuestionService.top(effectiveWeek, limit));
        return Result.success(data);
    }

    /** 取当前登录用户 ID（可选鉴权接口共用）：异常/未登录一律按匿名处理 */
    private Long currentUserId() {
        try {
            Object loginId = StpUtil.getLoginIdDefaultNull();
            return loginId == null ? null : Long.valueOf(loginId.toString());
        } catch (Exception ignore) {
            // 未登录场景：Sa-Token 上下文异常按匿名处理（与 QA-1 原口径一致）
            return null;
        }
    }

    /** QA-2 请求体：满意度 GOOD/BAD（具体校验在 service，业务码精确到 10003） */
    public record FeedbackReq(String satisfaction) {
    }

    public record QaReq(
            @NotBlank(message = "sessionId 不能为空") String sessionId,
            @NotBlank(message = "问题不能为空") String question,
            String interactionType,
            String imageUrl) {
    }
}
