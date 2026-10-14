package com.workbench.party.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 党费季度明细。缴费基数关联工资，属敏感数据：查询仅限全公司数据范围角色（服务层闸门 + 审计）。
 */
@Entity
@Table(name = "party_fee")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PartyFee {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 64)
  private String name;

  /** 缴费季度，如 2026-Q3 */
  @Column(nullable = false, length = 16)
  private String quarter;

  /** 各月缴费基数与党费（党费 = 基数 × 0.5%，绩效党费封顶 1000 元基数） */
  @Column(nullable = false, precision = 12, scale = 2)
  private BigDecimal base1;

  @Column(nullable = false, precision = 12, scale = 2)
  private BigDecimal fee1;

  @Column(nullable = false, precision = 12, scale = 2)
  private BigDecimal base2;

  @Column(nullable = false, precision = 12, scale = 2)
  private BigDecimal fee2;

  @Column(nullable = false, precision = 12, scale = 2)
  private BigDecimal base3;

  @Column(nullable = false, precision = 12, scale = 2)
  private BigDecimal fee3;

  @Column(nullable = false, precision = 12, scale = 2)
  private BigDecimal total;
  /** 所属公司（org_unit.id, type=COMPANY）：数据隔离维度（ADR-M002） */
  @Column(name = "company_id", nullable = false)
  private Long companyId;
}
