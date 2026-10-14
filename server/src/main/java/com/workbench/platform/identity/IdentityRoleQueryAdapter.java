package com.workbench.platform.identity;

import com.workbench.identity.domain.Role;
import com.workbench.identity.repository.RoleRepository;
import com.workbench.platform.org.RoleQueryPort;
import com.workbench.platform.org.RoleRef;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@link RoleQueryPort} 的适配器：把 identity 的角色实体翻译成平台视图记录。
 *
 * <p>{@code enabled} 用 {@code Boolean.TRUE.equals(...)} 归一：库中该列非空，
 * 但实体字段是包装类型，null 必须视为「未启用」（与改造前各业务服务的写法一致）。</p>
 */
@Component
public class IdentityRoleQueryAdapter implements RoleQueryPort {

  private final RoleRepository roleRepository;

  public IdentityRoleQueryAdapter(RoleRepository roleRepository) {
    this.roleRepository = roleRepository;
  }

  @Override
  public List<RoleRef> findByCodes(Collection<String> codes) {
    if (codes == null || codes.isEmpty()) {
      return List.of();
    }
    return roleRepository.findByCodeIn(codes).stream()
        .map(IdentityRoleQueryAdapter::toRef)
        .toList();
  }

  private static RoleRef toRef(Role role) {
    return new RoleRef(role.getCode(), role.getDataScope(), Boolean.TRUE.equals(role.getEnabled()));
  }
}
