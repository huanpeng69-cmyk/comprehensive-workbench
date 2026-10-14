package com.workbench.hr.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.workbench.audit.application.AuditService;
import com.workbench.hr.domain.Employee;
import com.workbench.hr.repository.EmployeeRepository;
import com.workbench.platform.datascope.DataScopeResolver;
import com.workbench.platform.datascope.RoleBasedDataScopeResolver;
import com.workbench.platform.org.OrgQueryPort;
import com.workbench.platform.org.RoleQueryPort;
import com.workbench.platform.org.RoleRef;
import com.workbench.platform.org.WriteOwnershipResolver;
import com.workbench.testsupport.SecurityContextFixture;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 中台化回归基线（G1，hr 模块）：数据范围必须只在服务端生效。
 *
 * <p>阶段 1 锁定的语义：COMPANY 数据范围读全表；PROJECT 数据范围只读本项目部；PROJECT 且无
 * projectId 读空；enabled=false 的 COMPANY 角色不生效（不得因为角色表里有个 COMPANY 行就放行）；
 * 越权写操作「拒绝 + ACCESS_DENIED 审计」。</p>
 *
 * <p>阶段 2（ADR-M008）把 COMPANY 档的可见范围从「全表」收紧为「本单位」：COMPANY 档必须带
 * 登录态所属单位，缺失一律空列表（绝不退化成查全库）；带 id 的写操作先过
 * {@code canAccessCompany} 再落 {@code company_id}；新增数据的归属单位由
 * WriteOwnershipResolver 解析，解析不出来就拒绝写入。</p>
 *
 * <p>本测试用<b>真实</b> RoleBasedDataScopeResolver 与<b>真实</b> WriteOwnershipResolver，
 * 只 mock 端口（RoleQueryPort / OrgQueryPort），保证数据范围语义被端到端锁定；
 * 若改成 mock 掉 resolver，本测试会退化为空转（明确否决）。</p>
 */
class EmployeeServiceDataScopeTest {

  private static final long COMPANY_ID = 4L;
  private static final long OTHER_COMPANY_ID = 8L;
  private static final long PROJECT_ID = 7L;

  private final EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
  private final OrgQueryPort orgQueryPort = mock(OrgQueryPort.class);
  private final RoleQueryPort roleQueryPort = mock(RoleQueryPort.class);
  private final AuditService auditService = mock(AuditService.class);
  private final DataScopeResolver dataScopeResolver = new RoleBasedDataScopeResolver(roleQueryPort);
  private final WriteOwnershipResolver writeOwnershipResolver =
      new WriteOwnershipResolver(orgQueryPort, dataScopeResolver, auditService);

  private final EmployeeService service = new EmployeeService(employeeRepository, orgQueryPort,
      dataScopeResolver, auditService, writeOwnershipResolver);

  @AfterEach
  void tearDown() {
    SecurityContextFixture.clear();
  }

  private static RoleRef role(String code, String dataScope, boolean enabled) {
    return new RoleRef(code, dataScope, enabled);
  }

  private void stubRoles(RoleRef... roles) {
    when(roleQueryPort.findByCodes(any())).thenReturn(List.of(roles));
  }

  @Test
  void companyScopeReadsOwnCompanyOnly() {
    SecurityContextFixture.runAsInCompany("gm", COMPANY_ID, null, "GM");
    stubRoles(role("GM", "COMPANY", true));
    when(employeeRepository.findAllByCompanyIdOrderBySeqAscIdAsc(COMPANY_ID)).thenReturn(List.of());

    assertTrue(service.list().isEmpty());
    verify(employeeRepository).findAllByCompanyIdOrderBySeqAscIdAsc(COMPANY_ID);
    verify(employeeRepository, never()).findByProjectIdOrderBySeqAscIdAsc(any());
  }

  @Test
  void companyScopeWithoutCompanyIdSeesNothing() {
    SecurityContextFixture.runAs("gm", null, "GM");
    stubRoles(role("GM", "COMPANY", true));

    assertTrue(service.list().isEmpty(), "COMPANY 档缺登录态所属单位必须看到空列表，不得放行全库");
    verifyNoInteractions(employeeRepository);
  }

