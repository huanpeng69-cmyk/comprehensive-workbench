package com.workbench.condolence.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 慰问金申请。对应 condolence 表（V8 迁移）。
 * applicant 取自登录态（不可客户端伪造）；workflow_instance_id 关联发起的慰问金流程实例。
 * 时间戳在业务层显式写入（create 与 seeder 各设自己的值），以便演示数据保留历史申请日期。
 */
@Entity
@Table(name = "condolence")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Condolence {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "applicant", nullable = false, length = 64)
  private String applicant;

  @Column(name = "applicant_username", length = 64)
  private String applicantUsername;

  @Column(name = "project_id")
  private Long projectId;

  @Column(name = "project", length = 64)
  private String project;

  @Column(name = "category", nullable = false, length = 32)
  private String category;

  @Column(name = "reason", length = 255)
  private String reason;

  @Column(name = "amount", nullable = false, precision = 12, scale = 2)
  private BigDecimal amount;

  @Column(name = "amount_in_words", length = 64)
  private String amountInWords;

  @Column(name = "project_union_opinion", length = 64)
  private String projectUnionOpinion;

  @Column(name = "company_union_opinion", length = 64)
  private String companyUnionOpinion;

  @Column(name = "status", nullable = false, length = 16)
  private String status;

  @Column(name = "workflow_instance_id")
  private Long workflowInstanceId;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;
  /** 所属公司（org_unit.id, type=COMPANY）：数据隔离维度（ADR-M002） */
  @Column(name = "company_id", nullable = false)
  private Long companyId;
}
