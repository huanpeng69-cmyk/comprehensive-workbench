package com.workbench.salary.domain;

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

/** 工资月度头表：一个月一条，明细在 salary_record / perf_record。 */
@Entity
@Table(name = "salary_month")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalaryMonth {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 工资所属月 yyyy-MM */
  @Column(nullable = false, length = 7)
  private String month;

  @Column(name = "imported_at", nullable = false)
  private LocalDateTime importedAt;
  /** 所属公司（org_unit.id, type=COMPANY）：唯一性为 (company_id, month) 复合（ADR-M002） */
  @Column(name = "company_id", nullable = false)
  private Long companyId;
}
