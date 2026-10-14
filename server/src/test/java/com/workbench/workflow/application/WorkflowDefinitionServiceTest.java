package com.workbench.workflow.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.workbench.audit.application.AuditService;
import com.workbench.security.CurrentUserHolder;
import com.workbench.testsupport.SecurityContextFixture;
import com.workbench.workflow.application.dto.DefinitionSnapshotVo;
import com.workbench.workflow.application.dto.NodeInputVo;
import com.workbench.workflow.domain.ProcessDefinition;
import com.workbench.workflow.domain.ProcessDefinitionVersion;
import com.workbench.workflow.domain.ProcessNode;
import com.workbench.workflow.repository.ProcessDefinitionRepository;
import com.workbench.workflow.repository.ProcessDefinitionVersionRepository;
import com.workbench.workflow.repository.ProcessNodeRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 阶段 3 任务 4（ADR-M010）单元回归：流程定义版本化 / 回滚 / 校验 / 审计。
 *
 * <p>纯 Mockito 单测：不启动 Spring，直接驱动 {@link WorkflowDefinitionService} 的公开方法，
 * 间接覆盖 private 的版本存档（{@code saveVersion}）与节点整体替换（{@code replaceNodes}）。</p>
 *
 * <p>锁住的核心不变量：</p>
 * <ol>
 *   <li>版本号只增不减——回滚要产生新版本而不是覆盖历史；</li>
 *   <li>回滚 = 写回旧快照 + 新版本，历史一行不删；</li>
 *   <li>节点整体替换：先删后插，节点数变少不得留残行；</li>
 *   <li>重复 code / 空节点 / 回滚到当前版本必须被拒；</li>
 *   <li>每次写操作必须落审计（WF_DEF_CREATE / UPDATE / ROLLBACK）。</li>
 * </ol>
 */
class WorkflowDefinitionServiceTest {

  private final ProcessDefinitionRepository definitionRepository =
      org.mockito.Mockito.mock(ProcessDefinitionRepository.class);
  private final ProcessNodeRepository nodeRepository =
      org.mockito.Mockito.mock(ProcessNodeRepository.class);
  private final ProcessDefinitionVersionRepository versionRepository =
      org.mockito.Mockito.mock(ProcessDefinitionVersionRepository.class);
  private final AuditService auditService = org.mockito.Mockito.mock(AuditService.class);

  private final WorkflowDefinitionService service = new WorkflowDefinitionService(
      definitionRepository, nodeRepository, versionRepository, auditService);

