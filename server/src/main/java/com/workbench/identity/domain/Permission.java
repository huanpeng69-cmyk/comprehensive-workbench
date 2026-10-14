package com.workbench.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 权限点（阶段 3，ADR-M010）：后台写操作的准入门槛。
 *
 * <p>与 {@link Role} 是多对多：一个权限点可授给多个角色，一个角色可持多个权限点。
 * 反向引用 {@link #roles} 由 {@code sys_role_permission} 维护，这里只读不写——
 * 授权关系由迁移脚本与后续后台维护，不在本实体上提供便捷的增删方法（避免散落写入口）。</p>
 */
@Entity
@Table(name = "sys_permission")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Permission {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 权限点码：全局唯一（公司统一制定，与 sys_role.code 同口径）。 */
  @Column(nullable = false, unique = true, length = 64)
  private String code;

  @Column(nullable = false, length = 64)
  private String name;

  /** 权限点分组：仅用于后台展示归类。 */
  @Column(length = 32)
  private String module;

  @Column(nullable = false)
  private Boolean enabled;

  @ManyToMany
  @JoinTable(name = "sys_role_permission",
      joinColumns = @JoinColumn(name = "permission_id"),
      inverseJoinColumns = @JoinColumn(name = "role_id"))
  private Set<Role> roles = new LinkedHashSet<>();
}
