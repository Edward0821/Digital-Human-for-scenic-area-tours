package com.scenic.digitalhuman.service;

import com.scenic.digitalhuman.entity.QaRecord;
import com.scenic.digitalhuman.kafka.QaRecordProducer;
import com.scenic.digitalhuman.mapper.QaRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
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

        String fingerprint = DigestUtils.md5DigestAsHex(
                question.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
        String hotKey = HOT_PREFIX + fingerprint;
        String countKey = COUNT_PREFIX + fingerprint;

        // 热点计数：INCR 单命令原子（作业题2"原子性"在本项目的直接实例）
        long count = redis.opsForValue().increment(countKey);
        redis.expire(countKey, COUNT_WINDOW);

        // 缓存命中：热点问题顺带延长 TTL（分享组口径：热点数据留存更久）
        String cached = redis.opsForValue().get(hotKey);
        boolean cacheHit = cached != null;
        String answer;
        if (cacheHit) {
            answer = cached;
            if (count >= hotCountThreshold) {
                redis.opsForValue().set(hotKey, answer, extendedTtl());
            }
        } else {
            answer = generateAnswer(question);
            redis.opsForValue().set(hotKey, answer,
                    count >= hotCountThreshold ? extendedTtl() : baseTtl());
        }

        int latencyMs = (int) (System.currentTimeMillis() - start);

        // 记录异步落库（题3）：Kafka 发送，失败自动降级同步写库（见 QaRecordProducer）
        QaRecord record = new QaRecord();
        record.setSessionId(sessionId);
        record.setUserId(userId);
        record.setQuestion(question);
        record.setAnswer(answer);
        record.setInteractionType(interactionType);
        record.setAnswerType("TEXT"); // 打样阶段统一文本回答；TTS 接入后变 TEXT_VOICE
        record.setImageUrl(imageUrl);
        record.setLatencyMs(latencyMs);
        qaRecordProducer.send(record);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answer", answer);
        data.put("answerType", "TEXT");
        data.put("latencyMs", latencyMs);
        data.put("cached", cacheHit);
        return data;
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
}
