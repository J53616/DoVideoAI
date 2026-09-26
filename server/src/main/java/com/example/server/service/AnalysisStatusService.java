package com.example.server.service;

import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.TaskStage;
import com.example.server.dto.TaskStatus;
import com.example.server.entity.AnalysisTask;
import com.example.server.utils.AnalysisTaskKeys;
import org.springframework.stereotype.Service;

@Service
public class AnalysisStatusService {

    private final AgentCheckpointService checkpointService;
    private final AnalysisDispatchService dispatchService;
    private final AnalysisTaskService analysisTaskService;

    public AnalysisStatusService(AgentCheckpointService checkpointService,
                                 AnalysisDispatchService dispatchService,
                                 AnalysisTaskService analysisTaskService) {
        this.checkpointService = checkpointService;
        this.dispatchService = dispatchService;
        this.analysisTaskService = analysisTaskService;
    }

    public TaskStatus current(Long mediaId, String goal) {
        return current(mediaId, goal, AnalysisMode.GENERAL);
    }

    public TaskStatus current(Long mediaId, String goal, AnalysisMode mode) {
        AnalysisTask task = analysisTaskService.latest(
                mediaId, AnalysisTaskKeys.goalDigest(goal, mode));
        boolean succeeded = task != null && AnalysisTaskService.SUCCEEDED.equals(task.getStatus());
        if (task != null) {
            TaskStatus persisted = fromTask(task);
            if (persisted != null) return persisted;
        }
        AgentState result = checkpointService.loadResult(mediaId, goal, mode);
        if (result != null && result.result() != null) {
            return TaskStatus.completed(result);
        }
        if (succeeded) return TaskStatus.of(TaskStatus.State.COMPLETED, "任务完成");

        TaskStage stage = checkpointService.loadStage(mediaId, goal, mode);
        if (dispatchService.isActive(mediaId, goal, mode)) {
            TaskStatus.State state = stage == null ? TaskStatus.State.QUEUED : TaskStatus.State.PROCESSING;
            return TaskStatus.of(state, statusMessage(stage));
        }
        if (stage == TaskStage.BUDGET_EXHAUSTED) {
            return TaskStatus.of(TaskStatus.State.FAILED, "Agent 已达到本次任务预算，请调整目标后重试");
        }
        if (stage == TaskStage.FAILED || stage == TaskStage.DEAD_LETTERED) {
            return TaskStatus.of(TaskStatus.State.FAILED, "分析失败，请稍后重试");
        }
        return TaskStatus.of(TaskStatus.State.NOT_STARTED, "尚未提交分析任务");
    }

    public TaskStage stage(Long mediaId, String goal) {
        return stage(mediaId, goal, AnalysisMode.GENERAL);
    }

    public TaskStage stage(Long mediaId, String goal, AnalysisMode mode) {
        AnalysisTask task = analysisTaskService.latest(
                mediaId, AnalysisTaskKeys.goalDigest(goal, mode));
        if (task != null && task.getCurrentStage() != null) {
            return TaskStage.from(task.getCurrentStage());
        }
        return checkpointService.loadStage(mediaId, goal, mode);
    }

    private TaskStatus fromTask(AnalysisTask task) {
        return switch (task.getStatus()) {
            case AnalysisTaskService.SUBMITTED, AnalysisTaskService.QUEUED ->
                    TaskStatus.of(TaskStatus.State.QUEUED, "任务已排队");
            case AnalysisTaskService.RUNNING ->
                    TaskStatus.of(TaskStatus.State.PROCESSING,
                            statusMessage(TaskStage.from(task.getCurrentStage())));
            case AnalysisTaskService.RETRYING ->
                    TaskStatus.of(TaskStatus.State.RETRYING, "任务执行异常，正在自动重试");
            case AnalysisTaskService.CANCELLED ->
                    TaskStatus.of(TaskStatus.State.CANCELLED, "任务已取消");
            case AnalysisTaskService.FAILED, AnalysisTaskService.DEAD_LETTERED ->
                    TaskStatus.of(TaskStatus.State.FAILED,
                            task.getErrorMessage() == null ? "分析失败，请稍后重试" : task.getErrorMessage());
            case AnalysisTaskService.SUCCEEDED -> null; // 从 checkpoint 返回最终结果
            default -> null;
        };
    }

    private String statusMessage(TaskStage stage) {
        if (stage == null || stage == TaskStage.QUEUED) return "任务已排队";
        return switch (stage) {
            case VIDEO_CONTEXT, CONTEXT_COMPLETED -> "正在解析视频语音和关键画面";
            case CHUNKS_COMPLETED -> "正在检索与目标相关的视频证据";
            case PLAN_COMPLETED -> "Planner 已完成任务拆解";
            case EXECUTOR_STARTED, EXECUTOR_COMPLETED -> "Executor 正在生成结构化产物";
            case CRITIC_STARTED -> "Critic 正在核验结论和证据";
            case CRITIC_RETRY_REQUIRED, EVIDENCE_REFRESHED -> "正在根据 Critic 反馈补充证据";
            case RETRYING -> "任务执行异常，正在自动重试";
            default -> "正在分析视频";
        };
    }
}
