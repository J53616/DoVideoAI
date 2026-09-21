package com.example.server.service;

import com.example.server.dto.AnalysisMode;
import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.dto.TraceContext;
import com.example.server.repository.AgentTraceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTelemetryTraceTest {

    @Test
    @SuppressWarnings("unchecked")
    void recordsQueueAndOrderedStageTimelineForOneTask() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        SetOperations<String, String> sets = mock(SetOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);

        AgentTelemetry telemetry = new AgentTelemetry(
                redis, new ObjectMapper().findAndRegisterModules(), mock(AgentTraceRepository.class));
        TraceContext context = telemetry.startTask(
                7L, 9L, "goal", AnalysisMode.GENERAL, AnalysisTaskMsg.START_ANALYSIS);

        telemetry.recordQueueWait(context.traceId(), context.submittedAtEpochMs());
        telemetry.stage(context.traceId(), "CONSUME_ATTEMPT_1", System.nanoTime(), true);
        telemetry.finish(context.traceId(), "COMPLETED");

        Map<String, Object> snapshot = telemetry.latest(7L, "goal", AnalysisMode.GENERAL);
        assertEquals(context.traceId(), snapshot.get("traceId"));
        assertEquals(context.taskId(), snapshot.get("taskId"));
        assertEquals("COMPLETED", snapshot.get("status"));
        List<Map<String, Object>> timeline =
                (List<Map<String, Object>>) snapshot.get("stageTimeline");
        assertFalse(timeline.isEmpty());
        assertEquals("QUEUE_WAIT", timeline.getFirst().get("stage"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordsReportedTokensAndMarksEstimatedFallbackSeparately() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        SetOperations<String, String> sets = mock(SetOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);

        AgentTelemetry telemetry = new AgentTelemetry(
                redis, new ObjectMapper().findAndRegisterModules(), mock(AgentTraceRepository.class));
        TraceContext context = telemetry.startTask(
                8L, 9L, "token goal", AnalysisMode.GENERAL,
                AnalysisTaskMsg.START_ANALYSIS);
        telemetry.bind(context.traceId());

        telemetry.modelCall("PLANNER", "prompt", "response", 10L, 5L,
                1.0, 2.0, System.nanoTime());
        telemetry.modelCall("CRITIC", "abcd", "中", null, null,
                1.0, 2.0, System.nanoTime());

        Map<String, Object> snapshot = telemetry.latest(
                8L, "token goal", AnalysisMode.GENERAL);
        Map<String, Number> counters = (Map<String, Number>) snapshot.get("counters");
        assertEquals(2L, counters.get("modelCalls").longValue());
        assertEquals(11L, counters.get("inputTokens").longValue());
        assertEquals(6L, counters.get("outputTokens").longValue());
        assertEquals(17L, counters.get("totalTokens").longValue());
        assertEquals(1L, counters.get("tokenUsageReportedCalls").longValue());
        assertEquals(1L, counters.get("tokenUsageEstimatedCalls").longValue());
        assertEquals(17L, telemetry.currentUsage().estimatedTokens());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aggregatesToolSuccessFailureAndDuration() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        SetOperations<String, String> sets = mock(SetOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);

        AgentTelemetry telemetry = new AgentTelemetry(
                redis, new ObjectMapper().findAndRegisterModules(), mock(AgentTraceRepository.class));
        TraceContext context = telemetry.startTask(
                10L, 9L, "tool goal", AnalysisMode.GENERAL,
                AnalysisTaskMsg.START_ANALYSIS);
        telemetry.bind(context.traceId());

        telemetry.toolCallCurrent("OCR", System.nanoTime(), true);
        telemetry.toolCallCurrent("OCR", System.nanoTime(), false);

        Map<String, Object> snapshot = telemetry.latest(
                10L, "tool goal", AnalysisMode.GENERAL);
        Map<String, Number> counters = (Map<String, Number>) snapshot.get("counters");
        Map<String, Number> valuesSnapshot = (Map<String, Number>) snapshot.get("values");
        assertEquals(2L, counters.get("toolCalls").longValue());
        assertEquals(2L, counters.get("tool.OCR.calls").longValue());
        assertEquals(1L, counters.get("tool.OCR.successes").longValue());
        assertEquals(1L, counters.get("tool.OCR.failures").longValue());
        assertFalse(valuesSnapshot.get("tool.OCR.durationMsTotal").doubleValue() < 0);
        assertFalse(valuesSnapshot.get("tool.OCR.durationMsMax").doubleValue() < 0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void restoresPersistedTraceBeforeConsumerContinuesIt() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> redisValues = mock(ValueOperations.class);
        SetOperations<String, String> sets = mock(SetOperations.class);
        when(redis.opsForValue()).thenReturn(redisValues);
        when(redis.opsForSet()).thenReturn(sets);
        AgentTraceRepository repository = mock(AgentTraceRepository.class);
        TraceContext context = new TraceContext(
                "trace-1", "task-1", 11L, 9L,
                AnalysisTaskMsg.START_ANALYSIS, System.currentTimeMillis());
        when(repository.findByTraceId(11L, "trace-1")).thenReturn(Map.of(
                "status", "QUEUED",
                "counters", Map.of("modelCalls", 2),
                "values", Map.of("queueDurationMs", 15),
                "stageDurationMs", Map.of("DISPATCH", 5),
                "stageTimeline", List.of(),
                "estimatedCost", 0.25));

        AgentTelemetry telemetry = new AgentTelemetry(
                redis, new ObjectMapper().findAndRegisterModules(), repository);
        telemetry.resumeTask(context, "restore goal", AnalysisMode.GENERAL);
        telemetry.toolCallCurrent("ASR", System.nanoTime(), true);
        telemetry.finish(context.traceId(), "COMPLETED");

        Map<String, Object> snapshot = telemetry.byTraceId(11L, "trace-1");
        Map<String, Number> counters = (Map<String, Number>) snapshot.get("counters");
        assertEquals(2L, counters.get("modelCalls").longValue());
        assertEquals(1L, counters.get("tool.ASR.calls").longValue());
        assertEquals("COMPLETED", snapshot.get("status"));
        verify(repository).findByTraceId(11L, "trace-1");
    }
}
