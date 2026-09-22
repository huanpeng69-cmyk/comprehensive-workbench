package com.workbench.workflow.application.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 流程实例详情：含节点进度与全部审批记录，供审批弹窗展示。
 * nodes.state: DONE 已经过 / CURRENT 当前节点 / PENDING 等待流转 / SKIPPED 条件未命中跳过。
 */
public record ProcessInstanceVo(
    Long id,
    String bizType,
    String title,
    String initiator,
    String initiatorName,
    String projectName,
    String status,
    LocalDateTime createdAt,
    LocalDateTime finishedAt,
    String currentNodeName,
    List<NodeProgress> nodes,
    List<TaskHistory> tasks) {

  public record NodeProgress(Integer seq, String name, String state) {
  }

  public record TaskHistory(
      Long id,
      Integer nodeSeq,
      String nodeName,
      String assignee,
      String assigneeName,
      String status,
      String comment,
      LocalDateTime createdAt,
      LocalDateTime finishedAt) {
  }
}
