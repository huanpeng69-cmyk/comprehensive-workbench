package com.workbench.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 审计日志：敏感操作留痕，对应 audit_log 表（V1 迁移已建表）。
 */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 32)
  private String username;

  @Column(nullable = false, length = 64)
  private String action;

  @Column(length = 64)
  private String targetType;

  @Column(length = 64)
  private String targetId;

  @Column(columnDefinition = "text")
  private String detail;

  @Column(nullable = false)
  private LocalDateTime createdAt;
}