  @Test
  void projectScopeReadsOwnProjectOnly() {
    SecurityContextFixture.runAs("pm", PROJECT_ID, "PROJECT_MANAGER");
    stubRoles(role("PROJECT_MANAGER", "PROJECT", true));
    when(employeeRepository.findByProjectIdOrderBySeqAscIdAsc(PROJECT_ID)).thenReturn(List.of());

    assertTrue(service.list().isEmpty());
    verify(employeeRepository).findByProjectIdOrderBySeqAscIdAsc(PROJECT_ID);
    verify(employeeRepository, never()).findAllByCompanyIdOrderBySeqAscIdAsc(any());
  }

  @Test
  void projectScopeWithoutProjectIdSeesNothing() {
    SecurityContextFixture.runAs("pm", null, "PROJECT_MANAGER");
    stubRoles(role("PROJECT_MANAGER", "PROJECT", true));

    assertTrue(service.list().isEmpty(), "无项目部归属的 PROJECT 用户必须看到空列表（严格模式）");
    verifyNoInteractions(employeeRepository);
  }

  @Test
  void disabledCompanyRoleDoesNotGrantCompanyScope() {
    SecurityContextFixture.runAs("pm", PROJECT_ID, "GM");
    stubRoles(role("GM", "COMPANY", false));
    when(employeeRepository.findByProjectIdOrderBySeqAscIdAsc(PROJECT_ID)).thenReturn(List.of());

    service.list();
    verify(employeeRepository).findByProjectIdOrderBySeqAscIdAsc(PROJECT_ID);
    verify(employeeRepository, never()).findAllByCompanyIdOrderBySeqAscIdAsc(any());
  }

  @Test
  void createIsDeniedForProjectScopeAndAudited() {
    SecurityContextFixture.runAs("pm", PROJECT_ID, "PROJECT_MANAGER");
    stubRoles(role("PROJECT_MANAGER", "PROJECT", true));

    assertThrows(IllegalStateException.class,
        () -> service.create(Employee.builder().name("张三").build()));
    verify(auditService).log(eq("pm"), eq("ACCESS_DENIED"), eq("EMPLOYEE"), eq("-"), anyString());
    verifyNoInteractions(employeeRepository);
  }

  @Test
  void updateOfOtherProjectEmployeeIsDeniedAndAudited() {
    SecurityContextFixture.runAs("pm", PROJECT_ID, "PROJECT_MANAGER");
    stubRoles(role("PROJECT_MANAGER", "PROJECT", true));
    Employee other = Employee.builder().id(99L).name("李四").projectId(9L).build();
    when(employeeRepository.findById(99L)).thenReturn(Optional.of(other));

    assertThrows(IllegalStateException.class,
        () -> service.update(99L, Employee.builder().name("李四").build()));
    verify(auditService).log(eq("pm"), eq("ACCESS_DENIED"), eq("EMPLOYEE"), eq("99"), anyString());
    verify(employeeRepository, never()).save(any());
  }

  @Test
  void updateOfOtherCompanyEmployeeIsDeniedAndAudited() {
    SecurityContextFixture.runAsInCompany("gm", COMPANY_ID, null, "GM");
    stubRoles(role("GM", "COMPANY", true));
    Employee other = Employee.builder().id(99L).name("李四").projectId(9L)
        .companyId(OTHER_COMPANY_ID).build();
    when(employeeRepository.findById(99L)).thenReturn(Optional.of(other));

    assertThrows(IllegalStateException.class,
        () -> service.update(99L, Employee.builder().name("李四").build()));
    verify(auditService).log(eq("gm"), eq("ACCESS_DENIED"), eq("EMPLOYEE"), eq("99"), anyString());
    verify(employeeRepository, never()).save(any());
  }

  @Test
  void companyScopeCanCreate() {
    SecurityContextFixture.runAsInCompany("gm", COMPANY_ID, null, "GM");
    stubRoles(role("GM", "COMPANY", true));
    when(employeeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    Employee created = service.create(Employee.builder().name("王五").build());

    assertEquals("王五", created.getName());
    assertEquals(0, created.getSeq(), "seq 为空时补 0，避免非空约束报错");
    assertEquals(COMPANY_ID, created.getCompanyId(), "新增员工必须落当前登录用户的所属单位");
    verify(auditService).log(eq("EMPLOYEE_CREATE"), eq("EMPLOYEE"), anyString(), anyString());
  }
}
