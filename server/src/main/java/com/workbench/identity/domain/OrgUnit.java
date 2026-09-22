package com.workbench.identity.domain;

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
 * 组织机构：公司 / 项目部 / 部门三级树，通过 type 区分层级，parent_id 关联上级。
 */
@Entity
@Table(name = "org_unit")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrgUnit {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 64)
  private String name;

  /** COMPANY / PROJECT / DEPARTMENT */
  @Column(nullable = false, length = 16)
  private String type;

  private Long parentId;

  @Column(length = 64)
  private String fullPath;

  private Integer sortNo;

  @Column(nullable = false)
  private Boolean enabled;
}
