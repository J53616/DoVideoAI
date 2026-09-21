package com.example.server.dto;

/**
 * 一次业务任务的端到端追踪身份。
 *
 * <p>traceId/taskId 在请求提交时生成，并随 MQ 消息传播；submittedAtEpochMs 用于消费者
 * 计算真实排队时间。这个对象只携带身份，不携带用户目标或模型输入等敏感内容。</p>
 */
public record TraceContext(
        String traceId,
        String taskId,
        Long mediaId,
        Long userId,
        String taskType,
        long submittedAtEpochMs) {
}
