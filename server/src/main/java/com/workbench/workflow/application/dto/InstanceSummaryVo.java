package com.workbench.workflow.application.dto;

import java.time.LocalDateTime;

/** 流程实例摘要：我的流程列表与发起结果。 */
public record InstanceSummaryVo(
    Long id,
    String bizType,
    String title,
    String initiator,
    String status,
    String currentNodeName,
    LocalDateTime createdAt,
    LocalDateTime finishedAt) {
}
