package com.workbench.leave.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 请假记录。对应 leave_record 表（V5 迁移）。
 * applicant 取自登录态（不可客户端伪造）；workflow_instance_id 关联发起的请假流程实例。
 */
@Entity
@Table(name = "leave_record")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Leave {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 64)
  private String applicant;

  @Column(length = 64)
  private String applicantName;

  @Column(name = "project_id")
  private Long projectId;

  @Column(length = 64)
  private String project;

  @Column(name = "leave_type", nullable = false, length = 16)
  @JsonProperty("type")
  private String leaveType;

  @Column(name = "start_date", nullable = false, length = 20)
  @JsonProperty("start")
  private String startDate;

  @Column(name = "end_date", nullable = false, length = 20)
  @JsonProperty("end")
  private String endDate;

  @Column(nullable = false)
  private Integer days;

  @Column(length = 255)
  private String reason;

  @Column(nullable = false, length = 16)
  private String status;

  @Column(name = "workflow_instance_id")
  private Long workflowInstanceId;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @PrePersist
  void onCreate() {
    createdAt = LocalDateTime.now();
    updatedAt = createdAt;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = LocalDateTime.now();
  }
  /** 所属公司（org_unit.id, type=COMPANY）：数据隔离维度（ADR-M002） */
  @Column(name = "company_id", nullable = false)
  private Long companyId;
}
