package com.workbench.identity.application;

import com.workbench.audit.application.AuditService;
import com.workbench.identity.application.dto.RoleResponse;
import com.workbench.identity.application.dto.UserCreateRequest;
import com.workbench.identity.application.dto.UserResponse;
import com.workbench.identity.application.dto.UserUpdateRequest;
import com.workbench.identity.domain.OrgUnit;
import com.workbench.identity.domain.Role;
import com.workbench.identity.domain.User;
import com.workbench.identity.repository.OrgUnitRepository;
import com.workbench.identity.repository.RoleRepository;
import com.workbench.identity.repository.UserRepository;
import com.workbench.security.CurrentUserHolder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 成员（系统登录账号）管理。仅 SYSTEM_ADMIN 可调（由 UserController 的 @PreAuthorize 把关）。
 * 增删改均写审计日志；删除带防呆：不能删自己、至少保留一个启用管理员。
 * 阶段 2 起用户名只在单位内唯一（ADR-M006），新建账号归属当前操作者的单位。
 */
@Service
public class UserService {

  private static final String DEFAULT_PASSWORD = "User@2026";
  private static final String ADMIN_ROLE_CODE = "SYSTEM_ADMIN";

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final OrgUnitRepository orgUnitRepository;
  private final PasswordEncoder passwordEncoder;
  private final AuditService auditService;

  public UserService(UserRepository userRepository, RoleRepository roleRepository,
      OrgUnitRepository orgUnitRepository, PasswordEncoder passwordEncoder, AuditService auditService) {
    this.userRepository = userRepository;
    this.roleRepository = roleRepository;
    this.orgUnitRepository = orgUnitRepository;
    this.passwordEncoder = passwordEncoder;
    this.auditService = auditService;
  }

  @Transactional(readOnly = true)
  public List<UserResponse> listUsers(String keyword, Long projectId, String roleCode) {
    List<User> users = userRepository.findAll();
    String kw = (keyword == null) ? null : keyword.trim().toLowerCase();
    List<UserResponse> result = new ArrayList<>();
    for (User u : users) {
      if (kw != null && !(u.getUsername().toLowerCase().contains(kw) || u.getName().toLowerCase().contains(kw))) {
        continue;
      }
      if (projectId != null && !Objects.equals(u.getProjectId(), projectId)) {
        continue;
      }
      if (roleCode != null && u.getRoles().stream().noneMatch(r -> roleCode.equals(r.getCode()))) {
        continue;
      }
      result.add(toResponse(u));
    }
    return result;
  }

  @Transactional(readOnly = true)
  public UserResponse getUser(Long id) {
    return toResponse(requireUser(id));
  }

  @Transactional
  public UserResponse createUser(UserCreateRequest req) {
    // 阶段 2：用户名只在单位内唯一（ADR-M006），唯一性校验必须带单位；
    // 归属单位取当前操作者的单位（跨单位建号留到阶段 3 的单位切换）。
    Long companyId = CurrentUserHolder.currentCompanyId();
    if (companyId == null) {
      throw new IllegalStateException("无法确定当前账号的所属单位，拒绝创建成员");
    }
    if (userRepository.existsByCompanyIdAndUsername(companyId, req.username())) {
      throw new IllegalArgumentException("用户名「" + req.username() + "」在本单位已存在");
    }
    Set<Role> roles = resolveRoles(req.roleCodes());
    String hash = passwordEncoder.encode(
        StringUtils.hasText(req.password()) ? req.password() : DEFAULT_PASSWORD);
    User user = User.builder()
        .username(req.username())
        .name(req.name())
        .passwordHash(hash)
        .orgId(req.projectId())
        .projectId(req.projectId())
        .companyId(companyId)
        .employmentType(StringUtils.hasText(req.employmentType()) ? req.employmentType() : "正式职工")
        .phone(req.phone())
        .enabled(req.enabled() != null ? req.enabled() : true)
        .roles(roles)
        .build();
    User saved = userRepository.save(user);
    auditService.log("USER_CREATE", "USER", saved.getUsername(), "创建成员 " + saved.getName());
    return toResponse(saved);
  }

  @Transactional
  public UserResponse updateUser(Long id, UserUpdateRequest req) {
    User user = requireUser(id);
    user.setName(req.name());
    user.setPhone(req.phone());
    if (StringUtils.hasText(req.employmentType())) {
      user.setEmploymentType(req.employmentType());
    }
    user.setProjectId(req.projectId());
    user.setOrgId(req.projectId());
    if (req.enabled() != null) {
      user.setEnabled(req.enabled());
    }
    if (req.roleCodes() != null) {
      user.setRoles(resolveRoles(req.roleCodes()));
    }
    if (StringUtils.hasText(req.password())) {
      user.setPasswordHash(passwordEncoder.encode(req.password()));
    }
    User saved = userRepository.save(user);
    auditService.log("USER_UPDATE", "USER", saved.getUsername(), "更新成员 " + saved.getName());
    return toResponse(saved);
  }

  @Transactional
  public void deleteUser(Long id) {
    User user = requireUser(id);
    if (user.getUsername().equals(CurrentUserHolder.currentUsername())) {
      throw new IllegalStateException("不能删除当前登录的账号");
    }
    if (user.getRoles().stream().anyMatch(r -> ADMIN_ROLE_CODE.equals(r.getCode()))
        && countEnabledAdmins() <= 1) {
      throw new IllegalStateException("至少需保留一个启用状态的管理员账号");
    }
    String uname = user.getUsername();
    userRepository.delete(user);
    auditService.log("USER_DELETE", "USER", uname, "删除成员 " + uname);
  }

  @Transactional
  public void resetPassword(Long id, String password) {
    User user = requireUser(id);
    if (!StringUtils.hasText(password)) {
      throw new IllegalArgumentException("密码不能为空");
    }
    user.setPasswordHash(passwordEncoder.encode(password));
    userRepository.save(user);
    auditService.log("USER_PASSWORD_RESET", "USER", user.getUsername(), "重置密码 " + user.getUsername());
  }

  @Transactional(readOnly = true)
  public List<RoleResponse> listRoles() {
    return roleRepository.findAll().stream()
        .map(r -> new RoleResponse(r.getCode(), r.getName(), r.getDataScope(), r.getEnabled()))
        .toList();
  }

  private long countEnabledAdmins() {
    return userRepository.findAll().stream()
        .filter(u -> Boolean.TRUE.equals(u.getEnabled()))
        .filter(u -> u.getRoles().stream().anyMatch(r -> ADMIN_ROLE_CODE.equals(r.getCode())))
        .count();
  }

  private Set<Role> resolveRoles(List<String> codes) {
    if (codes == null || codes.isEmpty()) {
      return new HashSet<>();
    }
    return new HashSet<>(roleRepository.findByCodeIn(codes));
  }

  private User requireUser(Long id) {
    return userRepository.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("成员不存在: " + id));
  }

  private UserResponse toResponse(User u) {
    String projectName = (u.getProjectId() != null)
        ? orgUnitRepository.findById(u.getProjectId()).map(OrgUnit::getName).orElse(null)
        : null;
    List<String> roleCodes = u.getRoles().stream().map(Role::getCode).toList();
    List<String> roleNames = u.getRoles().stream().map(Role::getName).toList();
    return new UserResponse(u.getId(), u.getUsername(), u.getName(), u.getPhone(),
        u.getEmploymentType(), u.getOrgId(), u.getProjectId(), projectName,
        roleCodes, roleNames, u.getEnabled());
  }
}
