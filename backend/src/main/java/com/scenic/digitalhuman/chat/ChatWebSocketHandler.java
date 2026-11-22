package com.scenic.digitalhuman.chat;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scenic.digitalhuman.service.QaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.Map;

/**
 * 实时聊天 WebSocket 处理器（第十一周任务A核心，对照 PPT P04/P06/P13/P16）。
 *
 * 四件事：
 * 1. 建连：解析 sessionId（缺失直接关闭）；握手鉴权（token 参数可选——带了且无效才拒绝，
 *    匿名游客允许，PPT P16）；登记本地会话表 + Redis 在线表 online:chat:{sessionId}="gw1|web"
 *    EX 90（PPT P13：TTL 防僵尸——进程被杀来不及注销，90 秒后名单自动过期）。
 * 2. 心跳：文本帧 ping → 回 pong 并给在线表续期（原样再 SET 重置 90 秒）。
 *    30 秒间隔 ×3 容忍一次丢失 = 90 秒空闲超时（PPT P07：太长被防火墙回收，太短被当攻击）。
 * 3. chat 帧：SETNX chat:clientid:{clientId} 粗去重（重复直接忽略，PPT P10）→
 *    INCR chat:seq:{sessionId} 服务端发号（不用时间戳：时钟不同步必乱序，PPT P11）→
 *    调 QaService.answerViaChat（复用热点缓存 + Kafka 落库链路）→ 回 ack 帧。
 * 4. 断连：比对在线表 value 后再删（防误删另一台设备续期的新值，PPT P13）。
 *
 * 会话归属（sessionId/userId）存 ChatSessionRegistry，不用 session.getAttributes()
 * （该 API 在部分容器建连阶段返回 null，实测 NPE，见登记表类注释）。
 *
 * 帧契约（接口文档 v1.3，改契约先在群里说，文档是唯一契约源）：
 * C→S chat {clientId, text}；S→C ack {clientId, seq}；answer 帧由 consumer 推送。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final String ONLINE_PREFIX = "online:chat:";
    private static final String CLIENTID_PREFIX = "chat:clientid:";
    private static final String SEQ_PREFIX = "chat:seq:";
    /** 在线表值（网关编号|来源端，单机演示固定 gw1|web） */
    private static final String ONLINE_VALUE = "gw1|web";
    private static final Duration ONLINE_TTL = Duration.ofSeconds(90);
    /** clientId 占坑 TTL：覆盖消息保留期（Kafka 24h），重发窗口内都拦得住 */
    private static final Duration CLIENTID_TTL = Duration.ofHours(24);
    /** 问题长度上限与 HTTP /qa 口径一致（500 字） */
    private static final int MAX_TEXT_LEN = 500;

    private final ChatSessionRegistry registry;
    private final QaService qaService;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = queryParam(session, "sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("[WS聊天] sessionId 缺失，关闭连接：{}", session.getRemoteAddress());
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        // 握手鉴权（PPT P16）：token 可选——匿名游客允许；带了但无效才拒绝
        Long userId = null;
        String token = queryParam(session, "token");
        if (token != null && !token.isBlank()) {
            Object loginId = StpUtil.getLoginIdByToken(token);
            if (loginId == null) {
                log.warn("[WS聊天] token 无效，拒绝连接：sessionId={}", sessionId);
                session.close(CloseStatus.NOT_ACCEPTABLE);
                return;
            }
            userId = Long.valueOf(loginId.toString());
        }

        registry.register(sessionId, session, userId);
        redis.opsForValue().set(ONLINE_PREFIX + sessionId, ONLINE_VALUE, ONLINE_TTL);
        log.info("[WS聊天] 连接建立：sessionId={} userId={} 本地在线会话数={}",
                sessionId, userId, registry.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();

        // 心跳：兼容纯文本 "ping" 与 JSON {"type":"ping"} 两种（契约：ping/pong 无字段）
        if ("ping".equalsIgnoreCase(payload.trim())) {
            session.sendMessage(new TextMessage("pong"));
            renewOnline(registry.sessionIdOf(session));
            return;
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(payload);
        } catch (Exception e) {
            log.warn("[WS聊天] 非法帧忽略（非 JSON）：{}", e.getMessage());
            return;
        }

        String type = node.path("type").asText("");
        if ("ping".equals(type)) {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of("type", "pong"))));
            renewOnline(registry.sessionIdOf(session));
            return;
        }
        if (!"chat".equals(type)) {
            log.warn("[WS聊天] 未知帧类型忽略：{}", type);
            return;
        }

        String sessionId = registry.sessionIdOf(session);
        if (sessionId == null) {
            log.warn("[WS聊天] 未登记连接的 chat 帧，忽略");
            return;
        }
        Long userId = registry.userIdOf(session);

        String clientId = node.path("clientId").asText("");
        String text = node.path("text").asText("");
        if (clientId.isBlank() || text.isBlank() || text.length() > MAX_TEXT_LEN) {
            log.warn("[WS聊天] chat 帧字段不合法，忽略：sessionId={} clientId={} len={}",
                    sessionId, clientId, text.length());
            return;
        }

        // 幂等第一层（PPT P10）：clientId 占坑，客户端重发同一条直接忽略
        Boolean first = redis.opsForValue().setIfAbsent(CLIENTID_PREFIX + clientId, "1", CLIENTID_TTL);
        if (!Boolean.TRUE.equals(first)) {
            log.info("[WS聊天] 重复 chat 忽略（SETNX 命中）：clientId={}", clientId);
            return;
        }

        // 服务端发号（PPT P11）：INCR 原子自增，会话内严格递增
        long seq = redis.opsForValue().increment(SEQ_PREFIX + sessionId);

        try {
            // 复用答案生成 + Kafka 落库链路；answer 帧由 consumer 落库后推送
            qaService.answerViaChat(sessionId, userId, text, clientId, seq);
        } catch (Exception e) {
            // 入队失败：释放幂等键，客户端稍后重发同一条 clientId 才能再走一遍（否则消息永久丢失）
            redis.delete(CLIENTID_PREFIX + clientId);
            log.error("[WS聊天] 消息处理失败，释放幂等键：clientId={}", clientId, e);
            return;
        }

        // 回 ack：前端把"发送中"变对勾（PPT P10）
        String ack = objectMapper.writeValueAsString(
                Map.of("type", "ack", "clientId", clientId, "seq", seq));
        session.sendMessage(new TextMessage(ack));
        log.info("[WS聊天] chat 受理：sessionId={} seq={} clientId={}", sessionId, seq, clientId);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        cleanup(session, "closed " + status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.warn("[WS聊天] 传输异常：{}", exception.getMessage());
        cleanup(session, "transport error");
    }

    /** 断连清理：注销本地登记；在线表比对 value 后再删（PPT P13 防误删） */
    private void cleanup(WebSocketSession session, String reason) {
        String sessionId = registry.sessionIdOf(session);
        if (sessionId == null) {
            return; // 建连阶段就被拒绝的连接，无会话可清理
        }
        registry.unregister(sessionId, session);
        String key = ONLINE_PREFIX + sessionId;
        String current = redis.opsForValue().get(key);
        if (ONLINE_VALUE.equals(current)) {
            redis.delete(key); // 只删自己写的值；另一台设备续期出的新值不动
        }
        log.info("[WS聊天] 连接断开（{}）：sessionId={}", reason, sessionId);
    }

    /** 在线表续期：原样 SET 一遍，重置 90 秒 TTL（心跳即续命） */
    private void renewOnline(String sessionId) {
        if (sessionId != null) {
            redis.opsForValue().set(ONLINE_PREFIX + sessionId, ONLINE_VALUE, ONLINE_TTL);
        }
    }

    /** 从握手 URL 解析查询参数（/ws/qa?sessionId=xxx&token=yyy） */
    private String queryParam(WebSocketSession session, String name) {
        MultiValueMap<String, String> params =
                UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams();
        return params.getFirst(name);
    }
}
