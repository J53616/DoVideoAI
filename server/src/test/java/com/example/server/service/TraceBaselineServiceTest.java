package com.example.server.service;

import com.example.server.dto.TraceBaselineMetrics;
import com.example.server.repository.AgentTraceRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceBaselineServiceTest {

    @Test
    void calculatesTerminalRatesPercentilesAndToolMetrics() {
        AgentTraceRepository repository = mock(AgentTraceRepository.class);
        when(repository.findRecent(anyLong(), anyInt())).thenReturn(List.of(
                trace("COMPLETED", "2026-01-01T00:00:00Z", "2026-01-01T00:00:01Z",
                        100, 1_000, 3, 1, 2, 2, 0, 40),
                trace("BUDGET_EXHAUSTED", "2026-01-01T00:00:00Z", "2026-01-01T00:00:03Z",
                        300, 2_000, 2, 0, 1, 0, 1, 80),
                trace("QUEUED", "2026-01-01T00:00:00Z", null,
                        50, 0, 0, 0, 0, 0, 0, 0)));

        TraceBaselineMetrics result = new TraceBaselineService(repository).calculate(7);

        assertEquals(3, result.sampleCount());
        assertEquals(2, result.terminalCount());
        assertEquals(1, result.activeCount());
        assertEquals(1, result.successCount());
        assertEquals(1, result.failureCount());
        assertEquals(0.5, result.successRate());
        assertEquals(1_000, result.totalDurationMs().p50());
        assertEquals(3_000, result.totalDurationMs().p95());
        assertEquals(5, result.modelCallAttempts());
        assertEquals(1, result.modelCallRetries());
        assertEquals(3, result.tools().get("OCR").calls());
        assertEquals(1, result.tools().get("OCR").failures());
        assertEquals(120, result.tools().get("OCR").totalDurationMs());
        assertEquals(1L, result.failedStageDistribution().get("AGENT_LOOP"));
    }

    private Map<String, Object> trace(String status,
                                      String submittedAt,
                                      String finishedAt,
                                      double queueMs,
                                      long tokens,
                                      long modelAttempts,
                                      long modelRetries,
                                      long toolCalls,
                                      long toolSuccesses,
                                      long toolFailures,
                                      double toolDuration) {
        Map<String, Object> counters = Map.of(
                "totalTokens", tokens,
                "modelCallAttempts", modelAttempts,
                "modelCallRetries", modelRetries,
                "tool.OCR.calls", toolCalls,
                "tool.OCR.successes", toolSuccesses,
                "tool.OCR.failures", toolFailures);
        Map<String, Object> values = Map.of(
                "queueDurationMs", queueMs,
                "tool.OCR.durationMsTotal", toolDuration,
                "tool.OCR.durationMsMax", toolDuration);
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("status", status);
        result.put("submittedAt", submittedAt);
        result.put("finishedAt", finishedAt);
        result.put("counters", counters);
        result.put("values", values);
        result.put("estimatedCost", 0.01);
        result.put("stageTimeline", status.equals("BUDGET_EXHAUSTED")
                ? List.of(Map.of("stage", "AGENT_LOOP", "status", "FAILED"))
                : List.of());
        return result;
    }
}
