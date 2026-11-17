package com.scenic.digitalhuman.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scenic.digitalhuman.entity.QaRecord;
import com.scenic.digitalhuman.mapper.QaRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * QA 问答记录生产者（第十周作业题3：消息队列削峰落库）。
 *
 * 职责：controller 同步返回答案后，把问答记录发到 topic（key=sessionId 保证同会话顺序），
 * 由 consumer 按数据库节奏异步落库——高并发提问时写库不阻塞游客响应。
 *
 * 可靠性口径（对照分享组"消息不丢"三段配置）：
 * - 生产端 acks=all + retries=3 + enable.idempotence=true（幂等防重试重复发送）
 * - 消费端手动提交 offset（见 QaRecordConsumer）
 * - 兜底降级：发送失败（Kafka 不可用/超时）直接同步落库，系统照常工作——MQ 是削峰组件
 *   而不是单点依赖，答辩可讲"可用性优先于架构完备"
 * - 开关降级：app.kafka.enabled=false（JUnit 测试/无 Kafka 环境）直接同步落库
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QaRecordProducer {

    public static final String TOPIC = "scenic-qa-record";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final QaRecordMapper qaRecordMapper;

    @Value("${app.kafka.enabled:false}")
    private boolean kafkaEnabled;

    /** Kafka 开启则异步发送（发送失败自动降级落库）；关闭则直接同步落库 */
    public void send(QaRecord record) {
        if (!kafkaEnabled) {
            qaRecordMapper.insert(record);
            return;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(toPayload(record));
        } catch (Exception e) {
            // 序列化失败不可能让游客响应受影响：直接落库兜底
            log.error("[QA落库] 消息序列化失败，降级同步落库", e);
            qaRecordMapper.insert(record);
            return;
        }
        kafkaTemplate.send(TOPIC, record.getSessionId(), json).whenComplete((result, ex) -> {
            if (ex != null) {
                // 至少一次投递的失败兜底：MQ 不可用时同步写库，牺牲削峰保可用
                log.warn("[QA落库] Kafka 发送失败，降级同步落库：{}", ex.getMessage());
                qaRecordMapper.insert(record);
            }
        });
    }

    /** 消息体（camelCase JSON）：带 messageId 供 consumer 做业务幂等去重 */
    private Map<String, Object> toPayload(QaRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("messageId", java.util.UUID.randomUUID().toString());
        m.put("sessionId", r.getSessionId());
        m.put("userId", r.getUserId());
        m.put("question", r.getQuestion());
        m.put("answer", r.getAnswer());
        m.put("interactionType", r.getInteractionType());
        m.put("answerType", r.getAnswerType());
        m.put("imageUrl", r.getImageUrl());
        m.put("recognizedSpotId", r.getRecognizedSpotId());
        m.put("latencyMs", r.getLatencyMs());
        return m;
    }
}
