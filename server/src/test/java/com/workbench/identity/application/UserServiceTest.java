package com.workbench.identity.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.workbench.audit.application.AuditService;
import com.workbench.identity.application.dto.UserCreateRequest;
import com.workbench.identity.domain.User;
import com.workbench.identity.repository.OrgUnitRepository;
import com.workbench.identity.repository.RoleRepository;
import com.workbench.identity.repository.UserRepository;
import com.workbench.testsupport.SecurityContextFixture;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 阶段 2（ADR-M006）：用户名只在单位内唯一之后，成员创建的唯一性校验必须带单位，
 * 新建账号必须写入归属单位；单位未知时拒绝创建而不是猜一个单位。
 */
class UserServiceTest {

  private static final long COMPANY_ID = 4L;

  private final UserRepository userRepository = mock(UserRepository.class);
  private final RoleRepository roleRepository = mock(RoleRepository.class);
  private final OrgUnitRepository orgUnitRepository = mock(OrgUnitRepository.class);
  private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
  private final AuditService auditService = mock(AuditService.class);

  private final UserService service = new UserService(userRepository, roleRepository,
      orgUnitRepository, passwordEncoder, auditService);

  @AfterEach
  void tearDown() {
    SecurityContextFixture.clear();
  }

  private static UserCreateRequest request(String username) {
    return new UserCreateRequest(username, "张三", null, null, null, null,
        List.of("EMPLOYEE"), true);
  }

  @Test
  void createUserChecksUniquenessWithinCurrentCompany() {
    SecurityContextFixture.runAsInCompany("admin", COMPANY_ID, null, "SYSTEM_ADMIN");
    when(passwordEncoder.encode(anyString())).thenReturn("$2hash");
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    service.createUser(request("zhangsan"));

    verify(userRepository).existsByCompanyIdAndUsername(COMPANY_ID, "zhangsan");
  }

  @Test
  void createUserWritesCurrentCompanyId() {
    SecurityContextFixture.runAsInCompany("admin", COMPANY_ID, null, "SYSTEM_ADMIN");
    when(passwordEncoder.encode(anyString())).thenReturn("$2hash");
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    service.createUser(request("zhangsan"));

    ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
    verify(userRepository).save(saved.capture());
    assertEquals(COMPANY_ID, saved.getValue().getCompanyId(),
        "新建账号必须落在当前操作者的单位内（company_id 非空）");
  }

  @Test
  void duplicateUsernameInSameCompanyIsRejected() {
    SecurityContextFixture.runAsInCompany("admin", COMPANY_ID, null, "SYSTEM_ADMIN");
    when(userRepository.existsByCompanyIdAndUsername(COMPANY_ID, "zhangsan")).thenReturn(true);

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> service.createUser(request("zhangsan")));
    assertTrue(ex.getMessage().contains("本单位已存在"));
    verify(userRepository, never()).save(any());
  }

  @Test
  void createUserWithoutCompanyIdIsRejectedInsteadOfGuessing() {
    // 阶段 2 之前签发的旧 token：Claims 里没有 companyId。
    SecurityContextFixture.runAs("admin", null, "SYSTEM_ADMIN");

    IllegalStateException ex = assertThrows(IllegalStateException.class,
        () -> service.createUser(request("zhangsan")));
    assertTrue(ex.getMessage().contains("无法确定"));
    verifyNoInteractions(userRepository);
  }
}
