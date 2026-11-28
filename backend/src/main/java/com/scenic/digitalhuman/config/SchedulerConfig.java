package com.scenic.digitalhuman.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 定时任务调度配置（第十二周任务 A3）。
 *
 * 池大小 4 是老师工程要点第 1 条：Spring 默认单线程调度器，
 * 一个慢任务会阻塞后面所有任务的触发——四个真场景任务各占一个线程，互不拖累。
 * setWaitForTasksToCompleteOnShutdown(true)：优雅停机时等正在跑的任务收尾，
 * 避免清理任务写到一半被 kill（配合 Redis SETNX 防重入）。
 */
@Configuration
public class SchedulerConfig {

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("scenic-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.setErrorHandler(t ->
                org.slf4j.LoggerFactory.getLogger(SchedulerConfig.class)
                        .error("[定时任务] 未捕获异常", t));
        scheduler.initialize();
        return scheduler;
    }
}
