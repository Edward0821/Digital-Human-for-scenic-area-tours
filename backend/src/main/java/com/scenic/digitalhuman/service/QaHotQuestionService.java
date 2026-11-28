package com.scenic.digitalhuman.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * QA-4 热门问题榜（第十二周追加任务·位 C）：Redis ZSet 按 ISO 周隔离计数。
 *
 * 契约（接口清单 v1.4 模块 3 / QA-4）：
 * - key = scenic:rank:hotq:{week}，week 为 ISO 周号（如 2026-W48），游客端 FAQ 展示
 * - 写侧在 QaRecordConsumer 落库成功后 ZINCRBY（问答问题文本为 member）
 * - 整键 TTL 8 天：跨周自然翻页，旧周榜单自动过期释放内存
 *
 * 为什么用 ZSet：ZINCRBY 单命令原子，天然扛并发提问的计数累加（同 INCR 口径）；
 * ZREVRANGE WITH SCORES 一次取回问题文本+频次，无需再回表查 qa_record。
 * 写侧失败只记日志不抛异常——榜单是统计副产品，不能影响已成功的问答落库主链路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QaHotQuestionService {

    /** 榜单 key 前缀：scenic:rank:hotq:{week} */
    public static final String HOTQ_PREFIX = "scenic:rank:hotq:";
    /** 整键 TTL：8 天（> 1 周，保证跨周切换时旧键仍在，但不会永久堆积） */
    private static final Duration HOTQ_TTL = Duration.ofDays(8);
    /** member 最大长度：防超长问题文本撑爆 ZSet（QA-1 提问上限 500 字，榜单只留前 200） */
    private static final int MAX_MEMBER_LENGTH = 200;

    private final StringRedisTemplate redis;

    /**
     * 当前 ISO 周号（YYYY-Www）。
     * 用 IsoFields 而非 WEEK_OF_YEAR：跨年那几天（如 2026-12-31 属于 2027-W01）
     * ISO 周历才与"周一为周首、本周四所在年为该周所属年"的口径一致。
     */
    public static String currentWeek() {
        LocalDate today = LocalDate.now();
        int week = today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        return today.get(IsoFields.WEEK_BASED_YEAR) + "-W" + (week < 10 ? "0" + week : week);
    }

    /** 拼 key：读写两侧唯一入口，避免手写字符串导致周格式不一致 */
    public static String weekKey(String week) {
        return HOTQ_PREFIX + (week == null || week.isBlank() ? currentWeek() : week);
    }

    /**
     * 写侧：QaRecordConsumer 落库成功后调用。
     * ZINCRBY 原子累加；每次写入重置 8 天 TTL（活跃周键不会在访问中途失效）。
     */
    public void recordQuestion(String question) {
        if (question == null || question.isBlank()) {
            return;
        }
        String member = question.trim();
        if (member.length() > MAX_MEMBER_LENGTH) {
            member = member.substring(0, MAX_MEMBER_LENGTH);
        }
        String key = weekKey(currentWeek());
        try {
            redis.opsForZSet().incrementScore(key, member, 1);
            redis.expire(key, HOTQ_TTL);
        } catch (Exception e) {
            // 统计副产品失败不阻断主链路：问答已落库、offset 照常提交
            log.warn("[QA-4] 热门问题计数写入失败（不影响主链路）：{}", e.getMessage());
        }
    }

    /**
     * 读侧：按 score 倒序取 topN，返回 [{rank, question, count}]。
     * week 为空取当前周；显式传周号可验证跨周 key 隔离（把周号改短/改前一周测）。
     */
    public List<Map<String, Object>> top(String week, int limit) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redis.opsForZSet().reverseRangeWithScores(weekKey(week), 0, (long) limit - 1);
        List<Map<String, Object>> list = new ArrayList<>();
        if (tuples == null || tuples.isEmpty()) {
            return list;
        }
        int rank = 1;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", rank++);
            row.put("question", tuple.getValue());
            row.put("count", tuple.getScore() == null ? 0L : tuple.getScore().longValue());
            list.add(row);
        }
        return list;
    }
}
