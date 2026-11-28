package com.scenic.digitalhuman.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

/**
 * 定时任务 3（A3）：recommendRefreshTask —— 每 10 分钟预热/过期清理画像 key。
 *
 * 配合 Flink 用户兴趣计算（组长任务）：Flink 把画像写进 Redis scenic:profile:{sessionId}，
 * 本任务负责两件事：
 * ① 扫出即将过期的 profile key 续期（活跃会话画像保留）；
 * ② 清掉已被 Flink 标记删除的空壳 key（避免推荐接口返回空壳卡片——前端口径）。
 * Flink 未上线前本任务空转打日志，不影响系统启动。
 *
 * 口径同 A3：整体 try-catch 不上抛 + SETNX 防重入 + cron 可调短验证。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecommendRefreshTask {

    private static final String PROFILE_PREFIX = "scenic:profile:";
    private static final Duration PROFILE_TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;
    private final TaskLock taskLock;

    /** Flink 画像 TTL 阈值：剩余不足 1 天的续期 */
    @Value("${app.recommend.refresh-threshold-seconds:86400}")
    private long refreshThresholdSeconds;

    /** 每 10 分钟（验证时改 "${app.recommend.cron:0 * * * * *}" 每分钟） */
    @Scheduled(cron = "${app.recommend.cron:0 0/10 * * * *}")
    public void refreshProfiles() {
        if (!taskLock.tryLock("recommend-refresh", 480)) {
            log.info("[定时任务] 推荐画像刷新：上一次仍在运行，跳过本轮");
            return;
        }
        try {
            Set<String> keys = redis.keys(PROFILE_PREFIX + "*");
            if (keys == null || keys.isEmpty()) {
                log.debug("[定时任务] 推荐画像刷新：暂无 profile key（Flink 未上线时正常）");
                return;
            }
            int refreshed = 0;
            int removed = 0;
            for (String key : keys) {
                Long ttl = redis.getExpire(key);
                String value = redis.opsForValue().get(key);
                if (value == null || value.isBlank() || "{}".equals(value)) {
                    redis.delete(key);
                    removed++;
                } else if (ttl != null && ttl >= 0 && ttl < refreshThresholdSeconds) {
                    redis.expire(key, PROFILE_TTL);
                    refreshed++;
                }
            }
            log.info("[定时任务] 推荐画像刷新：扫描 {} 个，续期 {} 个，清理空壳 {} 个",
                    keys.size(), refreshed, removed);
        } catch (Exception e) {
            log.error("[定时任务] 推荐画像刷新失败（不影响下次触发）", e);
        } finally {
            taskLock.unlock("recommend-refresh");
        }
    }
}
