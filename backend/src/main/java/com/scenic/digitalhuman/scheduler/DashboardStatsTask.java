package com.scenic.digitalhuman.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.scenic.digitalhuman.entity.QaRecord;
import com.scenic.digitalhuman.mapper.QaRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 定时任务 2（A3）：dashboardStatsTask —— 每 5 分钟预聚合大屏统计进 Redis。
 *
 * 配合大屏前端分钟级刷新（决策 16）：前端 60s 拉一次接口，接口直接读 Redis 现成值，
 * 不让每次刷新都打 SQL 聚合。任务把"重计算"和"轻查询"解耦。
 *
 * 口径同 A3：整体 try-catch 不上抛 + SETNX 防重入 + cron 可调短验证。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashboardStatsTask {

    /** 大屏统计 key：按天隔离，跨天自动翻页 */
    public static final String STATS_PREFIX = "scenic:dashboard:stats:";
    private static final Duration STATS_TTL = Duration.ofDays(7);

    private final QaRecordMapper qaRecordMapper;
    private final StringRedisTemplate redis;
    private final TaskLock taskLock;

    /** 每 5 分钟（验证时改 "${app.dashboard.cron:0 * * * * *}" 每分钟） */
    @Scheduled(cron = "${app.dashboard.cron:0 0/5 * * * *}")
    public void refreshStats() {
        if (!taskLock.tryLock("dashboard-stats", 240)) {
            log.info("[定时任务] 大屏统计：上一次仍在运行，跳过本轮");
            return;
        }
        try {
            String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
            String key = STATS_PREFIX + today;

            LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
            List<QaRecord> todayRows = qaRecordMapper.selectList(new LambdaQueryWrapper<QaRecord>()
                    .ge(QaRecord::getCreatedAt, startOfDay));

            long totalQa = todayRows.size();
            long good = todayRows.stream().filter(r -> "GOOD".equals(r.getSatisfaction())).count();
            long bad = todayRows.stream().filter(r -> "BAD".equals(r.getSatisfaction())).count();
            double avgLatency = todayRows.stream()
                    .filter(r -> r.getLatencyMs() != null)
                    .mapToInt(QaRecord::getLatencyMs)
                    .average().orElse(0.0);

            redis.opsForValue().set(key,
                    String.format("qa=%d,good=%d,bad=%d,avgLatency=%.1f", totalQa, good, bad, avgLatency),
                    STATS_TTL);
            log.info("[定时任务] 大屏统计已刷新：key={} qa={} good={} bad={} avgLatency={}",
                    key, totalQa, good, bad, avgLatency);
        } catch (Exception e) {
            log.error("[定时任务] 大屏统计刷新失败（不影响下次触发）", e);
        } finally {
            taskLock.unlock("dashboard-stats");
        }
    }
}
