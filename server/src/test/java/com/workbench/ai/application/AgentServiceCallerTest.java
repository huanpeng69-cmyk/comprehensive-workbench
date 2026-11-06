package com.workbench.ai.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.workbench.ai.application.tool.ToolCaller;
import com.workbench.ai.application.tool.ToolRegistry;
import com.workbench.audit.application.AuditService;
import com.workbench.memory.application.MemoryService;
import com.workbench.platform.datascope.DataScopeResolver;
import com.workbench.testsupport.SecurityContextFixture;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

/**
 * 调用者身份「在哪个线程取」的回归。
 *
 * <p>{@link AgentService#resolveCaller()} 必须在主线程执行：它读的是 SecurityContext（ThreadLocal），
 * 一旦挪到 Agent 线程里，取到的用户名 / 角色 / 项目上下文全是空的，工具授权就会静默失效。
 * 本测试同时钉住两点：身份各维度取自哪里（JWT claim + resolver），以及未注入 resolver 时的保守默认值。
 */
class AgentServiceCallerTest {

  private final AiConfigService configService = mock(AiConfigService.class);
  private final AuditService auditService = mock(AuditService.class);
  private final ToolRegistry toolRegistry = mock(ToolRegistry.class);
  private final MemoryService memoryService = mock(MemoryService.class);
  private final RestTemplate restTemplate = mock(RestTemplate.class);

  @AfterEach
  void tearDown() {
    SecurityContextFixture.clear();
  }

  @Test
  void callerCarriesUsernameRolesAndProjectFromJwt() {
    SecurityContextFixture.runAsInCompany("alice", 1L, 7L, "HR", "admin");
    AgentService service = serviceWithoutResolver();

    ToolCaller caller = service.resolveCaller();

    assertEquals("alice", caller.username());
    // 角色码去掉了 ROLE_ 前缀，与 AgentTool.requiredRoles() 的口径一致。
    assertEquals(Set.of("HR", "admin"), caller.roles());
    assertEquals(7L, caller.projectId());
    assertEquals(1L, caller.companyId());
    assertTrue(caller.hasProjectContext());
    service.destroy();
  }

  @Test
  void companyScopeComesFromResolver() {
    // COMPANY 档角色的 token 里没有 projectId，靠 resolver 判出来的全公司范围补上上下文。
    SecurityContextFixture.runAsInCompany("alice", 1L, null, "HR");
    DataScopeResolver resolver = mock(DataScopeResolver.class);
    when(resolver.hasCompanyScope()).thenReturn(true);
    AgentService service = serviceWithResolver(resolver);

    ToolCaller caller = service.resolveCaller();

    assertTrue(caller.companyScope());
    assertTrue(caller.hasProjectContext(), "全公司范围本身就是合法的项目上下文");
    service.destroy();
  }

  @Test
  void withoutResolverCompanyScopeIsConservativelyFalse() {
    // 未注入 resolver（测试构造器/系统调用场景）时不能乐观放行：宁可让工具被拒，也不越权查数据。
    SecurityContextFixture.runAsInCompany("alice", 1L, null, "HR");
    AgentService service = serviceWithoutResolver();

    ToolCaller caller = service.resolveCaller();

    assertFalse(caller.companyScope());
    assertFalse(caller.hasProjectContext(), "没有项目部、也没有全公司范围时必须判为无上下文");
    service.destroy();
  }

  @Test
  void unauthenticatedCallerHasNoIdentity() {
    SecurityContextFixture.clear();
    AgentService service = serviceWithoutResolver();

    ToolCaller caller = service.resolveCaller();

    assertEquals(null, caller.username());
    assertEquals(Set.of(), caller.roles());
    assertFalse(caller.hasProjectContext());
    service.destroy();
  }

  private AgentService serviceWithoutResolver() {
    return new AgentService(configService, auditService, toolRegistry, memoryService, restTemplate);
  }

  private AgentService serviceWithResolver(DataScopeResolver resolver) {
    return new AgentService(configService, auditService, toolRegistry, memoryService, restTemplate,
        resolver);
  }
}
