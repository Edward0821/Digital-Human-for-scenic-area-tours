package com.scenic.digitalhuman.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.scenic.digitalhuman.entity.QaRecord;
import com.scenic.digitalhuman.mapper.QaRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 定时任务 1（A3）：qaRecordCleanupTask —— 每天 03:00 清理过期数据。
 * ① 30 天前的匿名会话问答记录（user_id IS NULL：游客数据，不留持久化）
 * ② 超期未完成的分片 tmp 目录（上传中途放弃的临时文件）
 *
 * 口径：
 * - 任务体整体 try-catch 不上抛：异常被吞掉，不影响下一次触发（Spring 调度器语义）。
 * - Redis SETNX 防重入：多实例/超时重入时只跑一份。
 * - cron 可调短验证（如改 "0 * * * * *" 每分钟），验证后改回。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QaRecordCleanupTask {

    /** 匿名问答保留天数 */
    @Value("${app.cleanup.qa-retain-days:30}")
    private int retainDays;

    /** 分片临时目录保留小时数（超期未完成即清理） */
    @Value("${app.cleanup.tmp-retain-hours:24}")
    private int tmpRetainHours;

    @Value("${app.upload.dir:./uploads}")
    private String uploadDir;

    private final QaRecordMapper qaRecordMapper;
    private final TaskLock taskLock;

    /** 每天 03:00（验证时把 cron 改成 "0 * * * * *" 每分钟跑） */
    @Scheduled(cron = "${app.cleanup.qa-cron:0 0 3 * * *}")
    public void cleanup() {
        if (!taskLock.tryLock("qa-record-cleanup", 600)) {
            log.info("[定时任务] QA 记录清理：上一次仍在运行，跳过本轮");
            return;
        }
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(retainDays);
            int deleted = qaRecordMapper.delete(new LambdaQueryWrapper<QaRecord>()
                    .isNull(QaRecord::getUserId)
                    .lt(QaRecord::getCreatedAt, cutoff));
            log.info("[定时任务] QA 记录清理：删除 {} 天前匿名问答 {} 条", retainDays, deleted);

            cleanTmpDir();
        } catch (Exception e) {
            // 整体 try-catch 不上抛（A3 规矩）：异常只记日志，下一次触发照常
            log.error("[定时任务] QA 记录清理失败（不影响下次触发）", e);
        } finally {
            taskLock.unlock("qa-record-cleanup");
        }
    }

    /** 清理超期的分片临时目录（只删 tmp/ 下以时间戳命名的目录，不碰最终目录） */
    private void cleanTmpDir() {
        Path tmpRoot = Path.of(uploadDir, "tmp");
        if (!Files.exists(tmpRoot) || !Files.isDirectory(tmpRoot)) {
            return;
        }
        long cutoff = System.currentTimeMillis() - (long) tmpRetainHours * 3600_000;
        int removed = 0;
        try (var stream = Files.list(tmpRoot)) {
            List<Path> dirs = stream.filter(Files::isDirectory).toList();
            for (Path dir : dirs) {
                try {
                    if (Files.getLastModifiedTime(dir).toMillis() < cutoff) {
                        try (var walk = Files.walk(dir)) {
                            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (Exception ignore) {
                                    // 单个文件删不掉不中断整批
                                }
                            });
                        }
                        removed++;
                    }
                } catch (Exception e) {
                    log.debug("[定时任务] 临时目录清理跳过：{} err={}", dir, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[定时任务] 扫描临时目录失败", e);
        }
        log.info("[定时任务] 分片临时目录清理：删除 {} 个超期目录", removed);
    }
}
