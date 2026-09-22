package com.workbench.workflow.application;

import com.workbench.workflow.domain.ProcessDefinition;
import com.workbench.workflow.domain.ProcessInstance;
import com.workbench.workflow.domain.ProcessNode;
import com.workbench.workflow.domain.Task;
import com.workbench.workflow.repository.ProcessDefinitionRepository;
import com.workbench.workflow.repository.ProcessInstanceRepository;
import com.workbench.workflow.repository.ProcessNodeRepository;
import com.workbench.workflow.repository.TaskRepository;
import java.time.LocalDateTime;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 流程演示数据：流程定义表为空时写入，重复启动幂等。
 * 三条流程对应已做完的前端业务页面：请假、工资发放、公务接待（A/B/C 条件路由）。
 */
@Component
public class WorkflowDataSeeder implements CommandLineRunner {

  private final ProcessDefinitionRepository definitionRepository;
  private final ProcessNodeRepository nodeRepository;
  private final ProcessInstanceRepository instanceRepository;
  private final TaskRepository taskRepository;

  public WorkflowDataSeeder(ProcessDefinitionRepository definitionRepository,
      ProcessNodeRepository nodeRepository,
      ProcessInstanceRepository instanceRepository,
      TaskRepository taskRepository) {
    this.definitionRepository = definitionRepository;
    this.nodeRepository = nodeRepository;
    this.instanceRepository = instanceRepository;
    this.taskRepository = taskRepository;
  }

  @Override
  @Transactional
  public void run(String... args) {
    if (definitionRepository.count() > 0) {
      return;
    }

    ProcessDefinition leave = definition("leave", "请假审批流程", "leave");
    node(leave, 1, "APPROVAL", "项目部负责人审批", "PROJECT_LEADER", "PROJECT_MANAGER", "SINGLE", null);
    node(leave, 2, "END", "结束", null, null, null, null);

    ProcessDefinition payroll = definition("payroll", "工资发放审批流程", "payroll");
    node(payroll, 1, "APPROVAL", "人资负责人审核", "ROLE", "HR_ADMIN", "SINGLE", null);
    node(payroll, 2, "APPROVAL", "财务负责人审核", "ROLE", "FINANCE", "SINGLE", null);
    node(payroll, 3, "APPROVAL", "主要负责人审批", "PROJECT_LEADER", "PROJECT_MANAGER", "SINGLE", null);
    node(payroll, 4, "END", "结束", null, null, null, null);

    ProcessDefinition reception = definition("reception", "公务接待审批流程", "reception");
    node(reception, 1, "APPROVAL", "A类接待-办公室主任审批", "USER", "chenguodong", "SINGLE",
        "level == 'A'");
    node(reception, 2, "APPROVAL", "B类接待-办公室副主任审批", "USER", "limingyuan", "SINGLE",
        "level == 'B'");
    node(reception, 3, "APPROVAL", "C类接待-承办部门负责人审批", "USER", "wanghaitao", "SINGLE",
        "level == 'C'");
    node(reception, 4, "END", "结束", null, null, null, null);

    seedInstances(leave, payroll, reception);
  }

  private void seedInstances(ProcessDefinition leave, ProcessDefinition payroll,
      ProcessDefinition reception) {
    LocalDateTime now = LocalDateTime.now();
    Long tianhe = 2L;

    // 1. 请假：赵启明发起，待项目部负责人（陈国栋/李明远）审批
    ProcessInstance leaveInstance = instance(leave, "赵启明-事假3天", "zhaoqiming", tianhe, "{}", 1,
        now.minusHours(2));
    task(leaveInstance, 1, "项目部负责人审批", "chenguodong", "PENDING", null, now.minusHours(2), null);
    task(leaveInstance, 1, "项目部负责人审批", "limingyuan", "PENDING", null, now.minusHours(2), null);

    // 2. 工资发放：方静发起，已过人资/财务审核，待主要负责人审批
    ProcessInstance payrollInstance = instance(payroll, "2026年8月工资发放审批", "fangjing", tianhe,
        "{}", 3, now.minusDays(1));
    task(payrollInstance, 1, "人资负责人审核", "fangjing", "APPROVED", "花名册与考勤已核对",
        now.minusDays(1), now.minusDays(1).minusHours(1));
    task(payrollInstance, 2, "财务负责人审核", "fangjing", "APPROVED", "薪酬数据已复核",
        now.minusDays(1).minusHours(1), now.minusHours(20));
    task(payrollInstance, 3, "主要负责人审批", "chenguodong", "PENDING", null, now.minusHours(20), null);
    task(payrollInstance, 3, "主要负责人审批", "limingyuan", "PENDING", null, now.minusHours(20), null);

    // 3. 公务接待 A 类：王海涛发起，条件路由到办公室主任
    ProcessInstance receptionInstance = instance(reception, "A类接待-上级检查组一行3人",
        "wanghaitao", tianhe, "{\"level\":\"A\"}", 1, now.minusHours(5));
    task(receptionInstance, 1, "A类接待-办公室主任审批", "chenguodong", "PENDING", null,
        now.minusHours(5), null);
  }

  private ProcessDefinition definition(String code, String name, String bizType) {
    return definitionRepository.save(ProcessDefinition.builder()
        .code(code).name(name).bizType(bizType).version(1).enabled(true).build());
  }

  private ProcessNode node(ProcessDefinition definition, int seq, String nodeType, String name,
      String assigneeType, String assigneeExpr, String multiType, String conditionExpr) {
    return nodeRepository.save(ProcessNode.builder()
        .definitionId(definition.getId()).seq(seq).nodeType(nodeType).name(name)
        .assigneeType(assigneeType).assigneeExpr(assigneeExpr).multiType(multiType)
        .conditionExpr(conditionExpr).build());
  }

  private ProcessInstance instance(ProcessDefinition definition, String title, String initiator,
      Long projectId, String variables, int currentNodeSeq, LocalDateTime createdAt) {
    return instanceRepository.save(ProcessInstance.builder()
        .definitionId(definition.getId()).bizType(definition.getBizType()).title(title)
        .initiator(initiator).projectId(projectId).status("RUNNING").variables(variables)
        .currentNodeSeq(currentNodeSeq).createdAt(createdAt).build());
  }

  private Task task(ProcessInstance instance, int nodeSeq, String nodeName, String assignee,
      String status, String comment, LocalDateTime createdAt, LocalDateTime finishedAt) {
    return taskRepository.save(Task.builder()
        .instanceId(instance.getId()).nodeSeq(nodeSeq).nodeName(nodeName).assignee(assignee)
        .status(status).comment(comment).createdAt(createdAt).finishedAt(finishedAt).build());
  }
}
