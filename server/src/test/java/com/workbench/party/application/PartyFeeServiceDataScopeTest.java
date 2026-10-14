package com.workbench.party.application;

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
import com.workbench.platform.datascope.DataScopeResolver;
import com.workbench.platform.datascope.RoleBasedDataScopeResolver;
import com.workbench.platform.org.RoleQueryPort;
import com.workbench.platform.org.RoleRef;
import com.workbench.party.repository.PartyFeeRepository;
import com.workbench.testsupport.SecurityContextFixture;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 中台化回归基线（G1，party 模块）：党费含缴费基数（关联工资），属敏感数据，
 * 全部查询都要求 COMPANY 数据范围；PROJECT 用户一律「拒绝 + ACCESS_DENIED 审计」。
 *
 * <p>阶段 2（ADR-M008）：COMPANY 档的可见范围是本单位（company_id），登录态缺所属单位一律空列表
 * （有全公司数据范围但不知单位时，宁可空集也不猜单位）。</p>
 *
 * <p>本测试用<b>真实</b> RoleBasedDataScopeResolver 配 mock RoleQueryPort，保证语义被端到端锁定。</p>
 */
class PartyFeeServiceDataScopeTest {

  private static final long COMPANY_ID = 4L;

  private final PartyFeeRepository partyFeeRepository = mock(PartyFeeRepository.class);
  private final RoleQueryPort roleQueryPort = mock(RoleQueryPort.class);
  private final DataScopeResolver dataScopeResolver = new RoleBasedDataScopeResolver(roleQueryPort);
  private final AuditService auditService = mock(AuditService.class);

  private final PartyFeeService service = new PartyFeeService(partyFeeRepository, dataScopeResolver,
      auditService);

  @AfterEach
  void tearDown() {
    SecurityContextFixture.clear();
  }

  private static RoleRef role(String code, String dataScope, boolean enabled) {
    return new RoleRef(code, dataScope, enabled);
  }

  @Test
  void companyScopeReadsAllQuarters() {
    SecurityContextFixture.runAsInCompany("gm", COMPANY_ID, null, "GM");
    when(roleQueryPort.findByCodes(any())).thenReturn(List.of(role("GM", "COMPANY", true)));
    when(partyFeeRepository.findByCompanyIdOrderByIdAsc(COMPANY_ID)).thenReturn(List.of());

    assertTrue(service.list(null).isEmpty());
    assertTrue(service.list("   ").isEmpty(), "空白 quarter 视为未过滤");
    verify(partyFeeRepository, never()).findByCompanyIdAndQuarterOrderByIdAsc(any(), anyString());
  }

  @Test
  void companyScopeFiltersByQuarter() {
    SecurityContextFixture.runAsInCompany("gm", COMPANY_ID, null, "GM");
    when(roleQueryPort.findByCodes(any())).thenReturn(List.of(role("GM", "COMPANY", true)));
    when(partyFeeRepository.findByCompanyIdAndQuarterOrderByIdAsc(COMPANY_ID, "2026Q1"))
        .thenReturn(List.of());

    assertEquals(0, service.list("2026Q1").size());
    verify(partyFeeRepository).findByCompanyIdAndQuarterOrderByIdAsc(COMPANY_ID, "2026Q1");
  }

  @Test
  void companyScopeWithoutCompanyIdSeesNothing() {
    SecurityContextFixture.runAs("gm", null, "GM");
    when(roleQueryPort.findByCodes(any())).thenReturn(List.of(role("GM", "COMPANY", true)));

    assertTrue(service.list(null).isEmpty(), "有全公司数据范围但无登录态单位时必须空集，禁止猜单位");
    verifyNoInteractions(partyFeeRepository);
  }

  @Test
  void projectScopeIsDeniedAndAudited() {
    SecurityContextFixture.runAs("pm", 7L, "PROJECT_MANAGER");
    when(roleQueryPort.findByCodes(any()))
        .thenReturn(List.of(role("PROJECT_MANAGER", "PROJECT", true)));

    assertThrows(IllegalStateException.class, () -> service.list(null));
    verify(auditService).log(eq("pm"), eq("ACCESS_DENIED"), eq("PARTY_FEE"), eq("-"), anyString());
    verifyNoInteractions(partyFeeRepository);
  }

  @Test
  void disabledCompanyRoleIsDenied() {
    SecurityContextFixture.runAs("pm", 7L, "GM");
    when(roleQueryPort.findByCodes(any())).thenReturn(List.of(role("GM", "COMPANY", false)));

    assertThrows(IllegalStateException.class, () -> service.list(null));
    verifyNoInteractions(partyFeeRepository);
  }

  @Test
  void userWithoutRolesIsDeniedWithoutQueryingRoles() {
    SecurityContextFixture.runAs("nobody", null);
    when(roleQueryPort.findByCodes(any())).thenReturn(List.of());

    assertThrows(IllegalStateException.class, () -> service.list(null));
    verifyNoInteractions(roleQueryPort);
    verify(auditService).log(eq("nobody"), eq("ACCESS_DENIED"), eq("PARTY_FEE"), eq("-"), anyString());
  }
}
