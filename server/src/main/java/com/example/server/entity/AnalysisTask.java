package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("analysis_tasks")
public class AnalysisTask {
    @TableId
    private String taskId;
    private String traceId;
    private Long mediaId;
    private Long userId;
    private String taskType;
    private String mode;
    private String contentHash;
    private String goalDigest;
    private String userGoal;
    private String status;
    private String currentStage;
    private Integer attemptCount;
    private Integer maxAttempts;
    private Boolean cancelRequested;
    private String errorType;
    private String errorMessage;
    private LocalDateTime submittedAt;
    private LocalDateTime queuedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime nextRetryAt;
    private Long version;
    private String activeKey;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
