package com.workbench.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 运行时任务。一个审批节点可能产生多条任务（会签或多人角色）。
 * status: PENDING 待处理 / APPROVED 已通过 / REJECTED 已驳回 / TRANSMITTED 已转办 /
 * CC 抄送（自动完成）/ CANCELLED 因其他审批人已处理而作废。
 */
@Entity
@Table(name = "process_task")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Task {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long instanceId;

  @Column(nullable = false)
  private Integer nodeSeq;

  @Column(nullable = false, length = 64)
  private String nodeName;

  @Column(nullable = false, length = 32)
  private String assignee;

  /** PENDING / APPROVED / REJECTED / TRANSMITTED */
  @Column(nullable = false, length = 16)
  private String status;

  @Column(length = 200)
  private String comment;

  @Column(nullable = false)
  private java.time.LocalDateTime createdAt;

  private java.time.LocalDateTime finishedAt;
}
