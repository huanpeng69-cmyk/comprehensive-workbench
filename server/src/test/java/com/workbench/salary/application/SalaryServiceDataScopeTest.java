package com.workbench.salary.application;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workbench.audit.application.AuditService;
import com.workbench.platform.datascope.DataScopeResolver;
import com.workbench.platform.datascope.RoleBasedDataScopeResolver;
import com.workbench.platform.org.OrgQueryPort;
import com.workbench.platform.org.RoleQueryPort;
import com.workbench.platform.org.RoleRef;
import com.workbench.platform.org.WriteOwnershipResolver;
import com.workbench.salary.repository.PerfRecordRepository;
import com.workbench.salary.repository.SalaryConfigRepository;
import com.workbench.salary.repository.SalaryMonthRepository;
import com.workbench.salary.repository.SalaryRecordRepository;
import com.workbench.testsupport.SecurityContextFixture;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 中台化回归基线（G1，salary 模块）：工资模块是敏感数据总闸门，
 * 所有接口（含查询）都要求 COMPANY 数据范围；PROJECT 用户读写一律拒绝并写审计。
 *
 * <p>阶段 2（ADR-M008）：月表可见范围是本单位（company_id），登录态缺所属单位一律空列表；
 * 保存/删除工资月度数据时归属单位由 WriteOwnershipResolver 解析，解析不出来就拒绝写入。
 * {@code salary_config} 未纳入多单位改造（V10 未给该表加 company_id），配置仍为全局。</p>
 *
 * <p>本测试用<b>真实</b> RoleBasedDataScopeResolver 与<b>真实</b> WriteOwnershipResolver，
 * 只 mock 端口与仓储，保证语义被端到端锁定。</p>
 */
class SalaryServiceDataScopeTest {

  private static final long COMPANY_ID = 4L;

  private final SalaryMonthRepository monthRepository = mock(SalaryMonthRepository.class);
  private final SalaryRecordRepository recordRepository = mock(SalaryRecordRepository.class);
  private final PerfRecordRepository perfRepository = mock(PerfRecordRepository.class);
  private final SalaryConfigRepository configRepository = mock(SalaryConfigRepository.class);
  private final OrgQueryPort orgQueryPort = mock(OrgQueryPort.class);
  private final RoleQueryPort roleQueryPort = mock(RoleQueryPort.class);
  private final DataScopeResolver dataScopeResolver = new RoleBasedDataScopeResolver(roleQueryPort);
  private final AuditService auditService = mock(AuditService.class);
  private final WriteOwnershipResolver writeOwnershipResolver =
      new WriteOwnershipResolver(orgQueryPort, dataScopeResolver, auditService);

  private final SalaryService service = new SalaryService(monthRepository, recordRepository,
      perfRepository, configRepository, dataScopeResolver, auditService, new ObjectMapper(),
      writeOwnershipResolver);

  @AfterEach
  void tearDown() {
    SecurityContextFixture.clear();
  }

  private static RoleRef role(String code, String dataScope, boolean enabled) {
    return new RoleRef(code, dataScope, enabled);
  }

  @Test
  void companyScopeCanListOwnCompanyMonths() {
    SecurityContextFixture.runAsInCompany("hr", COMPANY_ID, null, "HR_ADMIN");
    when(roleQueryPort.findByCodes(any())).thenReturn(List.of(role("HR_ADMIN", "COMPANY", true)));
    when(monthRepository.findByCompanyIdOrderByMonthDesc(COMPANY_ID)).thenReturn(List.of());

    assertTrue(service.listMonths().isEmpty());
    verify(monthRepository).findByCompanyIdOrderByMonthDesc(COMPANY_ID);
  }

  @Test
  void companyScopeWithoutCompanyIdSeesNothing() {
    SecurityContextFixture.runAs("hr", null, "HR_ADMIN");
    when(roleQueryPort.findByCodes(any())).thenReturn(List.of(role("HR_ADMIN", "COMPANY", true)));

    assertTrue(service.listMonths().isEmpty(), "有全公司数据范围但无登录态单位时必须空集，禁止放行全库");
    verifyNoInteractions(monthRepository);
  }

  @Test
  void projectScopeIsDeniedOnReadAndAudited() {
    SecurityContextFixture.runAs("pm", 7L, "PROJECT_MANAGER");
    when(roleQueryPort.findByCodes(any()))
        .thenReturn(List.of(role("PROJECT_MANAGER", "PROJECT", true)));

    assertThrows(IllegalStateException.class, () -> service.listMonths());
    verify(auditService).log(eq("pm"), eq("ACCESS_DENIED"), eq("SALARY"), eq("-"), anyString());
    verifyNoInteractions(monthRepository);
  }

  @Test
  void projectScopeIsDeniedOnWriteAndTouchesNoSalaryTable() {
    SecurityContextFixture.runAs("pm", 7L, "PROJECT_MANAGER");
    when(roleQueryPort.findByCodes(any()))
        .thenReturn(List.of(role("PROJECT_MANAGER", "PROJECT", true)));

    assertThrows(IllegalStateException.class, () -> service.deleteMonth("2026-09"));
    verify(auditService).log(eq("pm"), eq("ACCESS_DENIED"), eq("SALARY"), eq("-"), anyString());
    verifyNoInteractions(monthRepository, recordRepository, perfRepository, configRepository);
  }

  @Test
  void disabledCompanyRoleIsDenied() {
    SecurityContextFixture.runAs("pm", 7L, "HR_ADMIN");
    when(roleQueryPort.findByCodes(any())).thenReturn(List.of(role("HR_ADMIN", "COMPANY", false)));

    assertThrows(IllegalStateException.class, () -> service.listMonths());
    verifyNoInteractions(monthRepository);
  }

  @Test
  void userWithoutRolesIsDeniedWithoutQueryingRoles() {
    SecurityContextFixture.runAs("nobody", null);
    when(roleQueryPort.findByCodes(any())).thenReturn(List.of());

    assertThrows(IllegalStateException.class, () -> service.listMonths());
    verifyNoInteractions(roleQueryPort, monthRepository);
  }
}
