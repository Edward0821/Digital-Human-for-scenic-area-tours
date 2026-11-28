package com.scenic.digitalhuman.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.scenic.digitalhuman.common.BizException;
import com.scenic.digitalhuman.common.ErrorCode;
import com.scenic.digitalhuman.common.Result;
import com.scenic.digitalhuman.entity.SysLoginLog;
import com.scenic.digitalhuman.entity.SysOperationLog;
import com.scenic.digitalhuman.mapper.SysLoginLogMapper;
import com.scenic.digitalhuman.mapper.SysOperationLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计日志查询接口（第十二周 A2，契约 v1.4 增量 LOG-1/2/3）：全 ADMIN。
 *
 * 系统日志不落库（成本高），直接读 logback 落盘文件尾部——这正是
 * "审计你系统异常的窗口"；操作/登录日志查 sys_operation_log / sys_login_log。
 */
@Validated
@RestController
@RequestMapping("/api/v1/admin/logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final SysLoginLogMapper loginLogMapper;
    private final SysOperationLogMapper operationLogMapper;

    /** LOG-1 系统日志尾部：logs/scenic.log 的 tail N 行，支持级别 + 关键字过滤 */
    @SaCheckPermission("log:audit")
    @GetMapping("/system")
    public Result<Map<String, Object>> system(
            @RequestParam(defaultValue = "200") int lines,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String keyword) {
        if (lines < 1 || lines > 2000) {
            throw new BizException(ErrorCode.BAD_PARAM, "lines 范围 1~2000");
        }
        Path log = Path.of("logs", "scenic.log");
        List<String> all = List.of();
        if (Files.exists(log)) {
            try {
                // 只读文件尾部：Files.readAllLines 对日志量大的场景不理想，
                // 课设规模（200MB 上限、按天切分）可接受；生产可用 RandomAccessFile 反向读。
                all = Files.readAllLines(log, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new BizException(ErrorCode.SYSTEM_ERROR, "系统日志读取失败");
            }
        }
        String lvl = level == null || level.isBlank() ? null : level.trim().toUpperCase();
        String kw = keyword == null || keyword.isBlank() ? null : keyword.trim();
        List<Map<String, Object>> rows = new ArrayList<>();
        // 从尾部倒着扫，命中 lines 条即停——不必先全量过滤再截取
        for (int i = all.size() - 1; i >= 0 && rows.size() < lines; i--) {
            String line = all.get(i);
            if (kw != null && !line.contains(kw)) {
                continue;
            }
            if (lvl != null && !line.contains(lvl)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("line", i + 1);
            row.put("content", line);
            rows.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("file", log.toString());
        data.put("lines", rows.size());
        data.put("list", rows);
        return Result.success(data);
    }

    /** LOG-2 操作日志分页：action 过滤 */
    @SaCheckPermission("log:audit")
    @GetMapping("/operations")
    public Result<Map<String, Object>> operations(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String action) {
        if (page < 1 || size < 1 || size > 100) {
            throw new BizException(ErrorCode.BAD_PARAM, "page 从 1 开始，size 范围 1~100");
        }
        LambdaQueryWrapper<SysOperationLog> q = new LambdaQueryWrapper<>();
        if (action != null && !action.isBlank()) {
            q.eq(SysOperationLog::getAction, action.trim());
        }
        q.orderByDesc(SysOperationLog::getCreatedAt);
        Page<SysOperationLog> p = operationLogMapper.selectPage(new Page<>(page, size), q);
        return Result.success(pageData(p.getTotal(), p.getCurrent(), p.getPages(), p.getRecords()));
    }

    /** LOG-3 登录日志分页：success 过滤（连续失败 = 爆破信号） */
    @SaCheckPermission("log:audit")
    @GetMapping("/logins")
    public Result<Map<String, Object>> logins(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) Boolean success) {
        if (page < 1 || size < 1 || size > 100) {
            throw new BizException(ErrorCode.BAD_PARAM, "page 从 1 开始，size 范围 1~100");
        }
        LambdaQueryWrapper<SysLoginLog> q = new LambdaQueryWrapper<>();
        if (success != null) {
            q.eq(SysLoginLog::getSuccess, success ? 1 : 0);
        }
        q.orderByDesc(SysLoginLog::getCreatedAt);
        Page<SysLoginLog> p = loginLogMapper.selectPage(new Page<>(page, size), q);
        return Result.success(pageData(p.getTotal(), p.getCurrent(), p.getPages(), p.getRecords()));
    }

    /** 统一分页出参：total + has_more + list（契约 1.4） */
    private <T> Map<String, Object> pageData(long total, long current, long pages, List<T> records) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", total);
        data.put("has_more", current < pages);
        data.put("list", records);
        return data;
    }
}
