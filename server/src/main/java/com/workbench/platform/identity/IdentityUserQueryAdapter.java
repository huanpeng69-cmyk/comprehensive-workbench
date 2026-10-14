package com.workbench.platform.identity;

import com.workbench.identity.domain.User;
import com.workbench.identity.repository.UserRepository;
import com.workbench.platform.org.UserQueryPort;
import com.workbench.platform.org.UserRef;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * {@link UserQueryPort} 的适配器：把 identity 的用户实体翻译成平台视图记录。
 *
 * <p>返回的「登录名 → 姓名」Map 是新建 {@link HashMap}，但契约上不承诺可变，
 * 调用方（{@code WorkflowService.nameMap}）仍会自行拷贝，避免以后换实现踩坑。</p>
 */
@Component
public class IdentityUserQueryAdapter implements UserQueryPort {

  private final UserRepository userRepository;

  public IdentityUserQueryAdapter(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  @Override
  public Optional<UserRef> findByCompanyIdAndUsername(Long companyId, String username) {
    if (companyId == null) {
      return Optional.empty();
    }
    return userRepository.findByCompanyIdAndUsername(companyId, username)
        .map(u -> new UserRef(u.getUsername(), u.getName()));
  }

  @Override
  public boolean existsByCompanyIdAndUsername(Long companyId, String username) {
    return companyId != null && userRepository.existsByCompanyIdAndUsername(companyId, username);
  }

  @Override
  public List<String> findEnabledUsernamesByRoleCodeAndCompanyId(String roleCode, Long companyId) {
    if (companyId == null) {
      return List.of();
    }
    return userRepository.findEnabledByRoleCodeAndCompanyId(roleCode, companyId).stream()
        .map(User::getUsername)
        .toList();
  }

  @Override
  public List<String> findEnabledUsernamesByRoleCodeAndProjectId(String roleCode,
      Long projectId) {
    return userRepository.findEnabledByRoleCodeAndProjectId(roleCode, projectId).stream()
        .map(User::getUsername)
        .toList();
  }

  @Override
  public Map<String, String> findNamesByUsernames(Collection<String> usernames) {
    if (usernames == null || usernames.isEmpty()) {
      return new HashMap<>();
    }
    return userRepository.findByUsernameIn(usernames).stream()
        .collect(Collectors.toMap(User::getUsername, User::getName, (a, b) -> a, HashMap::new));
  }
}
