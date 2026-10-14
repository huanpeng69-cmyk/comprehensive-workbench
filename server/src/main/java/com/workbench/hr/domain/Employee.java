package com.workbench.hr.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 人员花名册。对应 employee 表（V3 迁移建表）。
 * project_id 关联 org_unit.id（type=PROJECT），导入/新增时按项目名称匹配；
 * 匹配不到则置空，表示「未分配项目部」（仅 COMPANY 数据范围可见）。
 */
@Entity
@Table(name = "employee")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Employee {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Integer seq;

  @Column(nullable = false, length = 64)
  private String name;

  @Column(length = 8)
  private String gender;

  /** 敏感：身份证号 */
  @Column(name = "id_card", length = 32)
  private String idCard;

  /** 敏感：手机号 */
  @Column(length = 20)
  private String phone;

  private Integer age;

  @Column(name = "join_date", length = 20)
  private String joinDate;

  @Column(name = "employment_type", length = 16)
  private String employmentType;

  @Column(length = 16)
  private String education;

  @Column(length = 64)
  private String school;

  @Column(length = 64)
  private String major;

  @Column(length = 64)
  private String title;

  @Column(length = 16)
  private String political;

  @Column(length = 64)
  private String position;

  @Column(name = "admin_series", length = 32)
  private String adminSeries;

  @Column(name = "prof_series", length = 16)
  private String profSeries;

  @Column(name = "salary_mechanism", length = 16)
  private String salaryMechanism;

  @Column(name = "salary_grade", length = 32)
  private String salaryGrade;

  /** 敏感：月度标准（元/月） */
  @Column(name = "monthly_pay", precision = 12, scale = 2)
  private BigDecimal monthlyPay;

  @Column(name = "first_adjust", length = 16)
  private String firstAdjust;

  @Column(name = "project_id")
  private Long projectId;

  @Column(length = 64)
  private String project;

  @Column(length = 32)
  private String status;

  @Column(length = 255)
  private String certificates;

  @Column(name = "contract_rel", length = 32)
  private String contractRel;

  @Column(length = 16)
  private String category;

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
