package com.example.server.dto;

import java.time.Instant;
import java.util.Map;

/** 一段时间窗口内的端到端 Agent 基线指标。 */
public record TraceBaselineMetrics(
        Instant from,
        Instant to,
        int sampleCount,
        int terminalCount,
        int activeCount,
        int successCount,
        int failureCount,
        double successRate,
        MetricStats totalDurationMs,
        MetricStats queueDurationMs,
        MetricStats totalTokens,
        MetricStats estimatedCost,
        long modelCallAttempts,
        long modelCallRetries,
        double modelRetryRate,
        Map<String, Long> statusDistribution,
        Map<String, Long> failedStageDistribution,
        Map<String, ToolMetrics> tools) {

    public record MetricStats(
            int count,
            double average,
            double p50,
            double p95,
            double p99,
            double max) { }

    public record ToolMetrics(
            long calls,
            long successes,
            long failures,
            double failureRate,
            double totalDurationMs,
            double averageDurationMs,
            double maxDurationMs) { }
}
