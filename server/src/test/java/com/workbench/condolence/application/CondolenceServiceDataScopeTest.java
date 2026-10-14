package com.workbench.condolence.application;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.workbench.audit.application.AuditService;
import com.workbench.condolence.repository.CondolenceRepository;
import com.workbench.platform.datascope.DataScopeResolver;
import com.workbench.platform.datascope.RoleBasedDataScopeResolver;
import com.workbench.platform.org.OrgQueryPort;
import com.workbench.platform.org.RoleQueryPort;
import com.workbench.platform.org.RoleRef;
import com.workbench.platform.org.UserQueryPort;
import com.workbench.platform.org.WriteOwnershipResolver;
import com.workbench.testsupport.SecurityContextFixture;
import com.workbench.workflow.application.BizCompletionRegistry;
import com.workbench.workflow.application.WorkflowService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 中台化回归基线（G1，condolence 模块）：慰问金列表的数据范围过滤。
 *
 * <p>阶段 1 的语义：列表按 COMPANY/PROJECT 数据范围过滤；写路径（create/approve/reject）不做
 * COMPANY 数据范围门禁，而是「申请人强制取当前登录用户 + 审批交给工作流 assigned 校验」。</p>
 *
 * <p>阶段 2（ADR-M008）补的是<b>单位隔离</b>这一层，而不是数据范围门禁：
 * 列表只见本单位（company_id），登录态缺所属单位一律空列表；按 id 取到的记录先过
 * {@code canAccessCompany}，跨单位「拒绝 + ACCESS_DENIED 审计」；新增数据落
 * WriteOwnershipResolver 解析出的单位。</p>
 *
 * <p>本测试用<b>真实</b> RoleBasedDataScopeResolver 与<b>真实</b> WriteOwnershipResolver，
 * 只 mock 端口与仓储，保证语义被端到端锁定。</p>
 */
class CondolenceServiceDataScopeTest {

  private static final long COMPANY_ID = 4L;
  private static final long PROJECT_ID = 7L;

  private final CondolenceRepository condolenceRepository = mock(CondolenceRepository.class);
  private final WorkflowService workflowService = mock(WorkflowService.class);
  private final BizCompletionRegistry completionRegistry = mock(BizCompletionRegistry.class);
  private final AuditService auditService = mock(AuditService.class);
  private final UserQueryPort userQueryPort = mock(UserQueryPort.class);
  private final OrgQueryPort orgQueryPort = mock(OrgQueryPort.class);
  private final RoleQueryPort roleQueryPort = mock(RoleQueryPort.class);
  private final DataScopeResolver dataScopeResolver = new RoleBasedDataScopeResolver(roleQueryPort);
  private final WriteOwnershipResolver writeOwnershipResolver =
      new WriteOwnershipResolver(orgQueryPort, dataScopeResolver, auditService);

  private final CondolenceService service = new CondolenceService(condolenceRepository,
      workflowService, completionRegistry, auditService, userQueryPort, orgQueryPort,
      dataScopeResolver, writeOwnershipResolver);

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
    when(condolenceRepository.findByCompanyIdOrderByCreatedAtDesc(COMPANY_ID))
        .thenReturn(List.of());

    assertTrue(service.list().isEmpty());
    verify(condolenceRepository).findByCompanyIdOrderByCreatedAtDesc(COMPANY_ID);
    verify(condolenceRepository, never()).findByProjectIdOrderByCreatedAtDesc(any());
  }

  @Test
  void companyScopeWithoutCompanyIdSeesNothing() {
    SecurityContextFixture.runAs("gm", null, "GM");
    stubRoles(role("GM", "COMPANY", true));

    assertTrue(service.list().isEmpty(), "COMPANY 档缺登录态所属单位必须看到空列表，不得放行全库");
    verifyNoInteractions(condolenceRepository);
  }

  @Test
  void projectScopeReadsOwnProjectOnly() {
    SecurityContextFixture.runAs("pm", PROJECT_ID, "PROJECT_MANAGER");
    stubRoles(role("PROJECT_MANAGER", "PROJECT", true));
    when(condolenceRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(List.of());

    assertTrue(service.list().isEmpty());
    verify(condolenceRepository).findByProjectIdOrderByCreatedAtDesc(PROJECT_ID);
    verify(condolenceRepository, never()).findByCompanyIdOrderByCreatedAtDesc(any());
  }

  @Test
  void projectScopeWithoutProjectIdSeesNothing() {
    SecurityContextFixture.runAs("pm", null, "PROJECT_MANAGER");
    stubRoles(role("PROJECT_MANAGER", "PROJECT", true));

    assertTrue(service.list().isEmpty(), "无项目部归属的 PROJECT 用户必须看到空列表（严格模式）");
    verifyNoInteractions(condolenceRepository);
  }

  @Test
  void disabledCompanyRoleDoesNotGrantCompanyScope() {
    SecurityContextFixture.runAs("pm", PROJECT_ID, "GM");
    stubRoles(role("GM", "COMPANY", false));
    when(condolenceRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(List.of());

    service.list();
    verify(condolenceRepository).findByProjectIdOrderByCreatedAtDesc(PROJECT_ID);
    verify(condolenceRepository, never()).findByCompanyIdOrderByCreatedAtDesc(any());
  }
}
