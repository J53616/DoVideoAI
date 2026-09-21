package com.example.server.service;

import com.example.server.dto.AnalysisMode;
import com.example.server.dto.TraceContext;
import com.example.server.repository.AgentTraceRepository;
import com.example.server.utils.AnalysisTaskKeys;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

@Service
public class AgentTelemetry {

    private static final Logger log = LoggerFactory.getLogger(AgentTelemetry.class);
    private static final int MAX_TRACES = 500;
    private static final Duration TRACE_TTL = Duration.ofDays(7);

    private final Map<String, TraceData> traces = new ConcurrentHashMap<>();
    private final Map<String, String> latestTraceByTask = new ConcurrentHashMap<>();
    private final ThreadLocal<String> currentTrace = new ThreadLocal<>();
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AgentTraceRepository traceRepository;

    public AgentTelemetry(StringRedisTemplate redisTemplate,
                          ObjectMapper objectMapper,
                          AgentTraceRepository traceRepository) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.traceRepository = traceRepository;
    }

    public String start(Long taskId, String goal) {
        return start(taskId, goal, AnalysisMode.GENERAL);
    }

    public String start(Long taskId, String goal, AnalysisMode mode) {
        String goalDigest = AnalysisTaskKeys.goalDigest(goal, mode);
        String taskKey = taskKey(taskId, goalDigest);
        if (traces.size() >= MAX_TRACES) {
            traces.values().stream()
                    .min(Comparator.comparing(trace -> trace.startedAt))
                    .ifPresent(trace -> {
                        traces.remove(trace.traceId);
                        latestTraceByTask.remove(trace.taskKey, trace.traceId);
                    });
        }
        String traceId = UUID.randomUUID().toString();
        traces.put(traceId, new TraceData(
                traceId, UUID.randomUUID().toString(), taskId, null,
                "INTERACTIVE", System.currentTimeMillis(), goalDigest, taskKey));
        latestTraceByTask.put(taskKey, traceId);
        currentTrace.set(traceId);
        persist(traces.get(traceId));
        log.info("agent_trace traceId={} taskId={} stage=START status=SUCCESS", traceId, taskId);
        return traceId;
    }

    /** 在任务进入 MQ 前创建 Trace，使排队时间也属于同一条链路。 */
    public TraceContext startTask(Long mediaId,
                                  Long userId,
                                  String goal,
                                  AnalysisMode mode,
                                  String taskType) {
        String traceId = UUID.randomUUID().toString();
        String executionTaskId = UUID.randomUUID().toString();
        long submittedAt = System.currentTimeMillis();
        String goalDigest = AnalysisTaskKeys.goalDigest(goal, mode);
        String taskKey = taskKey(mediaId, goalDigest);
        TraceData trace = new TraceData(
                traceId, executionTaskId, mediaId, userId, taskType,
                submittedAt, goalDigest, taskKey);
        traces.put(traceId, trace);
        latestTraceByTask.put(taskKey, traceId);
        persist(trace);
        log.info("agent_trace traceId={} executionTaskId={} mediaId={} stage=SUBMITTED status=SUCCESS",
                traceId, executionTaskId, mediaId);
        return new TraceContext(
                traceId, executionTaskId, mediaId, userId, taskType, submittedAt);
    }

    /** 消费者恢复提交端创建的 Trace；兼容单实例内存和未来的跨实例消息传播。 */
    public String resumeTask(TraceContext context, String goal, AnalysisMode mode) {
        if (context == null) return start(null, goal, mode);
        String goalDigest = AnalysisTaskKeys.goalDigest(goal, mode);
        String taskKey = taskKey(context.mediaId(), goalDigest);
        traces.computeIfAbsent(context.traceId(), ignored -> {
            try {
                Map<String, Object> persisted = traceRepository.findByTraceId(
                        context.mediaId(), context.traceId());
                if (persisted != null && !persisted.isEmpty()) {
                    return TraceData.restore(context, goalDigest, taskKey, persisted);
                }
            } catch (RuntimeException e) {
                // Trace 是观测能力，恢复失败不能阻止 MQ 中的真实分析任务继续执行。
                log.warn("agent_trace_restore_failed traceId={} mediaId={}",
                        context.traceId(), context.mediaId(), e);
            }
            return new TraceData(
                    context.traceId(), context.taskId(), context.mediaId(), context.userId(),
                    context.taskType(), context.submittedAtEpochMs(), goalDigest, taskKey);
        });
        latestTraceByTask.put(taskKey, context.traceId());
        currentTrace.set(context.traceId());
        return context.traceId();
    }

    public void recordQueueWait(String traceId, long submittedAtEpochMs) {
        TraceData trace = traces.get(traceId);
        if (trace == null || submittedAtEpochMs <= 0) return;
        long endedAt = System.currentTimeMillis();
        long durationMs = Math.max(0, endedAt - submittedAtEpochMs);
        trace.values.put("queueDurationMs", (double) durationMs);
        trace.addStage("QUEUE_WAIT", submittedAtEpochMs, endedAt, true);
        persist(trace);
    }

    public void status(String traceId, String status) {
        TraceData trace = traces.get(traceId);
        if (trace == null) return;
        trace.status = status;
        persist(trace);
    }

    public void finish(String traceId, String status) {
        TraceData trace = traces.get(traceId);
        if (trace == null) return;
        trace.status = status;
        trace.finishedAt = Instant.now();
        persist(trace);
    }

    public void bind(String traceId) {
        currentTrace.set(traceId);
    }

    public void clear() {
        currentTrace.remove();
    }

    public void flush(String traceId) {
        TraceData trace = traces.get(traceId);
        if (trace != null) persist(trace);
    }

    public void stage(String traceId, String stage, long startedNanos, boolean success) {
        TraceData trace = traces.get(traceId);
        if (trace == null) return;
        long durationMs = (System.nanoTime() - startedNanos) / 1_000_000;
        trace.stageDurations.merge(stage, durationMs, Long::sum);
        long endedAt = System.currentTimeMillis();
        trace.addStage(stage, Math.max(0, endedAt - durationMs), endedAt, success);
        trace.increment(stage + "Calls", 1);
        if (!success) trace.increment("failedStages", 1);
        log.info("agent_trace traceId={} taskId={} stage={} durationMs={} status={}",
                traceId, trace.executionTaskId, stage, durationMs, success ? "SUCCESS" : "FAILED");
        persist(trace);
    }

    public void increment(String traceId, String metric, long amount) {
        if (traceId == null) return;
        TraceData trace = traces.get(traceId);
        if (trace != null) trace.increment(metric, amount);
    }

    public void incrementCurrent(String metric, long amount) {
        String traceId = currentTrace.get();
        if (traceId != null) increment(traceId, metric, amount);
    }

    public void valueCurrent(String metric, double value) {
        String traceId = currentTrace.get();
        value(traceId, metric, value);
    }

    public void value(String traceId, String metric, double value) {
        TraceData trace = traceId == null ? null : traces.get(traceId);
        if (trace != null) trace.values.put(metric, value);
    }

    public void failCurrentStage(String stage, long startedNanos) {
        String traceId = currentTrace.get();
        if (traceId != null) stage(traceId, stage, startedNanos, false);
    }

    /**
     * 聚合外部工具调用指标，不逐次写入时间线，避免长视频的 OCR/ASR 明细撑大 Trace。
     */
    public void toolCall(String traceId, String tool, long startedNanos, boolean success) {
        TraceData trace = traceId == null ? null : traces.get(traceId);
        if (trace == null || tool == null || tool.isBlank()) return;
        long durationMs = Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
        String prefix = "tool." + tool.trim().toUpperCase();
        trace.increment("toolCalls", 1);
        trace.increment(prefix + ".calls", 1);
        trace.increment(prefix + (success ? ".successes" : ".failures"), 1);
        trace.values.merge(prefix + ".durationMsTotal", (double) durationMs, Double::sum);
        trace.values.merge(prefix + ".durationMsMax", (double) durationMs, Math::max);
    }

    public void toolCallCurrent(String tool, long startedNanos, boolean success) {
        toolCall(currentTrace.get(), tool, startedNanos, success);
    }

    public void modelCall(String stage,
                          String prompt,
                          String response,
                          Long reportedInputTokens,
                          Long reportedOutputTokens,
                          double inputPricePerMillion,
                          double outputPricePerMillion,
                          long startedNanos) {
        String traceId = currentTrace.get();
        TraceData trace = traceId == null ? null : traces.get(traceId);
        if (trace == null) return;

        boolean usageReported = reportedInputTokens != null && reportedInputTokens >= 0
                && reportedOutputTokens != null && reportedOutputTokens >= 0;
        long inputTokens = usageReported ? reportedInputTokens : estimateTokens(prompt);
        long outputTokens = usageReported ? reportedOutputTokens : estimateTokens(response);
        trace.increment("modelCalls", 1);
        trace.increment("inputTokens", inputTokens);
        trace.increment("outputTokens", outputTokens);
        trace.increment("totalTokens", inputTokens + outputTokens);
        if (usageReported) {
            trace.increment("tokenUsageReportedCalls", 1);
        } else {
            trace.increment("tokenUsageEstimatedCalls", 1);
            trace.increment("inputTokensEstimated", inputTokens);
            trace.increment("outputTokensEstimated", outputTokens);
        }
        trace.estimatedCost.add(
                inputTokens * inputPricePerMillion / 1_000_000D
                        + outputTokens * outputPricePerMillion / 1_000_000D);
        stage(traceId, stage, startedNanos, true);
    }

    public BudgetUsage currentUsage() {
        String traceId = currentTrace.get();
        TraceData trace = traceId == null ? null : traces.get(traceId);
        return trace == null
                ? new BudgetUsage(0, 0)
                : new BudgetUsage(
                trace.counterValue("totalTokens"),
                trace.estimatedCost.sum());
    }

    public record BudgetUsage(long estimatedTokens, double estimatedCost) { }

    public Map<String, Object> latest(Long taskId, String goal) {
        return latest(taskId, goal, AnalysisMode.GENERAL);
    }

    public Map<String, Object> latest(Long taskId, String goal, AnalysisMode mode) {
        String goalDigest = AnalysisTaskKeys.goalDigest(goal, mode);
        String taskKey = taskKey(taskId, goalDigest);
        String traceId = latestTraceByTask.get(taskKey);
        TraceData trace = traceId == null ? null : traces.get(traceId);
        if (trace != null) return trace.snapshot();
        try {
            if (traceId == null) {
                traceId = redisTemplate.opsForValue().get(
                        latestTraceKey(taskId, goalDigest));
            }
            String snapshot = traceId == null ? null : redisTemplate.opsForValue().get(traceKey(traceId));
            if (snapshot != null) {
                return objectMapper.readValue(
                        snapshot, new TypeReference<Map<String, Object>>() { });
            }
        } catch (Exception e) {
            log.warn("agent_trace_read_failed taskId={}", taskId, e);
        }
        try {
            return traceRepository.findLatest(taskId, goalDigest);
        } catch (RuntimeException e) {
            log.warn("agent_trace_mysql_read_failed taskId={}", taskId, e);
            return Map.of();
        }
    }

    public Map<String, Object> byTraceId(Long mediaId, String traceId) {
        TraceData trace = traces.get(traceId);
        if (trace != null && java.util.Objects.equals(trace.mediaId, mediaId)) return trace.snapshot();
        return traceRepository.findByTraceId(mediaId, traceId);
    }

    public Map<String, Object> byTaskId(Long mediaId, String taskId) {
        TraceData trace = traces.values().stream()
                .filter(item -> java.util.Objects.equals(item.mediaId, mediaId)
                        && item.executionTaskId.equals(taskId))
                .findFirst().orElse(null);
        return trace == null ? traceRepository.findByTaskId(mediaId, taskId) : trace.snapshot();
    }

    public List<Map<String, Object>> byMediaId(Long mediaId, int limit) {
        return traceRepository.findByMediaId(mediaId, Math.max(1, Math.min(limit, 100)));
    }

    public void deleteTask(Long taskId) {
        String prefix = taskId + ":";
        Set<String> traceIds = new HashSet<>();
        latestTraceByTask.entrySet().removeIf(entry -> {
            if (!entry.getKey().startsWith(prefix)) return false;
            traceIds.add(entry.getValue());
            return true;
        });
        try {
            Set<String> latestKeys = redisTemplate.opsForSet().members(traceIndexKey(taskId));
            if (latestKeys != null) {
                for (String latestKey : latestKeys) {
                    String traceId = redisTemplate.opsForValue().get(latestKey);
                    if (traceId != null) traceIds.add(traceId);
                }
                redisTemplate.delete(latestKeys);
            }
            traceIds.forEach(traceId -> redisTemplate.delete(traceKey(traceId)));
            redisTemplate.delete(traceIndexKey(taskId));
        } catch (RuntimeException e) {
            log.warn("agent_trace_cleanup_failed taskId={}", taskId, e);
        }
        traceIds.forEach(traces::remove);
        try {
            traceRepository.deleteByMediaId(taskId);
        } catch (RuntimeException e) {
            log.warn("agent_trace_mysql_cleanup_failed taskId={}", taskId, e);
        }
    }

    private void persist(TraceData trace) {
        String snapshot;
        try {
            snapshot = objectMapper.writeValueAsString(trace.snapshot());
        } catch (Exception e) {
            log.warn("agent_trace_serialize_failed traceId={} mediaId={}", trace.traceId, trace.mediaId, e);
            return;
        }
        try {
            traceRepository.upsert(
                    trace.traceId, trace.executionTaskId, trace.mediaId, trace.userId,
                    trace.taskType, trace.goalDigest, trace.status,
                    trace.submittedAtEpochMs, snapshot);
        } catch (RuntimeException e) {
            log.warn("agent_trace_mysql_persist_failed traceId={} mediaId={}",
                    trace.traceId, trace.mediaId, e);
        }
        try {
            redisTemplate.opsForValue().set(
                    traceKey(trace.traceId), snapshot, TRACE_TTL);
            String latestKey = latestTraceKey(trace.mediaId, trace.goalDigest);
            redisTemplate.opsForValue().set(
                    latestKey, trace.traceId, TRACE_TTL);
            redisTemplate.opsForSet().add(traceIndexKey(trace.mediaId), latestKey);
            redisTemplate.expire(traceIndexKey(trace.mediaId), TRACE_TTL);
        } catch (Exception e) {
            log.warn("agent_trace_persist_failed traceId={} mediaId={}", trace.traceId, trace.mediaId, e);
        }
    }

    private String traceKey(String traceId) {
        return "agent:trace:" + traceId;
    }

    private String latestTraceKey(Long taskId, String goalDigest) {
        return "agent:trace:task:" + taskId + ":" + goalDigest;
    }

    private String traceIndexKey(Long taskId) {
        return "agent:trace:task:" + taskId + ":goals";
    }

    private String taskKey(Long taskId, String goalDigest) {
        return taskId + ":" + goalDigest;
    }

    private long estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        long nonAscii = text.codePoints().filter(codePoint -> codePoint > 127).count();
        long ascii = text.codePoints().count() - nonAscii;
        return Math.max(1, nonAscii + (ascii + 3) / 4);
    }

    private static class TraceData {
        private final String traceId;
        private final String executionTaskId;
        private final Long mediaId;
        private final Long userId;
        private final String taskType;
        private final long submittedAtEpochMs;
        private final String goalDigest;
        private final String taskKey;
        private final Instant startedAt;
        private final Map<String, Long> stageDurations = new ConcurrentHashMap<>();
        private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
        private final Map<String, Double> values = new ConcurrentHashMap<>();
        private final List<Map<String, Object>> stageTimeline = new CopyOnWriteArrayList<>();
        private final DoubleAdder estimatedCost = new DoubleAdder();
        private volatile String status = "RUNNING";
        private volatile Instant finishedAt;

        private TraceData(String traceId,
                          String executionTaskId,
                          Long mediaId,
                          Long userId,
                          String taskType,
                          long submittedAtEpochMs,
                          String goalDigest,
                          String taskKey) {
            this.traceId = traceId;
            this.executionTaskId = executionTaskId;
            this.mediaId = mediaId;
            this.userId = userId;
            this.taskType = taskType;
            this.submittedAtEpochMs = submittedAtEpochMs;
            this.goalDigest = goalDigest;
            this.taskKey = taskKey;
            this.startedAt = Instant.ofEpochMilli(submittedAtEpochMs);
        }

        private void addStage(String stage, long startedAt, long finishedAt, boolean success) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("stage", stage);
            event.put("startedAt", Instant.ofEpochMilli(startedAt));
            event.put("finishedAt", Instant.ofEpochMilli(finishedAt));
            event.put("durationMs", Math.max(0, finishedAt - startedAt));
            event.put("status", success ? "SUCCESS" : "FAILED");
            stageTimeline.add(event);
        }

        private void increment(String metric, long amount) {
            counters.computeIfAbsent(metric, key -> new LongAdder()).add(amount);
        }

        private long counterValue(String metric) {
            LongAdder value = counters.get(metric);
            return value == null ? 0 : value.sum();
        }

        private Map<String, Object> snapshot() {
            Map<String, Long> counterSnapshot = new LinkedHashMap<>();
            counters.forEach((key, value) -> counterSnapshot.put(key, value.sum()));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("traceId", traceId);
            result.put("taskId", executionTaskId);
            result.put("mediaId", mediaId);
            result.put("userId", userId);
            result.put("taskType", taskType);
            result.put("goalDigest", goalDigest);
            result.put("submittedAt", Instant.ofEpochMilli(submittedAtEpochMs));
            result.put("startedAt", startedAt);
            result.put("finishedAt", finishedAt);
            result.put("status", status);
            result.put("stageDurationMs", new LinkedHashMap<>(stageDurations));
            result.put("stageTimeline", List.copyOf(stageTimeline));
            result.put("counters", counterSnapshot);
            result.put("values", new LinkedHashMap<>(values));
            result.put("estimatedCost", estimatedCost.sum());
            return result;
        }

        @SuppressWarnings("unchecked")
        private static TraceData restore(TraceContext context,
                                         String goalDigest,
                                         String taskKey,
                                         Map<String, Object> snapshot) {
            TraceData trace = new TraceData(
                    context.traceId(), context.taskId(), context.mediaId(), context.userId(),
                    context.taskType(), context.submittedAtEpochMs(), goalDigest, taskKey);
            Object status = snapshot.get("status");
            if (status != null) trace.status = status.toString();
            Object finishedAt = snapshot.get("finishedAt");
            if (finishedAt != null) trace.finishedAt = Instant.parse(finishedAt.toString());
            Object durations = snapshot.get("stageDurationMs");
            if (durations instanceof Map<?, ?> values) values.forEach((key, value) -> {
                if (key != null && value instanceof Number number) {
                    trace.stageDurations.put(key.toString(), number.longValue());
                }
            });
            Object counters = snapshot.get("counters");
            if (counters instanceof Map<?, ?> values) values.forEach((key, value) -> {
                if (key != null && value instanceof Number number) {
                    trace.increment(key.toString(), number.longValue());
                }
            });
            Object metricValues = snapshot.get("values");
            if (metricValues instanceof Map<?, ?> values) values.forEach((key, value) -> {
                if (key != null && value instanceof Number number) {
                    trace.values.put(key.toString(), number.doubleValue());
                }
            });
            Object timeline = snapshot.get("stageTimeline");
            if (timeline instanceof List<?> entries) entries.forEach(entry -> {
                if (entry instanceof Map<?, ?> event) {
                    trace.stageTimeline.add(new LinkedHashMap<>((Map<String, Object>) event));
                }
            });
            Object cost = snapshot.get("estimatedCost");
            if (cost instanceof Number number) trace.estimatedCost.add(number.doubleValue());
            return trace;
        }
    }
}
