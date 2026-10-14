package com.workbench.salary.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 工资明细（单人单月）。主键为前端约定的 `${month}-${name}`。
 * 数值列兼容「=公式」字符串（NumLike），统一 varchar 存储。
 */
@Entity
@Table(name = "salary_record")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalaryRecord {

  @Id
  @Column(length = 64)
  private String id;

  @Column(nullable = false, length = 7)
  private String month;

  @Column(length = 64)
  private String department;

  private Integer seq;

  @Column(nullable = false, length = 64)
  private String name;

  @Column(name = "salary_grade", length = 32)
  private String salaryGrade;

  private String postDays;
  private String postSalary;
  private String titleAllowance;
  private String otDays;
  private String overtime;
  private String bonus;
  private String makeUp;
  private String seniority;
  private String siteAllowance;
  private String dueTotal;
  private String pension;
  private String unemployment;
  private String medical;
  private String housingFund;
  private String annuity;
  private String largeMedical;
  private String otherDeduct;
  private String childEdu;
  private String continuingEdu;
  private String housingLoan;
  private String housingRent;
  private String elderlyCare;
  private String infantCare;
  private String tax;
  private String taxAdjust;
  private String deduct;
  private String netTotal;
  private String monthPerf;
  private String unionFee;

  @Column(length = 64)
  private String sign;

  @Column(name = "imported_due", precision = 14, scale = 2)
  private BigDecimal importedDue;

  @Column(name = "imported_net", precision = 14, scale = 2)
  private BigDecimal importedNet;
  /** 所属公司（org_unit.id, type=COMPANY）：数据隔离维度（ADR-M002） */
  @Column(name = "company_id", nullable = false)
  private Long companyId;
}
