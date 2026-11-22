package com.scenic.digitalhuman.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实时会话登记表（第十一周任务A）：sessionId -> 在用连接（会话 + 登录态）。
 *
 * 为什么需要：answer 帧由 consumer（Kafka 消费线程）在落库成功后推送，
 * 但 consumer 拿不到连接——本类就是 handler（建连/断连线程）与 consumer 之间的"接线板"。
 *
 * 为什么自己存 sessionId/userId 而不用 session.getAttributes()：
 * 该 API 在部分 Servlet 容器实现的建连阶段返回 null（本次联调实测 NPE），
 * 会话归属信息放登记表里自己管，不依赖容器行为——更稳也更直观。
 *
 * 为什么 session 用 ConcurrentWebSocketSessionDecorator 包一层：
 * 原生 WebSocketSession 不允许并发 send（handler 发 ack 与 consumer 推 answer
 * 是两个线程），装饰器内部排队 + 超时/缓冲上限保护，避免并发写崩连接。
 */
@Slf4j
@Component
public class ChatSessionRegistry {

    /** 发送超时 5 秒；缓冲上限 512KB（慢客户端堆积超限即断开，防止拖垮线程） */
    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int SEND_BUFFER_SIZE_LIMIT = 512 * 1024;

    /** 一条在用连接：包装后的会话 + 握手时解析出的登录态（匿名 NULL） */
    public record ChatConn(WebSocketSession session, Long userId) {
    }

    private final Map<String, ChatConn> conns = new ConcurrentHashMap<>();

    public void register(String sessionId, WebSocketSession session, Long userId) {
        conns.put(sessionId, new ChatConn(
                new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_SIZE_LIMIT),
                userId));
    }

    /**
     * 注销：仅当登记的还是"这条"连接时才删——断线重连竞态下，
     * 旧连接的 close 事件可能晚于新连接的 register 到达，误删会把新连接弄丢。
     */
    public void unregister(String sessionId, WebSocketSession session) {
        ChatConn current = conns.get(sessionId);
        if (current != null && current.session() instanceof ConcurrentWebSocketSessionDecorator decorator
                && decorator.getDelegate() == session) {
            conns.remove(sessionId, current);
        }
    }

    /** 反查：这条连接登记在哪个 sessionId 下（断连清理/收帧时用；未登记返回 null） */
    public String sessionIdOf(WebSocketSession session) {
        for (Map.Entry<String, ChatConn> e : conns.entrySet()) {
            if (e.getValue().session() instanceof ConcurrentWebSocketSessionDecorator decorator
                    && decorator.getDelegate() == session) {
                return e.getKey();
            }
        }
        return null;
    }

    /** 反查登录态（chat 帧处理时用；匿名 NULL） */
    public Long userIdOf(WebSocketSession session) {
        String sessionId = sessionIdOf(session);
        return sessionId == null ? null : conns.get(sessionId).userId();
    }

    /**
     * 推送一帧 JSON 文本（answer 帧由 consumer 调用）。
     * 返回 false = 会话不在线或发送失败。调用方只记日志不重试——sync 补拉兜底
     * （PPT P12"推是加速，拉是兜底"，推送丢了对账通道能补回）。
     */
    public boolean push(String sessionId, String json) {
        ChatConn conn = conns.get(sessionId);
        if (conn == null || !conn.session().isOpen()) {
            return false;
        }
        try {
            conn.session().sendMessage(new TextMessage(json));
            return true;
        } catch (IOException e) {
            log.warn("[WS聊天] answer 推送失败：sessionId={} {}", sessionId, e.getMessage());
            return false;
        }
    }

    /** 当前登记的在线会话数（观测用） */
    public int size() {
        return conns.size();
    }
}
