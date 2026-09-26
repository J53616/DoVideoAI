package com.example.server.dto;

import com.example.server.entity.AnalysisTask;

import java.time.LocalDateTime;

public record AnalysisTaskView(
        String taskId,
        String traceId,
        Long mediaId,
        String type,
        String mode,
        String status,
        String stage,
        int attempts,
        int maxAttempts,
        boolean cancelRequested,
        String errorType,
        String errorMessage,
        LocalDateTime submittedAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime updatedAt) {
    public static AnalysisTaskView from(AnalysisTask task) {
        return new AnalysisTaskView(task.getTaskId(), task.getTraceId(), task.getMediaId(),
                task.getTaskType(), task.getMode(), task.getStatus(), task.getCurrentStage(),
                task.getAttemptCount(), task.getMaxAttempts(), Boolean.TRUE.equals(task.getCancelRequested()),
                task.getErrorType(), task.getErrorMessage(), task.getSubmittedAt(), task.getStartedAt(),
                task.getFinishedAt(), task.getUpdatedAt());
    }
}
