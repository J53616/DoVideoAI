package com.example.server.repository;

import com.example.server.mapper.AgentTraceMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/** Agent Trace 的 MySQL 持久化入口；Redis 仅作为 AgentTelemetry 的热缓存。 */
@Repository
public class AgentTraceRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final AgentTraceMapper mapper;
    private final ObjectMapper objectMapper;

    public AgentTraceRepository(AgentTraceMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public void upsert(String traceId,
                       String taskId,
                       Long mediaId,
                       Long userId,
                       String taskType,
                       String goalDigest,
                       String status,
                       long submittedAtEpochMs,
                       String snapshot) {
        mapper.upsert(traceId, taskId, mediaId, userId, taskType, goalDigest,
                status, submittedAtEpochMs, snapshot);
    }

    public Map<String, Object> findByTraceId(Long mediaId, String traceId) {
        return read(mapper.findByTraceId(mediaId, traceId));
    }

    public Map<String, Object> findByTaskId(Long mediaId, String taskId) {
        return read(mapper.findByTaskId(mediaId, taskId));
    }

    public Map<String, Object> findLatest(Long mediaId, String goalDigest) {
        return read(mapper.findLatest(mediaId, goalDigest));
    }

    public List<Map<String, Object>> findByMediaId(Long mediaId, int limit) {
        return mapper.findByMediaId(mediaId, limit).stream().map(this::read).toList();
    }

    public List<Map<String, Object>> findRecent(long sinceEpochMs, int limit) {
        return mapper.findRecent(sinceEpochMs, limit).stream().map(this::read).toList();
    }

    public void deleteByMediaId(Long mediaId) {
        mapper.deleteByMediaId(mediaId);
    }

    private Map<String, Object> read(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(snapshot, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("读取 Agent Trace 快照失败", e);
        }
    }
}
