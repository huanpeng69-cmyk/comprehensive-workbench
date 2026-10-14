package com.workbench.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 流程定义版本快照（阶段 3，ADR-M010）。
 *
 * <p>每次改动定义都会存一份完整快照（定义本身 + 全部节点）为 JSON。用快照而不是
 * "节点级版本表"的理由：历史版本只是审计与回退的依据，几乎不会被单独查询；
 * 而节点级版本会把"读当前定义"变成"读最新版本再拼装"，热路径变复杂。</p>
 *
 * <p><b>回滚语义</b>：回滚不是删掉中间版本，而是把目标版本的快照<b>写回</b>当前定义，
 * 并产生一个 {@code changeType=ROLLBACK} 的新版本。因此版本号单调递增，
 * 历史版本一行不删——它们是审计依据。</p>
 */
@Entity
@Table(name = "process_definition_version",
    uniqueConstraints = @UniqueConstraint(name = "uq_pd_version_definition_version",
        columnNames = {"definitionId", "version"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessDefinitionVersion {

  /** 初始发布。 */
  public static final String CHANGE_INIT = "INIT";

  /** 管理员修改。 */
  public static final String CHANGE_UPDATE = "UPDATE";

  /** 回滚旧版本产生的新版本。 */
  public static final String CHANGE_ROLLBACK = "ROLLBACK";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long definitionId;

  /** 版本号：从 1 开始单调递增，同一定义内唯一。 */
  @Column(nullable = false)
  private Integer version;

  /** 变更类型：{@value #CHANGE_INIT} / {@value #CHANGE_UPDATE} / {@value #CHANGE_ROLLBACK}。 */
  @Column(nullable = false, length = 16)
  private String changeType;

  /** 管理员填写的变更说明（回滚时用于记录"为什么退回"）。 */
  @Column(length = 256)
  private String changeNote;

  /** 完整快照 JSON：{@code { "definition": {...}, "nodes": [...] }}。 */
  @Column(nullable = false, length = 4000)
  private String snapshot;

  @Column(nullable = false, length = 64)
  private String createdBy;

  /**
   * 创建时间，由数据库默认值填充。
   *
   * <p>{@code insertable=false, updatable=false}：本实体只读该列，写入一律走 DB 默认值
   * {@code current_timestamp}。刻意不用 {@code @CreationTimestamp}，让"时间来源"只有数据库一个，
   * 避免应用时钟与库时钟不一致导致版本排序错乱。也因此<b>插入后本实体里该字段是 null</b>，
   * 需要展示时必须重新查一次（listVersions 走的是查询路径，拿得到值）。</p>
   */
  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private LocalDateTime createdAt;
}
