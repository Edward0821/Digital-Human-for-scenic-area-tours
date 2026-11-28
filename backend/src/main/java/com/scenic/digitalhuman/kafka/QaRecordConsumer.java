package com.scenic.digitalhuman.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scenic.digitalhuman.chat.ChatSessionRegistry;
import com.scenic.digitalhuman.entity.QaRecord;
import com.scenic.digitalhuman.mapper.QaRecordMapper;
import com.scenic.digitalhuman.service.QaHotQuestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * QA 问答记录消费者（第十周作业题3：异步落库 + 手动提交 offset + 业务幂等）。
 *
 * 三件事对应答辩口径：
 * 1. 削峰落库：提问高峰时消息在 topic 里排队，consumer 按固定节奏消费写库
 *    （consume-delay-ms 模拟推理/落库耗时），数据库永远按自己能承受的速度写入。
 * 2. 手动提交 offset（enable-auto-commit=false + Acknowledgment）：
 *    业务处理成功才提交——进程被杀/断电，未提交的消息重启后从上次进度重新消费，
 *    这就是"杀进程补数据"演示的原理（offset 存在 Kafka 内部主题 __consumer_offsets）。
 * 3. 至少一次投递 → 业务幂等去重：offset 未提交导致的重投用 Redis SETNX
 *    （messageId 去重键，TTL 覆盖消息保留期）拦截，保证 qa_record 不重复。
 *    时序：先占幂等键 → 落库 → 成功才提交 offset；落库失败会释放幂等键允许重试，
 *    避免"占了键没落库"造成的消息丢失（进程在占键后瞬间崩溃的极小窗口，生产环境
 *    可用 message_id 唯一索引兜底，课设口径讲清楚即可）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class QaRecordConsumer {

    /** 消费去重键前缀（messageId -> 已处理标记，TTL 覆盖消息保留期 24h） */
    private static final String CONSUMED_PREFIX = "scenic:qa:consumed:";

    private final ObjectMapper objectMapper;
    private final QaRecordMapper qaRecordMapper;
    private final StringRedisTemplate redis;
    private final ChatSessionRegistry chatSessionRegistry;
    private final QaHotQuestionService hotQuestionService;

    /** 每条消息处理延时（模拟推理/落库节奏，演示队列积压与消化；生产置 0） */
    @Value("${app.qa.consume-delay-ms:0}")
    private long consumeDelayMs;

    @KafkaListener(topics = QaRecordProducer.TOPIC, groupId = "scenic-qa-record-writer")
    public void onMessage(String message, Acknowledgment ack) {
        JsonNode node;
        String messageId;
        try {
            node = objectMapper.readTree(message);
            messageId = node.path("messageId").asText("");
        } catch (Exception e) {
            // 消息体损坏（正常流程不会发生）：记日志并确认跳过，避免毒丸消息阻塞分区
            log.error("[QA落库] 消息解析失败，跳过：{}", e.getMessage());
            ack.acknowledge();
            return;
        }

        // 业务幂等：重投的消息（offset 未提交就崩了）跳过重复落库
        String dedupKey = CONSUMED_PREFIX + messageId;
        Boolean first = redis.opsForValue().setIfAbsent(dedupKey, "1", Duration.ofHours(24));
        if (!Boolean.TRUE.equals(first)) {
            log.info("[QA落库] 重复消息跳过（幂等命中）：{}", messageId);
            ack.acknowledge();
            return;
        }

        try {
            // 演示削峰：模拟每次落库的推理/IO 耗时，压测时可见队列积压与消化过程
            if (consumeDelayMs > 0) {
                Thread.sleep(consumeDelayMs);
            }

            QaRecord record = new QaRecord();
            record.setSessionId(node.path("sessionId").asText());
            // 第十一周实时聊天：seq/clientId 由 WS 通道产生，HTTP 批量问答消息无此键（null-safe）
            record.setSeq(node.hasNonNull("seq") ? node.get("seq").asLong() : null);
            record.setClientId(node.hasNonNull("clientId") ? node.get("clientId").asText() : null);
            record.setUserId(node.hasNonNull("userId") ? node.get("userId").asLong() : null);
            record.setQuestion(node.path("question").asText());
            record.setAnswer(node.path("answer").asText());
            record.setInteractionType(node.path("interactionType").asText("TEXT"));
            record.setAnswerType(node.path("answerType").asText("TEXT"));
            record.setImageUrl(node.hasNonNull("imageUrl") ? node.get("imageUrl").asText() : null);
            record.setRecognizedSpotId(node.hasNonNull("recognizedSpotId") ? node.get("recognizedSpotId").asLong() : null);
            record.setLatencyMs(node.path("latencyMs").asInt(0));
            // created_at 不设值：由数据库 DEFAULT CURRENT_TIMESTAMP 生成落库时间
            try {
                qaRecordMapper.insert(record);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 第十一周（PPT P10）：客户端断线重发同一条 chat，clientId 相同 → 撞
                // uk_client_id 唯一键 = 重复消息。语义：这轮问答已落过库，跳过即幂等成功，
                // 正常提交 offset，不释放幂等键、不重投（第二层防线：Redis messageId 键之上，
                // 数据库唯一键是最终兜底——两层任一命中都不会重复落库）
                log.info("[QA落库] 重复消息跳过（uk_client_id 命中）：clientId={}", record.getClientId());
                ack.acknowledge();
                return;
            }
            log.debug("[QA落库] id={} sessionId={} seq={} clientId={} question={}",
                    record.getId(), record.getSessionId(), record.getSeq(),
                    record.getClientId(), record.getQuestion());

            // 第十一周：落库成功后推 answer 帧（契约：{type:"answer",seq,question,answer,latencyMs}）。
            // 只推 WS 通道来的消息（seq != null；HTTP 旧链路消息无 seq，且本就没有 WS 会话）。
            // 推送失败仅记日志、不重试、不影响 offset 提交——sync 补拉兜底（PPT P12 推是加速拉是兜底）。
            if (record.getSeq() != null) {
                String answerFrame = objectMapper.writeValueAsString(Map.of(
                        "type", "answer",
                        "seq", record.getSeq(),
                        "question", record.getQuestion(),
                        "answer", record.getAnswer(),
                        "latencyMs", record.getLatencyMs()));
                boolean pushed = chatSessionRegistry.push(record.getSessionId(), answerFrame);
                if (!pushed) {
                    log.info("[QA落库] answer 推送未达（会话不在线/发送失败，sync 兜底）：sessionId={} seq={}",
                            record.getSessionId(), record.getSeq());
                }
            }

            // QA-4 写侧（第十二周）：落库成功后按问题文本 ZINCRBY 本周热门榜。
            // 放在幂等去重之后 → 同一条消息重投不会重复计数；服务内部吞异常 → 榜单故障不影响 ack。
            hotQuestionService.recordQuestion(record.getQuestion());

            // 处理成功才提交 offset（崩溃在 insert 后、ack 前：幂等键已占，重投会被跳过，不重复）
            ack.acknowledge();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            redis.delete(dedupKey); // 未处理完，释放幂等键，重启后重投
        } catch (Exception e) {
            log.error("[QA落库] 消息处理失败，释放幂等键交由容器重试：{}", e.getMessage(), e);
            redis.delete(dedupKey);
            throw new IllegalStateException("QA 落库失败，交由错误处理器重试", e);
        }
    }
}
