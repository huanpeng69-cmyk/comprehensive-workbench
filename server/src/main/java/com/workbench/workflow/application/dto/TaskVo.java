package com.workbench.workflow.application.dto;

import java.time.LocalDateTime;

/** 待办任务视图：携带实例与姓名信息，供待办中心直接展示。 */
public record TaskVo(
    Long taskId,
    Long instanceId,
    String title,
    String bizType,
    String initiator,
    String initiatorName,
    String nodeName,
    String assignee,
    String assigneeName,
    String status,
    LocalDateTime createdAt) {
}
