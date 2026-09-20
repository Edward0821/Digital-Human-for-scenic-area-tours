package com.wanghangyu.digitalhuman.controller;

import com.wanghangyu.digitalhuman.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final RedisConnectionFactory redisConnectionFactory;

    /**
     * 健康检查：验证脚手架可启动、组件可连通。
     * MySQL / Redis 未启动时接口仍返回 200，对应组件标记为 DOWN。
     */
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("app", "digital-human-backend");
        data.put("status", "UP");
        data.put("time", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        Map<String, String> components = new LinkedHashMap<>();
        components.put("mysql", probeMysql());
        components.put("redis", probeRedis());
        data.put("components", components);
        return Result.success(data);
    }

    private String probeMysql() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return "UP";
        } catch (Exception e) {
            return "DOWN";
        }
    }

    private String probeRedis() {
        try {
            redisConnectionFactory.getConnection().ping();
            return "UP";
        } catch (Exception e) {
            return "DOWN";
        }
    }
}
