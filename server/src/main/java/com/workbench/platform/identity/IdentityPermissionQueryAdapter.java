package com.workbench.platform.identity;

import com.workbench.identity.repository.PermissionRepository;
import java.util.Collection;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * {@link PermissionQueryPort} 的适配器：按角色码查权限点 code。
 *
 * <p>放在 {@code platform.identity} 是因为它是唯一允许直连 identity 仓储的适配层
 * （ADR-M007：例外只许这一处）。</p>
 */
@Component
public class IdentityPermissionQueryAdapter implements PermissionQueryPort {

  private final PermissionRepository permissionRepository;

  public IdentityPermissionQueryAdapter(PermissionRepository permissionRepository) {
    this.permissionRepository = permissionRepository;
  }

  @Override
  public Set<String> findByRoleCodes(Collection<String> roleCodes) {
    if (roleCodes == null || roleCodes.isEmpty()) {
      return Set.of();
    }
    return permissionRepository.findCodesByRoleCodes(roleCodes);
  }
}
