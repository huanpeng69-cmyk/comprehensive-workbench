package com.workbench.memory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 用户长期记忆（一行 = 该用户的一条记忆）。
 *
 * <p><b>归属键是 (companyId, ownerUsername)，不是单个 userId</b>：</p>
 * <ul>
 *   <li>{@code companyId} 是全项目统一的软隔离维度（ADR-M002），跨单位绝不互相可见；</li>
 *   <li>{@code ownerUsername} 是 JWT {@code sub} 里的登录名，在单位内唯一（ADR-M006），跨单位可重名。</li>
 * </ul>
 * <p>这样既做到「每个用户一份记忆」，又不必让本模块直连 identity 仓储去解析 userId
 * （{@code ModuleBoundaryTest} 禁止业务模块依赖 {@code identity.repository}）。</p>
 *
 * <p>表结构与本类必须严格对齐：应用以 {@code ddl-auto: validate} 启动，字段错位在启动时就会失败，
 * DDL 见 {@code db/migration/V16__user_memory.sql}。</p>
 */
@Entity
@Table(name = "user_memory")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserMemory {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 归属单位（org_unit.type='COMPANY' 行的 id）。 */
  @Column(name = "company_id", nullable = false)
  private Long companyId;

  /** 归属账号（登录名）。 */
  @Column(name = "owner_username", nullable = false, length = 64)
  private String ownerUsername;

  @Enumerated(EnumType.STRING)
  @Column(name = "memory_type", nullable = false, length = 16)
  private MemoryType memoryType;

  /** 记忆正文。 */
  @Column(nullable = false, columnDefinition = "text")
  private String content;

  /** 归一化正文的 SHA-256（十六进制），与库中唯一索引配合做同人同类型去重。 */
  @Column(name = "content_hash", nullable = false, length = 64)
  private String contentHash;

  /** 重要度 0..100，参与检索打分。 */
  @Column(nullable = false)
  private Integer importance;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private MemorySource source;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  /** 更新时间，同时是检索打分的「新鲜度」依据。 */
  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  /** 过期时间；非空且已过期则不参与检索（管理列表仍可见）。 */
  @Column(name = "expires_at")
  private LocalDateTime expiresAt;

  /**
   * 相对给定时刻是否已过期。{@code expiresAt} 为空表示永不过期。
   *
   * <p>检索侧仍要靠 SQL 过滤（否则白读一堆过期行），本方法用于二次兜底与视图标注。</p>
   */
  public boolean isExpiredAt(LocalDateTime now) {
    return expiresAt != null && !expiresAt.isAfter(now);
  }
}
