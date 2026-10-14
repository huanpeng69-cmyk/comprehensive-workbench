package com.workbench.salary.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 绩效激励记录（「绩效」工作表结构）。 */
@Entity
@Table(name = "perf_record")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PerfRecord {

  @Id
  @Column(length = 64)
  private String id;

  @Column(nullable = false, length = 7)
  private String month;

  private Integer seq;

  @Column(length = 64)
  private String department;

  @Column(nullable = false, length = 64)
  private String name;

  @Column(name = "month_score", length = 32)
  private String monthScore;

  @Column(name = "perf_level", length = 32)
  private String perfLevel;

  @Column(name = "incentive_coef", length = 32)
  private String incentiveCoef;

  private String sepPerf;
  private String augBase;
  private String incentivePart;

  @Column(name = "balance_coef", length = 32)
  private String balanceCoef;

  @Column(name = "coop_coef", length = 32)
  private String coopCoef;

  @Column(name = "final_incentive")
  private String finalIncentive;

  @Column(name = "total_perf")
  private String totalPerf;
  /** 所属公司（org_unit.id, type=COMPANY）：数据隔离维度（ADR-M002） */
  @Column(name = "company_id", nullable = false)
  private Long companyId;
}
