package com.scenic.digitalhuman.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.scenic.digitalhuman.common.BizException;
import com.scenic.digitalhuman.common.ErrorCode;
import com.scenic.digitalhuman.entity.QaRecord;
import com.scenic.digitalhuman.kafka.QaRecordProducer;
import com.scenic.digitalhuman.mapper.QaRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 问答服务（QA-1 打样 + 第十周作业题2：Redis 热点问答缓存）。
 *
 * 高并发链路中的位置：Nginx 网关限流（题1，挡超额入口）→ 本服务热点缓存（题2，
 * 挡重复推理）→ Kafka 异步落库（题3，挡写库洪峰）。
 *
 * 热点缓存口径（对照课件分享的"热点数据缓存"实验）：
 * - 每次提问对问题指纹（md5）INCR 计数——单命令原子，无锁竞争（Redis 原子性的项目实例）
 * - 未命中缓存：生成答案即写入缓存（短 TTL，复问即秒回）
 * - 命中缓存且计数超阈值（热点）：延长缓存 TTL 至热点时长——热点问题留存更久
 * - TTL 全部加随机偏移：防大量 key 同时过期集体打库（缓存雪崩）
 * - 问题指纹缓存对不存在的输入同样生效（空值缓存思路，防缓存穿透反复打库）
 *
 * 答案生成为关键词规则打样（STUB）：第十一周替换为 RAG 管线
 * （文档 → 分片 → BGE 向量化 → Redis 向量检索）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QaService {

    /** 问题指纹 → 缓存答案 */
    private static final String HOT_PREFIX = "scenic:qa:hot:";
    /** 问题指纹 → 访问计数（热点判定依据） */
    private static final String COUNT_PREFIX = "scenic:qa:qcount:";
    /** 计数窗口（滑动 1 小时） */
    private static final Duration COUNT_WINDOW = Duration.ofHours(1);

    private final StringRedisTemplate redis;
    private final QaRecordProducer qaRecordProducer;
    private final QaRecordMapper qaRecordMapper;

    @Value("${app.qa.hot-count-threshold:2}")
    private int hotCountThreshold;
    @Value("${app.qa.hot-base-ttl-minutes:5}")
    private long hotBaseTtlMinutes;
    @Value("${app.qa.hot-extended-ttl-minutes:30}")
    private long hotExtendedTtlMinutes;
    @Value("${app.qa.hot-ttl-jitter-minutes:5}")
    private long hotTtlJitterMinutes;

    /** 回答生成打样入口（STUB，第十一周换 RAG） */
    public Map<String, Object> answer(String sessionId, Long userId, String question,
                                      String interactionType, String imageUrl) {
        long start = System.currentTimeMillis();
        CacheOutcome outcome = resolveAnswer(question);
        int latencyMs = (int) (System.currentTimeMillis() - start);

        // 记录异步落库（题3）：Kafka 发送，失败自动降级同步写库（见 QaRecordProducer）
        QaRecord record = new QaRecord();
        record.setSessionId(sessionId);
        record.setUserId(userId);
        record.setQuestion(question);
        record.setAnswer(outcome.answer());
        record.setInteractionType(interactionType);
        record.setAnswerType("TEXT"); // 打样阶段统一文本回答；TTS 接入后变 TEXT_VOICE
        record.setImageUrl(imageUrl);
        record.setLatencyMs(latencyMs);
        qaRecordProducer.send(record);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answer", outcome.answer());
        data.put("answerType", "TEXT");
        data.put("latencyMs", latencyMs);
        data.put("cached", outcome.hit());
        return data;
    }

    /**
     * 实时聊天通道答案入口（第十一周任务A，文档"复用 QaService 的答案生成"落地）：
     * 复用热点缓存 + Kafka 落库整条链路，仅额外携带 seq（WS 服务端 Redis INCR 发号）
     * 与 clientId（客户端幂等锚点）。本方法只负责"出答案 + 入队"，
     * answer 帧由 consumer 落库成功后经 ChatSessionRegistry 推送——推是加速，/qa/sync 兜底。
     */
    public QaRecord answerViaChat(String sessionId, Long userId, String question,
                                  String clientId, long seq) {
        long start = System.currentTimeMillis();
        CacheOutcome outcome = resolveAnswer(question);
        int latencyMs = (int) (System.currentTimeMillis() - start);

        QaRecord record = new QaRecord();
        record.setSessionId(sessionId);
        record.setSeq(seq);
        record.setClientId(clientId);
        record.setUserId(userId);
        record.setQuestion(question);
        record.setAnswer(outcome.answer());
        record.setInteractionType("TEXT");
        record.setAnswerType("TEXT"); // 打样阶段统一文本回答；TTS 接入后变 TEXT_VOICE
        record.setLatencyMs(latencyMs);
        qaRecordProducer.send(record);
        return record;
    }

    /**
     * 热点缓存解析（answer 与聊天通道共用）：问题指纹 INCR 计数 → 查缓存，
     * 未命中生成答案并写入缓存（热点延长 TTL，均带随机偏移防雪崩）。
     */
    private CacheOutcome resolveAnswer(String question) {
        String fingerprint = DigestUtils.md5DigestAsHex(
                question.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
        String hotKey = HOT_PREFIX + fingerprint;
        String countKey = COUNT_PREFIX + fingerprint;

        // 热点计数：INCR 单命令原子（作业题2"原子性"在本项目的直接实例）
        long count = redis.opsForValue().increment(countKey);
        redis.expire(countKey, COUNT_WINDOW);

        // 缓存命中：热点问题顺带延长 TTL（分享组口径：热点数据留存更久）
        String cached = redis.opsForValue().get(hotKey);
        if (cached != null) {
            if (count >= hotCountThreshold) {
                redis.opsForValue().set(hotKey, cached, extendedTtl());
            }
            return new CacheOutcome(cached, true);
        }
        String answer = generateAnswer(question);
        redis.opsForValue().set(hotKey, answer,
                count >= hotCountThreshold ? extendedTtl() : baseTtl());
        return new CacheOutcome(answer, false);
    }

    /** 缓存解析结果：answer + 是否命中（hit 供 HTTP 响应 cached 字段） */
    private record CacheOutcome(String answer, boolean hit) {
    }

    /**
     * 答案生成（STUB）：关键词规则打样，验证整条高并发链路；
     * 第十一周 RAG 管线就位后替换本方法（检索 → 重排 → 生成）。
     */
    private String generateAnswer(String question) {
        String q = question.trim().toLowerCase();
        if (q.contains("门票") || q.contains("票价") || q.contains("多少钱")) {
            return "成人票 80 元，学生/老人半价 40 元，1.2 米以下儿童免票。您可以在景区官方小程序或入口售票处购买。";
        }
        if (q.contains("时间") || q.contains("营业") || q.contains("开门") || q.contains("几点")) {
            return "景区开放时间为每天 8:00-17:30，17:00 停止入园；节假日延长至 18:00。";
        }
        if (q.contains("交通") || q.contains("怎么去") || q.contains("公交") || q.contains("地铁")) {
            return "可乘坐地铁 3 号线至景区站 B 口出站步行 5 分钟，或乘公交 12/45/102 路直达景区南门；自驾请导航至景区南停车场。";
        }
        if (q.contains("推荐") || q.contains("路线") || q.contains("游玩") || q.contains("攻略")) {
            return "经典半日游路线：南门入口 → 观湖步道 → 古寺文化区 → 索道观景台 → 特色美食街，全程约 4 小时。带老人小孩建议乘坐园区观光车。";
        }
        if (q.contains("厕所") || q.contains("洗手间") || q.contains("卫生间")) {
            return "最近的无障碍卫生间在游客中心东侧 50 米处；园区内每 200 米设有指示牌。";
        }
        return "您好，我是景区数字人导游！这个问题我还在学习中，您可以问我门票价格、开放时间、交通指引、游玩路线等问题；也可以到游客中心咨询人工服务台。";
    }

    /** 普通缓存 TTL：基础时长（无随机偏移意义不大，保持固定便于演示对照） */
    private Duration baseTtl() {
        return Duration.ofMinutes(hotBaseTtlMinutes);
    }

    /** 热点 TTL：延长时长 + 随机偏移（防雪崩：错开大量热点 key 的过期瞬间） */
    private Duration extendedTtl() {
        long minutes = hotExtendedTtlMinutes + ThreadLocalRandom.current().nextLong(hotTtlJitterMinutes + 1);
        return Duration.ofMinutes(minutes);
    }

    /**
     * 断线补拉（第十一周，PPT P12"推是加速，拉是兜底"）：
     * 按 seq 游标补齐某会话在 lastSeq 之后的消息（升序，LIMIT 500）。
     * 调用时机：WS 连接建立后 + 前端发现 seq 空洞时（answer 推送丢失的对账通道）。
     * WHERE seq > ? 天然排除 seq 为 NULL 的旧 HTTP 问答行（NULL 比较恒为假）。
     */
    public Map<String, Object> sync(String sessionId, long afterSeq) {
        List<QaRecord> rows = qaRecordMapper.selectList(new LambdaQueryWrapper<QaRecord>()
                .eq(QaRecord::getSessionId, sessionId)
                .gt(QaRecord::getSeq, afterSeq)
                .orderByAsc(QaRecord::getSeq)
                .last("LIMIT 500"));

        List<Map<String, Object>> messages = new ArrayList<>();
        long lastSeq = afterSeq;
        for (QaRecord r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", r.getSeq());
            m.put("question", r.getQuestion());
            m.put("answer", r.getAnswer());
            m.put("latencyMs", r.getLatencyMs());
            messages.add(m);
            if (r.getSeq() != null && r.getSeq() > lastSeq) {
                lastSeq = r.getSeq();
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("messages", messages);
        result.put("lastSeq", lastSeq);
        return result;
    }

    /** 满意度取值白名单（QA-2）：只接受 GOOD/BAD，其他一律 10003 格式错 */
    private static final Set<String> SATISFACTION_VALUES = Set.of("GOOD", "BAD");

    /**
     * QA-2 满意度评价（第十二周追加任务·位 C）。
     *
     * 水平越权（IDOR）防护示范接口——答辩必讲：
     * 服务端不信任请求体里的 userId，只认两种归属证据：
     *   ① 登录态：record.user_id == 当前令牌解析出的 userId；
     *   ② 游客态：record.session_id == 请求携带的 session_id（匿名提问留下的匿名会话锚点）。
     * 两者都不匹配 → 403 + 20003。匿名评价必须带 sessionId，否则直接 10001（无归属证据）。
     *
     * 重复评价 → 409 + 20007（STATE_CONFLICT）。
     * 并发兜底：UPDATE 带 `satisfaction IS NULL` 条件，抢不到的返回 0 行 → 同样按 409 处理，
     * 避免两个请求同时读到的都是 NULL、双双写成功（单边校验挡不住并发竞态）。
     */
    @Transactional
    public void feedback(Long recordId, String satisfaction, Long userId, String sessionId) {
        if (satisfaction == null || !SATISFACTION_VALUES.contains(satisfaction)) {
            throw new BizException(ErrorCode.BAD_FORMAT, "满意度只能是 GOOD 或 BAD");
        }
        QaRecord record = qaRecordMapper.selectById(recordId);
        if (record == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "问答记录不存在");
        }
        // 归属校验必须先于状态检查（组长 review 修正 11-28）：
        // 若先查 satisfaction，陌生人可借 20007 探测"该记录已被评价"——顺序即泄漏。
        // 正确顺序：先验证请求者身份（不归属一律 20003），身份过关后才允许看到记录状态。
        boolean owned = userId != null && userId.equals(record.getUserId());
        if (!owned && sessionId != null && !sessionId.isBlank()
                && sessionId.equals(record.getSessionId())) {
            owned = true;
        }
        if (!owned) {
            log.warn("[QA-2] 越权评价拦截：recordId={} 请求用户={} 请求会话={} 记录用户={}",
                    recordId, userId, sessionId, record.getUserId());
            throw new BizException(ErrorCode.FORBIDDEN, "无权评价该问答记录");
        }
        if (record.getSatisfaction() != null) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "该问答已评价，不能重复评价");
        }

        int updated = qaRecordMapper.update(null, new LambdaUpdateWrapper<QaRecord>()
                .eq(QaRecord::getId, recordId)
                .isNull(QaRecord::getSatisfaction)
                .set(QaRecord::getSatisfaction, satisfaction));
        if (updated == 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "该问答已评价，不能重复评价");
        }
    }

    /**
     * QA-3 会话历史分页（第十二周追加任务·位 C）。
     *
     * 两条查询路径的索引取舍（契约 1.4 + 接口清单 QA-3 原注）：
     * - 匿名：按 session_id 查 → 命中 idx_session_created(session_id, created_at)，天然有序，直接分页。
     * - 登录：按 user_id 查 → DDL 刻意不建 (user_id, created_at) 索引（避免写放大），
     *   走 idx_created_at 时间窗后内存过滤 user_id。课设千级记录规模代价可接受；
     *   答辩口径：规模上量再加 (user_id, created_at) 联合索引。
     *
     * 出参严格按契约 1.4：total + has_more + list（不用 total/list 裸返回）。
     */
    public Map<String, Object> history(String sessionId, Long userId, long page, long size) {
        LambdaQueryWrapper<QaRecord> query = new LambdaQueryWrapper<>();
        if (userId != null) {
            // 登录态：按 user_id 查全部历史；按 created_at 倒序引导 idx_created_at 扫描后过滤
            query.eq(QaRecord::getUserId, userId);
        } else {
            // 匿名态：必须带 sessionId（Controller 已校验），走 idx_session_created
            query.eq(QaRecord::getSessionId, sessionId);
        }
        query.orderByDesc(QaRecord::getCreatedAt).orderByDesc(QaRecord::getId);

        Page<QaRecord> pageResult = qaRecordMapper.selectPage(new Page<>(page, size), query);
        List<Map<String, Object>> list = new ArrayList<>();
        for (QaRecord r : pageResult.getRecords()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", r.getId());
            item.put("sessionId", r.getSessionId());
            item.put("question", r.getQuestion());
            item.put("answer", r.getAnswer());
            item.put("interactionType", r.getInteractionType());
            item.put("answerType", r.getAnswerType());
            item.put("satisfaction", r.getSatisfaction());
            item.put("latencyMs", r.getLatencyMs());
            item.put("createdAt", r.getCreatedAt());
            list.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", pageResult.getTotal());
        result.put("has_more", pageResult.getCurrent() < pageResult.getPages());
        result.put("list", list);
        return result;
    }
}
