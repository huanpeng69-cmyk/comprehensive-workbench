package com.workbench.workflow.application;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.workbench.platform.org.OrgQueryPort;
import com.workbench.platform.org.OrgUnitRef;
import com.workbench.workflow.domain.ProcessDefinition;
import com.workbench.workflow.domain.ProcessInstance;
import com.workbench.workflow.domain.ProcessNode;
import com.workbench.workflow.repository.ProcessDefinitionRepository;
import com.workbench.workflow.repository.ProcessInstanceRepository;
import com.workbench.workflow.repository.ProcessNodeRepository;
import com.workbench.workflow.repository.TaskRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 中台化阶段 2（ADR-M008）回归：工作流演示种子的门控与单位归属。
 *
 * <p>本测试锁住一条实测缺陷：门控原先用 {@code process_definition} 全表 count，而 V8 迁移已种
 * condolence 定义 ⇒ 新装库 count() 恒 > 0，本类被整段跳过（实测新装库 process_instance = 0）。
 * 现改为按本类自己的三条定义 code 判断。</p>
 *
 * <p>只 mock 仓储与端口：跳过分支必须零写入；新库（无单位）必须零写入；新库（有单位）必须写出
 * 3 定义 + 10 节点 + 3 实例 + 7 任务，且实例 {@code company_id} = 首家单位 id（否则 NOT NULL 拒绝）。</p>
 */
class WorkflowDataSeederTest {

  private static final long COMPANY_ID = 1L;

  private final ProcessDefinitionRepository definitionRepository =
      mock(ProcessDefinitionRepository.class);
  private final ProcessNodeRepository nodeRepository = mock(ProcessNodeRepository.class);
  private final ProcessInstanceRepository instanceRepository = mock(ProcessInstanceRepository.class);
  private final TaskRepository taskRepository = mock(TaskRepository.class);
  private final OrgQueryPort orgQueryPort = mock(OrgQueryPort.class);

  private final WorkflowDataSeeder seeder = new WorkflowDataSeeder(definitionRepository,
      nodeRepository, instanceRepository, taskRepository, orgQueryPort);

  private long definitionId;
  private long nodeId;
  private long instanceId;

  private void stubSaveAssignsIds() {
    when(definitionRepository.save(any())).thenAnswer(inv -> {
      ProcessDefinition saved = inv.getArgument(0);
      saved.setId(++definitionId);
      return saved;
    });
    when(nodeRepository.save(any())).thenAnswer(inv -> {
      ProcessNode saved = inv.getArgument(0);
      saved.setId(++nodeId);
      return saved;
    });
    when(instanceRepository.save(any())).thenAnswer(inv -> {
      ProcessInstance saved = inv.getArgument(0);
      saved.setId(++instanceId);
      return saved;
    });
    when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void run_skipsWhenOneDemoDefinitionAlreadyExists() {
    // V8 已种 condolence 定义，但本类三条演示定义一条都不存在 ⇒ 不能被“表里有记录”带偏。
    when(definitionRepository.existsByCode("leave")).thenReturn(true);

    seeder.run();

    verify(definitionRepository, never()).save(any());
    verifyNoInteractions(nodeRepository, instanceRepository, taskRepository, orgQueryPort);
  }

  @Test
  void run_skipsWhenNoCompanyUnitExists() {
    when(orgQueryPort.findFirstCompanyUnit()).thenReturn(Optional.empty());

    seeder.run();

    verify(definitionRepository, never()).save(any());
    verifyNoInteractions(nodeRepository, instanceRepository, taskRepository);
  }

  @Test
  void run_onFreshDatabase_seedsDemoFlowAnchoredToFirstCompany() {
    stubSaveAssignsIds();
    when(orgQueryPort.findFirstCompanyUnit())
        .thenReturn(Optional.of(new OrgUnitRef(COMPANY_ID, "中国水利水电第六工程局有限公司南方公司",
            COMPANY_ID)));

    seeder.run();

    verify(definitionRepository, times(3)).save(any());
    verify(nodeRepository, times(10)).save(any());

    ArgumentCaptor<ProcessNode> nodes = ArgumentCaptor.forClass(ProcessNode.class);
    verify(nodeRepository, times(10)).save(nodes.capture());
    List<String> roleExprs = nodes.getAllValues().stream()
        .filter(node -> "ROLE".equals(node.getAssigneeType()))
        .map(ProcessNode::getAssigneeExpr)
        .toList();
    assertTrue(roleExprs.containsAll(List.of("OFFICE_DIRECTOR", "OFFICE_DEPUTY", "DEPT_LEADER")),
        "公务接待 A/B/C 必须按角色码指派：用户名不再全局唯一，按用户名指派会在其他单位悬空");

    ArgumentCaptor<ProcessInstance> instances = ArgumentCaptor.forClass(ProcessInstance.class);
    verify(instanceRepository, times(3)).save(instances.capture());
    assertTrue(instances.getAllValues().stream()
            .allMatch(instance -> Long.valueOf(COMPANY_ID).equals(instance.getCompanyId())),
        "演示流程实例必须锚定首家单位，否则 company_id NOT NULL 拒绝启动期写入");

    verify(taskRepository, times(7)).save(any());
  }
}
