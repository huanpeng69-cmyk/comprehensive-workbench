package com.workbench.leave.application;

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
import com.workbench.platform.org.OrgQueryPort;
import com.workbench.platform.org.RoleQueryPort;
import com.workbench.platform.org.RoleRef;
import com.workbench.platform.org.UserQueryPort;
import com.workbench.platform.org.WriteOwnershipResolver;
import com.workbench.leave.domain.Leave;
import com.workbench.leave.repository.LeaveRepository;
import com.workbench.testsupport.SecurityContextFixture;
import com.workbench.workflow.application.BizCompletionRegistry;
import com.workbench.workflow.application.WorkflowService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 中台化回归基线（G1，leave 模块）：请假记录的数据范围过滤与越权拒绝。
 *
 * <p>阶段 1 把三段重复的 callerHasCompanyScope/assertCanModify 收敛为
 * platform/DataScopeResolver 后，本测试必须仍然全绿。</p>
 *
 * <p>阶段 2（ADR-M008）：COMPANY 档的可见范围是本单位（company_id），登录态缺所属单位一律空列表；
 * 按 id 取到的记录先过 {@code canAccessCompany}，跨单位一律「拒绝 + ACCESS_DENIED 审计」；
 * 新增数据落 WriteOwnershipResolver 解析出的单位。</p>
 *
 * <p>本测试用<b>真实</b> RoleBasedDataScopeResolver 与<b>真实</b> WriteOwnershipResolver，
 * 只 mock 端口与仓储，保证语义被端到端锁定。</p>
 */
class LeaveServiceDataScopeTest {

  private static final long COMPANY_ID = 4L;
  private static final long PROJECT_ID = 7L;

  private final LeaveRepository leaveRepository = mock(LeaveRepository.class);
  private final WorkflowService workflowService = mock(WorkflowService.class);
  private final BizCompletionRegistry completionRegistry = mock(BizCompletionRegistry.class);
  private final AuditService auditService = mock(AuditService.class);
  private final UserQueryPort userQueryPort = mock(UserQueryPort.class);
  private final OrgQueryPort orgQueryPort = mock(OrgQueryPort.class);
  private final RoleQueryPort roleQueryPort = mock(RoleQueryPort.class);
  private final DataScopeResolver dataScopeResolver = new RoleBasedDataScopeResolver(roleQueryPort);
  private final WriteOwnershipResolver writeOwnershipResolver =
      new WriteOwnershipResolver(orgQueryPort, dataScopeResolver, auditService);

  private final LeaveService service = new LeaveService(leaveRepository, workflowService,
      completionRegistry, auditService, userQueryPort, orgQueryPort, dataScopeResolver,
      writeOwnershipResolver);

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
    when(leaveRepository.findByCompanyIdOrderByCreatedAtDesc(COMPANY_ID)).thenReturn(List.of());

    assertTrue(service.list().isEmpty());
    verify(leaveRepository).findByCompanyIdOrderByCreatedAtDesc(COMPANY_ID);
    verify(leaveRepository, never()).findByProjectIdOrderByCreatedAtDesc(any());
  }

  @Test
  void companyScopeWithoutCompanyIdSeesNothing() {
    SecurityContextFixture.runAs("gm", null, "GM");
    stubRoles(role("GM", "COMPANY", true));

    assertTrue(service.list().isEmpty(), "COMPANY 档缺登录态所属单位必须看到空列表，不得放行全库");
    verifyNoInteractions(leaveRepository);
  }

  @Test
  void projectScopeReadsOwnProjectOnly() {
    SecurityContextFixture.runAs("pm", PROJECT_ID, "PROJECT_MANAGER");
    stubRoles(role("PROJECT_MANAGER", "PROJECT", true));
    when(leaveRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(List.of());

    assertTrue(service.list().isEmpty());
    verify(leaveRepository).findByProjectIdOrderByCreatedAtDesc(PROJECT_ID);
    verify(leaveRepository, never()).findByCompanyIdOrderByCreatedAtDesc(any());
  }

  @Test
  void projectScopeWithoutProjectIdSeesNothing() {
    SecurityContextFixture.runAs("pm", null, "PROJECT_MANAGER");
    stubRoles(role("PROJECT_MANAGER", "PROJECT", true));

    assertTrue(service.list().isEmpty(), "无项目部归属的 PROJECT 用户必须看到空列表（严格模式）");
    verifyNoInteractions(leaveRepository);
  }

  @Test
  void updateOfOtherProjectLeaveIsDeniedAndAudited() {
    SecurityContextFixture.runAs("pm", PROJECT_ID, "PROJECT_MANAGER");
    stubRoles(role("PROJECT_MANAGER", "PROJECT", true));
    Leave other = Leave.builder().id(11L).projectId(9L).applicantName("李四").status("待审批").build();
    when(leaveRepository.findById(11L)).thenReturn(Optional.of(other));

    assertThrows(IllegalStateException.class,
        () -> service.update(11L, Leave.builder().leaveType("事假").build()));
    verify(auditService).log(eq("pm"), eq("ACCESS_DENIED"), eq("LEAVE"), eq("11"), anyString());
    verify(leaveRepository, never()).save(any());
  }

  @Test
  void updateOfOtherCompanyLeaveIsDeniedAndAudited() {
    SecurityContextFixture.runAsInCompany("gm", COMPANY_ID, null, "GM");
    stubRoles(role("GM", "COMPANY", true));
    Leave other = Leave.builder().id(11L).projectId(9L).companyId(8L)
        .applicantName("李四").status("待审批").build();
    when(leaveRepository.findById(11L)).thenReturn(Optional.of(other));

    assertThrows(IllegalStateException.class,
        () -> service.update(11L, Leave.builder().leaveType("事假").build()));
    verify(auditService).log(eq("gm"), eq("ACCESS_DENIED"), eq("LEAVE"), eq("11"), anyString());
    verify(leaveRepository, never()).save(any());
  }

  @Test
  void removeOfOtherProjectLeaveIsDeniedAndAudited() {
    SecurityContextFixture.runAs("pm", PROJECT_ID, "PROJECT_MANAGER");
    stubRoles(role("PROJECT_MANAGER", "PROJECT", true));
    Leave other = Leave.builder().id(12L).projectId(9L).applicantName("李四").build();
    when(leaveRepository.findById(12L)).thenReturn(Optional.of(other));

    assertThrows(IllegalStateException.class, () -> service.remove(12L));
    verify(auditService).log(eq("pm"), eq("ACCESS_DENIED"), eq("LEAVE"), eq("12"), anyString());
    verify(leaveRepository, never()).delete(any());
  }
}
