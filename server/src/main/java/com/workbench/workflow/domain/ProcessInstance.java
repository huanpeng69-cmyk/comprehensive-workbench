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
 * 流程实例：一次具体的审批过程。variables 存 JSON，供条件路由使用。
 */
@Entity
@Table(name = "process_instance")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessInstance {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long definitionId;

  @Column(nullable = false, length = 32)
  private String bizType;

  private Long bizId;

  @Column(nullable = false, length = 128)
  private String title;

  @Column(nullable = false, length = 32)
  private String initiator;

  private Long projectId;

  /** RUNNING / COMPLETED / REJECTED / TERMINATED */
  @Column(nullable = false, length = 16)
  private String status;

  @Column(columnDefinition = "text")
  private String variables;

  private Integer currentNodeSeq;

  @Column(nullable = false)
  private java.time.LocalDateTime createdAt;

  private java.time.LocalDateTime finishedAt;
}
