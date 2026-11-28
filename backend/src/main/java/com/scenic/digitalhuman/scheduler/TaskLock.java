package com.scenic.digitalhuman.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 定时任务防重入锁（第十二周 A3）：Redis SETNX + TTL。
 *
 * 单实例部署下 @Scheduled 本身不会并发触发同一方法，但以下两种场景会重入：
 * ① 任务执行时间超过 cron 间隔（Spring 默认串行等待，恢复后可能连跑多次）；
 * ② 将来扩成多实例（每个实例各跑一遍）。SETNX 两场景都挡住。
 * TTL 兜底：进程崩在锁持有期间，锁会自动过期，不会永久卡死下一次触发。
 */
@Component
@RequiredArgsConstructor
public class TaskLock {

    private static final String PREFIX = "scenic:task:lock:";

    private final StringRedisTemplate redis;

    /**
     * 尝试获取任务锁：拿到返回 true，未拿到（别的实例/上次还在跑）返回 false。
     * @param name 任务名（唯一）
     * @param ttlSeconds 锁过期秒数，必须大于任务最长执行时间
     */
    public boolean tryLock(String name, long ttlSeconds) {
        return Boolean.TRUE.equals(redis.opsForValue()
                .setIfAbsent(PREFIX + name, "1", Duration.ofSeconds(ttlSeconds)));
    }

    /** 释放锁：只在业务成功后调用；异常时不释放也可（TTL 兜底），但正常流程及时释放更稳 */
    public void unlock(String name) {
        redis.delete(PREFIX + name);
    }
}
