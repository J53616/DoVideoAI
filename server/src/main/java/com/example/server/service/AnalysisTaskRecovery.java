package com.example.server.service;

import com.example.server.entity.AnalysisTask;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class AnalysisTaskRecovery {
    private static final Logger log = LoggerFactory.getLogger(AnalysisTaskRecovery.class);

    private final AnalysisTaskService taskService;
    private final RocketMQTemplate rocketMQTemplate;
    private final String analysisTopic;

    public AnalysisTaskRecovery(AnalysisTaskService taskService,
                                RocketMQTemplate rocketMQTemplate,
                                @Value("${rocketmq.topic.video-analysis:video-analysis-topic}")
                                String analysisTopic) {
        this.taskService = taskService;
        this.rocketMQTemplate = rocketMQTemplate;
        this.analysisTopic = analysisTopic;
    }

    /**
     * Broker 投递是至少一次语义；启动时重投非终态任务，消费者再用任务状态和分布式锁收敛重复消息。
     * RUNNING 只恢复超过安全窗口的记录，避免滚动发布时抢正在执行的任务。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        for (AnalysisTask task : taskService.recoverable(LocalDateTime.now().minusMinutes(10))) {
            try {
                rocketMQTemplate.convertAndSend(analysisTopic, taskService.toMessage(task));
                log.info("analysis_task_recovered taskId={} status={}", task.getTaskId(), task.getStatus());
            } catch (RuntimeException error) {
                log.error("analysis_task_recovery_dispatch_failed taskId={}", task.getTaskId(), error);
            }
        }
    }
}
