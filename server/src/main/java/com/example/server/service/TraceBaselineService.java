package com.example.server.service;

import com.example.server.dto.TraceBaselineMetrics;
import com.example.server.repository.AgentTraceRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class TraceBaselineService {

    private static final int MAX_SAMPLES = 5_000;
    private static final Set<String> SUCCESS_STATUSES = Set.of("COMPLETED", "COMPLETED_REUSED");
    private static final Set<String> ACTIVE_STATUSES = Set.of("RUNNING", "QUEUED", "RETRYING");

    private final AgentTraceRepository repository;

    public TraceBaselineService(AgentTraceRepository repository) {
        this.repository = repository;
    }

    public TraceBaselineMetrics calculate(int days) {
        int resolvedDays = Math.max(1, Math.min(days, 90));
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(resolvedDays));
        List<Map<String, Object>> traces = repository.findRecent(from.toEpochMilli(), MAX_SAMPLES);

        List<Double> totalDurations = new ArrayList<>();
        List<Double> queueDurations = new ArrayList<>();
        List<Double> tokenTotals = new ArrayList<>();
        List<Double> costs = new ArrayList<>();
        Map<String, Long> statuses = new HashMap<>();
        Map<String, Long> failedStages = new HashMap<>();
        Map<String, ToolAccumulator> tools = new HashMap<>();
        int active = 0;
        int success = 0;
        int failure = 0;
        long modelAttempts = 0;
        long modelRetries = 0;

        for (Map<String, Object> trace : traces) {
            String status = string(trace.get("status"), "UNKNOWN");
            statuses.merge(status, 1L, Long::sum);
            if (ACTIVE_STATUSES.contains(status)) active++;
            else if (SUCCESS_STATUSES.contains(status)) success++;
            else failure++;

            Instant submittedAt = instant(trace.get("submittedAt"));
            Instant finishedAt = instant(trace.get("finishedAt"));
            if (submittedAt != null && finishedAt != null && !finishedAt.isBefore(submittedAt)) {
                totalDurations.add((double) Duration.between(submittedAt, finishedAt).toMillis());
            }

            Map<String, Object> counters = map(trace.get("counters"));
            Map<String, Object> values = map(trace.get("values"));
            addIfPresent(queueDurations, values.get("queueDurationMs"));
            addIfPresent(tokenTotals, counters.get("totalTokens"));
            addIfPresent(costs, trace.get("estimatedCost"));
            modelAttempts += number(counters.get("modelCallAttempts"));
            modelRetries += number(counters.get("modelCallRetries"));
            collectTools(counters, values, tools);
            collectFailedStages(trace.get("stageTimeline"), failedStages);
        }

        int terminal = success + failure;
        Map<String, TraceBaselineMetrics.ToolMetrics> toolMetrics = new LinkedHashMap<>();
        tools.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                toolMetrics.put(entry.getKey(), entry.getValue().snapshot()));
        return new TraceBaselineMetrics(
                from, to, traces.size(), terminal, active, success, failure,
                rate(success, terminal), stats(totalDurations), stats(queueDurations),
                stats(tokenTotals), stats(costs), modelAttempts, modelRetries,
                rate(modelRetries, modelAttempts), sorted(statuses), sorted(failedStages), toolMetrics);
    }

    private void collectTools(Map<String, Object> counters,
                              Map<String, Object> values,
                              Map<String, ToolAccumulator> tools) {
        counters.forEach((key, value) -> {
            if (!key.startsWith("tool.") || !key.endsWith(".calls")) return;
            String tool = key.substring("tool.".length(), key.length() - ".calls".length());
            ToolAccumulator accumulator = tools.computeIfAbsent(tool, ignored -> new ToolAccumulator());
            accumulator.calls += number(value);
            accumulator.successes += number(counters.get("tool." + tool + ".successes"));
            accumulator.failures += number(counters.get("tool." + tool + ".failures"));
            accumulator.totalDurationMs += decimal(values.get("tool." + tool + ".durationMsTotal"));
            accumulator.maxDurationMs = Math.max(accumulator.maxDurationMs,
                    decimal(values.get("tool." + tool + ".durationMsMax")));
        });
    }

    private void collectFailedStages(Object value, Map<String, Long> failedStages) {
        if (!(value instanceof List<?> timeline)) return;
        for (Object item : timeline) {
            Map<String, Object> event = map(item);
            if (!"FAILED".equals(string(event.get("status"), ""))) continue;
            failedStages.merge(string(event.get("stage"), "UNKNOWN"), 1L, Long::sum);
        }
    }

    private TraceBaselineMetrics.MetricStats stats(List<Double> values) {
        if (values.isEmpty()) return new TraceBaselineMetrics.MetricStats(0, 0, 0, 0, 0, 0);
        List<Double> sorted = values.stream().sorted().toList();
        double average = sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return new TraceBaselineMetrics.MetricStats(
                sorted.size(), average, percentile(sorted, 0.50), percentile(sorted, 0.95),
                percentile(sorted, 0.99), sorted.getLast());
    }

    private double percentile(List<Double> sorted, double percentile) {
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
        return sorted.get(index);
    }

    private void addIfPresent(List<Double> values, Object value) {
        if (value instanceof Number number) values.add(number.doubleValue());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private Instant instant(Object value) {
        if (value == null) return null;
        try {
            return Instant.parse(value.toString());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String string(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private double decimal(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private double rate(long numerator, long denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    private Map<String, Long> sorted(Map<String, Long> values) {
        Map<String, Long> result = new LinkedHashMap<>();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private static class ToolAccumulator {
        private long calls;
        private long successes;
        private long failures;
        private double totalDurationMs;
        private double maxDurationMs;

        private TraceBaselineMetrics.ToolMetrics snapshot() {
            return new TraceBaselineMetrics.ToolMetrics(
                    calls, successes, failures,
                    calls == 0 ? 0 : (double) failures / calls,
                    totalDurationMs, calls == 0 ? 0 : totalDurationMs / calls, maxDurationMs);
        }
    }
}
