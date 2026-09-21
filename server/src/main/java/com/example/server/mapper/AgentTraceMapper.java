package com.example.server.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentTraceMapper {

    @Insert("""
            INSERT INTO agent_traces(
                trace_id, task_id, media_id, user_id, task_type, goal_digest,
                status, submitted_at_epoch_ms, snapshot)
            VALUES(
                #{traceId}, #{taskId}, #{mediaId}, #{userId}, #{taskType}, #{goalDigest},
                #{status}, #{submittedAtEpochMs}, CAST(#{snapshot} AS JSON))
            ON DUPLICATE KEY UPDATE
                status = VALUES(status), snapshot = VALUES(snapshot),
                updated_at = CURRENT_TIMESTAMP(3)
            """)
    void upsert(@Param("traceId") String traceId,
                @Param("taskId") String taskId,
                @Param("mediaId") Long mediaId,
                @Param("userId") Long userId,
                @Param("taskType") String taskType,
                @Param("goalDigest") String goalDigest,
                @Param("status") String status,
                @Param("submittedAtEpochMs") long submittedAtEpochMs,
                @Param("snapshot") String snapshot);

    @Select("SELECT snapshot FROM agent_traces WHERE trace_id = #{traceId} AND media_id = #{mediaId}")
    String findByTraceId(@Param("mediaId") Long mediaId, @Param("traceId") String traceId);

    @Select("SELECT snapshot FROM agent_traces WHERE task_id = #{taskId} AND media_id = #{mediaId}")
    String findByTaskId(@Param("mediaId") Long mediaId, @Param("taskId") String taskId);

    @Select("""
            SELECT snapshot FROM agent_traces
            WHERE media_id = #{mediaId} AND goal_digest = #{goalDigest}
            ORDER BY updated_at DESC LIMIT 1
            """)
    String findLatest(@Param("mediaId") Long mediaId, @Param("goalDigest") String goalDigest);

    @Select("""
            SELECT snapshot FROM agent_traces WHERE media_id = #{mediaId}
            ORDER BY updated_at DESC LIMIT #{limit}
            """)
    List<String> findByMediaId(@Param("mediaId") Long mediaId, @Param("limit") int limit);

    @Select("""
            SELECT snapshot FROM agent_traces
            WHERE submitted_at_epoch_ms >= #{sinceEpochMs}
            ORDER BY submitted_at_epoch_ms DESC LIMIT #{limit}
            """)
    List<String> findRecent(@Param("sinceEpochMs") long sinceEpochMs, @Param("limit") int limit);

    @Delete("DELETE FROM agent_traces WHERE media_id = #{mediaId}")
    void deleteByMediaId(@Param("mediaId") Long mediaId);
}
