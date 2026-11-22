package com.scenic.digitalhuman.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问答记录表（QA-1：游客提问 → 系统回答，异步落库）。
 *
 * 落库走 Kafka（第十周作业题3）：controller 同步返回答案，qa_record 由 consumer
 * 按数据库节奏异步写入——高并发提问时写库削峰，不阻塞游客响应。
 * 匿名游客 user_id 为 NULL，会话以前端 UUID（session_id）关联。
 */
@Data
@TableName("qa_record")
public class QaRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话标识（前端 UUID，匿名交互关联键） */
    private String sessionId;

    /** 会话内消息序号（服务端 Redis INCR 发号，实时聊天通道用；HTTP 批量问答为 NULL） */
    private Long seq;

    /** 客户端消息幂等锚点（设备号+本地序号，重发去重；uk_client_id 唯一键兜底） */
    private String clientId;

    /** 提问用户标识（匿名 NULL，逻辑外键 -> sys_user.id） */
    private Long userId;

    /** 游客问题文本（或图片描述） */
    private String question;

    /** 系统回答文本 */
    private String answer;

    /** 交互输入类型（VOICE/TEXT/IMAGE） */
    private String interactionType;

    /** 回答输出形式（TEXT/VOICE/TEXT_VOICE） */
    private String answerType;

    /** 识景图片地址（仅 IMAGE 型） */
    private String imageUrl;

    /** 识景命中景点（未命中 NULL，逻辑外键 -> scenic_spot.id） */
    private Long recognizedSpotId;

    /** 游客评价（GOOD/BAD，NULL 未评价；满意度=好评率 BR-10） */
    private String satisfaction;

    /** 端到端时延毫秒（阈值 5000，BR-05） */
    private Integer latencyMs;

    /** 情感得分（-1.0~1.0，NULL 未分析） */
    private Double sentimentScore;

    /** 提问时间（异步落库，由数据库 DEFAULT CURRENT_TIMESTAMP 生成） */
    private LocalDateTime createdAt;
}
