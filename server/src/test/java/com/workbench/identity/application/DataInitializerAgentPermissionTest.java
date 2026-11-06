package com.workbench.identity.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.workbench.identity.domain.Permission;
import com.workbench.identity.domain.Role;
import com.workbench.identity.repository.OrgUnitRepository;
import com.workbench.identity.repository.PermissionRepository;
import com.workbench.identity.repository.RoleRepository;
import com.workbench.identity.repository.UserRepository;
import com.workbench.security.Permissions;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 「AI 助手使用」权限点在启动期的授予契约（ADR-M013）。
 *
 * <p>锁住三件事：权限点不存在时能补建、授予面是<b>全部角色</b>、重复启动不再写库（幂等）。
 * 第三点尤其重要：本方法每次启动都跑，若不幂等，每次重启都会往 sys_role_permission 重写一遍。</p>
 */
class DataInitializerAgentPermissionTest {

  private final RoleRepository roleRepository = mock(RoleRepository.class);
  private final PermissionRepository permissionRepository = mock(PermissionRepository.class);

  private DataInitializer initializer() {
    return new DataInitializer(mock(OrgUnitRepository.class), mock(UserRepository.class),
        roleRepository, mock(PasswordEncoder.class), permissionRepository);
  }

  @Test
  void createsPermissionAndGrantsEveryExistingRole() {
    when(permissionRepository.findByCode(Permissions.AI_AGENT_USE)).thenReturn(Optional.empty());
    when(permissionRepository.save(any(Permission.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    Role employee = Role.builder().code("EMPLOYEE").name("普通员工").dataScope("PROJECT").build();
    Role hr = Role.builder().code("HR_ADMIN").name("人力管理员").dataScope("COMPANY").build();
    when(roleRepository.findAll()).thenReturn(List.of(employee, hr));

    initializer().grantAgentUseToAllRoles();

    ArgumentCaptor<Permission> saved = ArgumentCaptor.forClass(Permission.class);
    verify(permissionRepository, times(2)).save(saved.capture());
    Permission created = saved.getAllValues().get(0);
    assertEquals(Permissions.AI_AGENT_USE, created.getCode(), "权限点码必须与常量一致");
    assertEquals("AI 助手使用", created.getName());
    assertEquals("ai", created.getModule());
    assertEquals(Boolean.TRUE, created.getEnabled(), "禁用的权限点不进 JWT，等于没授");
    assertTrue(created.getRoles().containsAll(List.of(employee, hr)),
        "上线当天不能改变可用性：全部既有角色都必须被授予");
  }

  @Test
  void grantsOnlyTheMissingRoles() {
    Role employee = Role.builder().code("EMPLOYEE").dataScope("PROJECT").build();
    Role hr = Role.builder().code("HR_ADMIN").dataScope("COMPANY").build();
    Permission existing = Permission.builder()
        .code(Permissions.AI_AGENT_USE)
        .name("AI 助手使用")
        .module("ai")
        .enabled(true)
        .roles(new LinkedHashSet<>(List.of(employee)))
        .build();
    when(permissionRepository.findByCode(Permissions.AI_AGENT_USE)).thenReturn(Optional.of(existing));
    when(permissionRepository.save(any(Permission.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(roleRepository.findAll()).thenReturn(List.of(employee, hr));

    initializer().grantAgentUseToAllRoles();

    ArgumentCaptor<Permission> saved = ArgumentCaptor.forClass(Permission.class);
    verify(permissionRepository, times(1)).save(saved.capture());
    assertEquals(2, saved.getValue().getRoles().size(),
        "只补缺的角色，已有授权不得被覆盖或重复");
    assertTrue(saved.getValue().getRoles().containsAll(List.of(employee, hr)));
  }

  @Test
  void secondStartupWritesNothingWhenEveryRoleAlreadyHoldsIt() {
    Role employee = Role.builder().code("EMPLOYEE").dataScope("PROJECT").build();
    Permission existing = Permission.builder()
        .code(Permissions.AI_AGENT_USE)
        .name("AI 助手使用")
        .module("ai")
        .enabled(true)
        .roles(new LinkedHashSet<>(List.of(employee)))
        .build();
    when(permissionRepository.findByCode(Permissions.AI_AGENT_USE)).thenReturn(Optional.of(existing));
    when(roleRepository.findAll()).thenReturn(List.of(employee));

    initializer().grantAgentUseToAllRoles();

    verify(permissionRepository, never()).save(any(Permission.class));
  }
}
