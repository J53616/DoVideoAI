package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.dto.TaskStage;
import com.example.server.dto.TraceContext;
import com.example.server.entity.AnalysisTask;
import com.example.server.mapper.AnalysisTaskMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

@Service
public class AnalysisTaskService {
    public static final String SUBMITTED = "SUBMITTED";
    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String RETRYING = "RETRYING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String DEAD_LETTERED = "DEAD_LETTERED";
    public static final String CANCELLED = "CANCELLED";
    private static final Set<String> TERMINAL = Set.of(SUCCEEDED, FAILED, DEAD_LETTERED, CANCELLED);

    private final AnalysisTaskMapper mapper;

    public AnalysisTaskService(AnalysisTaskMapper mapper) {
        this.mapper = mapper;
    }

    public boolean create(AnalysisTaskMsg message, String goalDigest) {
        TraceContext trace = message.traceContext();
        if (trace == null) throw new IllegalArgumentException("新任务必须携带 taskId 和 traceId");
        AnalysisTask task = new AnalysisTask();
        task.setTaskId(trace.taskId());
        task.setTraceId(trace.traceId());
        task.setMediaId(message.getMediaId());
        task.setUserId(message.getUserId());
        task.setTaskType(message.getAction());
        task.setMode(AnalysisMode.fromNullable(message.getMode()).name());
        task.setContentHash(message.getContentHash());
        task.setGoalDigest(goalDigest);
        task.setUserGoal(message.getUserGoal());
        task.setStatus(SUBMITTED);
        task.setAttemptCount(0);
        task.setMaxAttempts(3);
        task.setCancelRequested(false);
        task.setVersion(0L);
        task.setActiveKey(activeKey(message.getMediaId(), goalDigest));
        task.setSubmittedAt(LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(trace.submittedAtEpochMs()), ZoneId.systemDefault()));
        try {
            mapper.insert(task);
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    public void queued(String taskId) {
        transition(taskId, Set.of(SUBMITTED), QUEUED, TaskStage.QUEUED, null, false);
    }

    public int beginAttempt(String taskId) {
        if (taskId == null || taskId.isBlank()) return -1; // historical MQ messages
        AnalysisTask current = mapper.selectById(taskId);
        if (current == null || TERMINAL.contains(current.getStatus())
                || Boolean.TRUE.equals(current.getCancelRequested())) return 0;
        if (RUNNING.equals(current.getStatus())) {
            stage(taskId, TaskStage.CONSUMING);
            return current.getAttemptCount();
        }
        int updated = mapper.update(null, new UpdateWrapper<AnalysisTask>()
                .eq("task_id", taskId)
                .in("status", SUBMITTED, QUEUED, RETRYING)
                .eq("cancel_requested", false)
                .apply("attempt_count < max_attempts")
                .set("status", RUNNING)
                .set("current_stage", TaskStage.CONSUMING.name())
                .setSql("attempt_count = attempt_count + 1")
                .setSql("started_at = COALESCE(started_at, CURRENT_TIMESTAMP(3))")
                .set("next_retry_at", null)
                .setSql("version = version + 1"));
        if (updated != 1) return 0;
        AnalysisTask task = mapper.selectById(taskId);
        return task == null ? 0 : task.getAttemptCount();
    }

    public void stage(String taskId, TaskStage stage) {
        if (taskId == null || taskId.isBlank() || stage == null) return;
        mapper.update(null, new UpdateWrapper<AnalysisTask>()
                .eq("task_id", taskId).notIn("status", TERMINAL)
                .set("current_stage", stage.name()).setSql("version = version + 1"));
    }

    public void stageLatest(Long mediaId, String goalDigest, TaskStage stage) {
        if (stage == null) return;
        AnalysisTask task = latest(mediaId, goalDigest);
        if (task != null) stage(task.getTaskId(), stage);
    }

    public void retrying(String taskId, Throwable error) {
        transition(taskId, Set.of(RUNNING), RETRYING, TaskStage.RETRYING, error, false);
    }

    public void succeeded(String taskId, TaskStage stage) {
        transition(taskId, Set.of(RUNNING), SUCCEEDED, stage, null, true);
    }

    public void failed(String taskId, TaskStage stage, Throwable error) {
        transition(taskId, Set.of(SUBMITTED, QUEUED, RUNNING, RETRYING), FAILED, stage, error, true);
    }

    public void deadLettered(String taskId, Throwable error) {
        transition(taskId, Set.of(SUBMITTED, QUEUED, RUNNING, RETRYING),
                DEAD_LETTERED, TaskStage.DEAD_LETTERED, error, true);
    }

    public boolean cancellationRequested(String taskId) {
        if (taskId == null || taskId.isBlank()) return false;
        AnalysisTask task = mapper.selectById(taskId);
        return task != null && (Boolean.TRUE.equals(task.getCancelRequested()) || CANCELLED.equals(task.getStatus()));
    }

    public boolean cancel(String taskId, Long userId) {
        AnalysisTask task = requireOwned(taskId, userId);
        if (TERMINAL.contains(task.getStatus())) return CANCELLED.equals(task.getStatus());
        boolean running = RUNNING.equals(task.getStatus());
        UpdateWrapper<AnalysisTask> update = new UpdateWrapper<AnalysisTask>()
                .eq("task_id", taskId).notIn("status", TERMINAL)
                .set("cancel_requested", true).setSql("version = version + 1");
        if (!running) {
            update.set("status", CANCELLED).set("current_stage", null)
                    .set("finished_at", LocalDateTime.now()).set("active_key", null);
        }
        return mapper.update(null, update) == 1;
    }

    public void cancelled(String taskId) {
        transition(taskId, Set.of(SUBMITTED, QUEUED, RUNNING, RETRYING), CANCELLED, null, null, true);
    }

    public AnalysisTask latest(Long mediaId, String goalDigest) {
        return mapper.selectOne(new QueryWrapper<AnalysisTask>()
                .eq("media_id", mediaId).eq("goal_digest", goalDigest)
                .orderByDesc("submitted_at").last("LIMIT 1"));
    }

    public boolean hasActive(Long mediaId, String goalDigest) {
        return mapper.selectCount(new QueryWrapper<AnalysisTask>()
                .eq("active_key", activeKey(mediaId, goalDigest))) > 0;
    }

    public AnalysisTask requireOwned(String taskId, Long userId) {
        AnalysisTask task = mapper.selectById(taskId);
        if (task == null || !userId.equals(task.getUserId())) {
            throw new java.util.NoSuchElementException("分析任务不存在");
        }
        return task;
    }

    public List<AnalysisTask> recoverable(LocalDateTime staleBefore) {
        return mapper.selectList(new QueryWrapper<AnalysisTask>()
                .and(q -> q.in("status", SUBMITTED, QUEUED, RETRYING)
                        .or(n -> n.eq("status", RUNNING).lt("updated_at", staleBefore)))
                .eq("cancel_requested", false).orderByAsc("submitted_at"));
    }

    public AnalysisTaskMsg toMessage(AnalysisTask task) {
        TraceContext trace = new TraceContext(task.getTraceId(), task.getTaskId(), task.getMediaId(),
                task.getUserId(), task.getTaskType(),
                task.getSubmittedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        return new AnalysisTaskMsg(task.getMediaId(), task.getTaskType(), task.getContentHash(),
                task.getUserGoal(), task.getMode(), trace);
    }

    private void transition(String taskId, Set<String> from, String to, TaskStage stage,
                            Throwable error, boolean terminal) {
        if (taskId == null || taskId.isBlank()) return;
        UpdateWrapper<AnalysisTask> update = new UpdateWrapper<AnalysisTask>()
                .eq("task_id", taskId).in("status", from).set("status", to)
                .set("current_stage", stage == null ? null : stage.name())
                .setSql("version = version + 1");
        if (QUEUED.equals(to)) update.set("queued_at", LocalDateTime.now());
        if (error != null) {
            Throwable root = root(error);
            update.set("error_type", truncate(root.getClass().getSimpleName(), 128))
                    .set("error_message", truncate(root.getMessage(), 1000));
        }
        if (terminal) update.set("finished_at", LocalDateTime.now()).set("active_key", null);
        mapper.update(null, update);
    }

    private Throwable root(Throwable error) {
        Throwable current = error;
        for (int i = 0; current.getCause() != null && current.getCause() != current && i < 16; i++) {
            current = current.getCause();
        }
        return current;
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    private String activeKey(Long mediaId, String goalDigest) {
        return mediaId + ":" + goalDigest;
    }
}
