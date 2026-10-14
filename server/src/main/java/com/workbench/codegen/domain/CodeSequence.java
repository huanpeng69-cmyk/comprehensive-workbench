package com.workbench.codegen.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 文号序号：一个 (类别, 周期) 一行，只存当前值（阶段 3，ADR-M009）。
 *
 * <p><b>不重号不跳号的实现基础</b>：取号时用 {@code SELECT ... FOR UPDATE} 锁住这一行再递增，
 * 靠数据库行锁串行化同一 (类别, 周期) 的取号请求。因此 {@link #currentValue} 只允许服务端改，
 * 手工 UPDATE 会直接破坏「不重号」承诺。</p>
 */
@Entity
@Table(name = "code_sequence",
    uniqueConstraints = @UniqueConstraint(name = "uq_code_sequence_category_period",
        columnNames = {"category", "period"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CodeSequence {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 类别码，对应 {@link CodeRule#getCategory()}。 */
  @Column(nullable = false, length = 32)
  private String category;

  /** 周期值：YEAR 周期即四位年份（如 {@code 2026}）；NONE 周期固定为 {@code "ALL"}。 */
  @Column(nullable = false, length = 16)
  private String period;

  /** 当前已发放到的序号。下一号 = current_value + 1。 */
  @Column(nullable = false)
  private Long currentValue;
}
