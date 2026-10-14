package com.workbench.notice.domain;

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
 * 通知公告。scope 表示可见范围（各项目部/全体会员等），阅读率由 read_count / total_count 聚合。
 * 发布时间存字符串（YYYY-MM-DD HH:MM），便于前端按 YYYY-MM-DD 切片展示。
 */
@Entity
@Table(name = "notice")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notice {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 200)
  private String title;

  @Column(length = 32)
  private String category;

  @Column(length = 64)
  private String scope;

  @Column(name = "published_at", length = 32)
  private String publishedAt;

  @Column(name = "read_count", nullable = false)
  private Integer readCount;

  @Column(name = "total_count", nullable = false)
  private Integer totalCount;

  @Column(nullable = false)
  private Boolean urgent;

  @Column(columnDefinition = "text")
  private String content;
  /** 所属公司（org_unit.id, type=COMPANY）：数据隔离维度（ADR-M002） */
  @Column(name = "company_id", nullable = false)
  private Long companyId;
}