  @BeforeEach
  void setUp() {
    SecurityContextFixture.runAsInCompany("admin", 1L, 1L, "SYSTEM_ADMIN");
    // 版本存档默认返回“当前最大版本”，调用方据此 +1。
    when(versionRepository.findFirstByDefinitionIdOrderByVersionDesc(anyLong()))
        .thenReturn(null);
    when(versionRepository.save(any(ProcessDefinitionVersion.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(nodeRepository.save(any(ProcessNode.class))).thenAnswer(inv -> {
      ProcessNode saved = inv.getArgument(0);
      saved.setId(System.nanoTime());
      return saved;
    });
    when(definitionRepository.save(any(ProcessDefinition.class))).thenAnswer(inv -> {
      ProcessDefinition saved = inv.getArgument(0);
      if (saved.getId() == null) {
        saved.setId(System.nanoTime());
      }
      return saved;
    });
    when(nodeRepository.findByDefinitionIdOrderBySeq(anyLong()))
        .thenReturn(List.of());
    when(definitionRepository.findAllByOrderByIdAsc())
        .thenReturn(List.of());
  }

  @AfterEach
  void tearDown() {
    SecurityContextFixture.clear();
    CurrentUserHolder.currentUsername();
  }

  private NodeInputVo node(int seq, String type, String name) {
    return new NodeInputVo(seq, type, name, "ROLE", "OFFICE_DIRECTOR", "SINGLE", null, "desc");
  }

  private DefinitionSnapshotVo snapshot(String code, String name, List<NodeInputVo> nodes) {
    return new DefinitionSnapshotVo(code, name, "TEST", true, "desc", "init", nodes);
  }

  @Test
  void create_storesFirstVersion_v1() {
    when(definitionRepository.existsByCode("lv1")).thenReturn(false);

    service.create(snapshot("lv1", "流程一", List.of(node(1, "APPROVAL", "审批"), node(2, "END", "结束"))));

    ArgumentCaptor<ProcessDefinitionVersion> cap =
        ArgumentCaptor.forClass(ProcessDefinitionVersion.class);
    verify(versionRepository).save(cap.capture());
    assertEquals(1, cap.getValue().getVersion(), "新建定义第一个版本必须是 v1");
    assertEquals(ProcessDefinitionVersion.CHANGE_INIT, cap.getValue().getChangeType());
    // 快照必须包含定义与全部节点，否则回滚无可写回。
    String json = cap.getValue().getSnapshot();
    org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"code\"")
        && json.contains("\"nodes\""), "快照必须含 definition 与 nodes");
    verify(auditService).log(eq("admin"), eq("WF_DEF_CREATE"), anyString(), anyString(), anyString());
  }

  @Test
  void update_afterCreate_incrementsVersionTo_v2() {
    ProcessDefinition def = ProcessDefinition.builder()
        .id(11L).code("lv2").name("流程二").bizType("TEST").version(1).enabled(true).build();
    when(definitionRepository.findById(11L)).thenReturn(Optional.of(def));
    when(definitionRepository.existsByCode("lv2")).thenReturn(false);
    // 已有一个版本，update 必须产生 v2。
    when(versionRepository.findFirstByDefinitionIdOrderByVersionDesc(11L)).thenReturn(1);

    service.update(11L, snapshot("lv2", "流程二改",
        List.of(node(1, "APPROVAL", "审批"), node(2, "END", "结束"))));

    ArgumentCaptor<ProcessDefinitionVersion> cap =
        ArgumentCaptor.forClass(ProcessDefinitionVersion.class);
    verify(versionRepository).save(cap.capture());
    assertEquals(2, cap.getValue().getVersion(), "修改后必须产生 v2");
    assertEquals(ProcessDefinitionVersion.CHANGE_UPDATE, cap.getValue().getChangeType());
    verify(auditService).log(eq("admin"), eq("WF_DEF_UPDATE"), anyString(), anyString(), anyString());
  }

  @Test
  void rollback_writesBackOldSnapshotAndProducesNewVersion_keepingHistory() {
    ProcessDefinition def = ProcessDefinition.builder()
        .id(21L).code("lv3").name("当前名").bizType("TEST").version(3).enabled(true).build();
    when(definitionRepository.findById(21L)).thenReturn(Optional.of(def));
    long before = versionRepositoryInvocationCount();
    // 当前最新是 v2（set 调 saveVersion 前 findFirst 返回 2）。
    when(versionRepository.findFirstByDefinitionIdOrderByVersionDesc(21L)).thenReturn(2);
    ProcessDefinitionVersion v1 = ProcessDefinitionVersion.builder()
        .definitionId(21L).version(1).changeType(ProcessDefinitionVersion.CHANGE_INIT)
        .snapshot("{\"definition\":{\"code\":\"lv3\",\"name\":\"旧名\",\"bizType\":\"TEST\","
            + "\"enabled\":true,\"description\":null},"
            + "\"nodes\":[{\"seq\":1,\"nodeType\":\"APPROVAL\",\"name\":\"审批\","
            + "\"assigneeType\":\"ROLE\",\"assigneeExpr\":\"OFFICE_DIRECTOR\","
            + "\"multiType\":\"SINGLE\",\"conditionExpr\":null,\"description\":\"desc\"}]}")
        .createdBy("admin").build();
    when(versionRepository.findByDefinitionIdAndVersion(21L, 1)).thenReturn(Optional.of(v1));

    service.rollback(21L, 1, "退回初版");

    // 节点被整体替换：先删后插。
    verify(nodeRepository).deleteByDefinitionId(21L);
    verify(nodeRepository, times(1)).save(any(ProcessNode.class));

    ArgumentCaptor<ProcessDefinitionVersion> cap =
        ArgumentCaptor.forClass(ProcessDefinitionVersion.class);
    verify(versionRepository, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
    ProcessDefinitionVersion rolled = cap.getAllValues().get(cap.getAllValues().size() - 1);
    assertEquals(3, rolled.getVersion(), "回滚产生 v3，版本号只增不减");
    assertEquals(ProcessDefinitionVersion.CHANGE_ROLLBACK, rolled.getChangeType());
    // 定义被写回旧快照内容。
    assertEquals("旧名", def.getName(), "回滚必须把定义写回旧快照");
    verify(auditService).log(eq("admin"), eq("WF_DEF_ROLLBACK"), anyString(), anyString(), anyString());
  }

  @Test
  void rollback_toCurrentVersion_isRejected() {
    ProcessDefinition def = ProcessDefinition.builder()
        .id(31L).code("lv4").name("流程四").bizType("TEST").version(2).enabled(true).build();
    when(definitionRepository.findById(31L)).thenReturn(Optional.of(def));
    when(versionRepository.findFirstByDefinitionIdOrderByVersionDesc(31L)).thenReturn(2);
    when(versionRepository.findByDefinitionIdAndVersion(31L, 2))
        .thenReturn(Optional.of(ProcessDefinitionVersion.builder()
            .definitionId(31L).version(2).changeType(ProcessDefinitionVersion.CHANGE_UPDATE)
            .snapshot("{}").createdBy("admin").build()));

    assertThrows(IllegalArgumentException.class, () -> service.rollback(31L, 2, "没意义"));
    verify(versionRepository, never()).save(any(ProcessDefinitionVersion.class));
  }

  @Test
  void create_duplicateCode_isRejected() {
    when(definitionRepository.existsByCode("dup")).thenReturn(true);

    assertThrows(IllegalArgumentException.class,
        () -> service.create(snapshot("dup", "重复", List.of(node(1, "END", "结束")))));
    verify(definitionRepository, never()).save(any(ProcessDefinition.class));
  }

  @Test
  void create_withoutNodes_isRejected() {
    when(definitionRepository.existsByCode("empty")).thenReturn(false);

    assertThrows(IllegalArgumentException.class,
        () -> service.create(snapshot("empty", "空节点", List.of())));
    verify(nodeRepository, never()).save(any(ProcessNode.class));
  }

  @Test
  void update_reducesNodeCount_leavesNoResidualNodes() {
    ProcessDefinition def = ProcessDefinition.builder()
        .id(41L).code("lv5").name("流程五").bizType("TEST").version(1).enabled(true).build();
    when(definitionRepository.findById(41L)).thenReturn(Optional.of(def));
    when(definitionRepository.existsByCode("lv5")).thenReturn(false);

    // 从 3 个节点改成 2 个，必须删掉多余的那一个而不是留残行。
    service.update(41L, snapshot("lv5", "流程五",
        List.of(node(1, "APPROVAL", "审批"), node(2, "END", "结束"))));

    verify(nodeRepository).deleteByDefinitionId(41L);
    verify(nodeRepository, times(2)).save(any(ProcessNode.class));
  }

  private long versionRepositoryInvocationCount() {
    return 0;
  }
}
