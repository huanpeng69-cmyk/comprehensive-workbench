package com.workbench.codegen.domain;

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
 * 文号规则：一个「类别」一条规则（阶段 3，ADR-M009）。
 *
 * <p>规则是低频读、几乎不改的数据，与 {@link CodeSequence}（高频写、有并发争用）拆成两张表：
 * 改规则不会因为序号行锁而阻塞，取号热路径也只碰序号表。</p>
 */
@Entity
@Table(name = "code_rule")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CodeRule {

  /** 重置周期：每年序号归 1。 */
  public static final String RESET_YEAR = "YEAR";

  /** 重置周期：序号一直递增，不归 1。 */
  public static final String RESET_NONE = "NONE";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 类别码：调用方传的 key，全局唯一。 */
  @Column(nullable = false, unique = true, length = 32)
  private String category;

  /** 类别名称，仅用于展示。 */
  @Column(nullable = false, length = 64)
  private String name;

  /**
   * 格式模板。占位符：{@code {PREFIX}} 前缀、{@code {YYYY}} 四位年、{@code {YY}} 两位年、
   * {@code {SEQ}} 补零序号、{@code {PERIOD}} 周期值。
   */
  @Column(nullable = false, length = 128)
  private String pattern;

  /** 序号补零宽度，如 4 表示 0001。 */
  @Column(nullable = false)
  private Integer seqWidth;

  /** 重置周期：{@value #RESET_YEAR} / {@value #RESET_NONE}。 */
  @Column(nullable = false, length = 16)
  private String resetCycle;

  @Column(nullable = false)
  private Boolean enabled;
}
