package com.workbench.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinTable;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 用户账号。company_id 是数据隔离维度，角色生效范围由所属公司决定（ADR-M002/ADR-M003）；
 * project_id 表示其所属项目部，是数据范围过滤维度。
 */
@Entity
@Table(name = "sys_user")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 32)
  private String username;

  @Column(nullable = false, length = 64)
  private String name;

  @Column(nullable = false, length = 120)
  private String passwordHash;

  private Long orgId;

  private Long projectId;

  @Column(length = 20)
  private String phone;

  @Column(length = 16)
  private String employmentType;

  @ManyToMany(fetch = FetchType.LAZY)
  @JoinTable(
      name = "sys_user_role",
      joinColumns = @JoinColumn(name = "user_id"),
      inverseJoinColumns = @JoinColumn(name = "role_id"))
  private Set<Role> roles = new HashSet<>();

  @Column(nullable = false)
  private Boolean enabled;
  /** 所属公司（org_unit.id, type=COMPANY）：唯一性为 (company_id, username) 复合（ADR-M006） */
  @Column(name = "company_id", nullable = false)
  private Long companyId;

  /** 预留：统一身份认证外部 ID（本期只建列，不写业务逻辑，ADR-M006） */
  @Column(name = "external_id", length = 64)
  private String externalId;
}
