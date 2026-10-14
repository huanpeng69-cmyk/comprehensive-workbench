package com.workbench.workflow.application;

import com.workbench.platform.org.OrgQueryPort;
import com.workbench.platform.org.OrgUnitRef;
import com.workbench.workflow.domain.ProcessDefinition;
import com.workbench.workflow.domain.ProcessInstance;
import com.workbench.workflow.domain.ProcessNode;
import com.workbench.workflow.domain.Task;
import com.workbench.workflow.repository.ProcessDefinitionRepository;
import com.workbench.workflow.repository.ProcessInstanceRepository;
import com.workbench.workflow.repository.ProcessNodeRepository;
import com.workbench.workflow.repository.TaskRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 流程演示数据：三条演示流程定义（leave/payroll/reception）未写入时创建，重复启动幂等。
 * 门控必须按 code 精确判断，不能用 process_definition 全表 count：V8 迁移已种 condolence 定义，
 * 新库启动时 count() 恒 > 0，用 count() 会把本类整段跳过（实测缺陷：新装库 process_instance = 0）。
 * 三条流程对应已做完的前端业务页面：请假、工资发放、公务接待（A/B/C 条件路由）。
 *
 * <p>阶段 2（ADR-M008）：流程实例按归属单位隔离，实例行必须带 company_id（角色审批人按实例单位解析，
 * 见 {@code WorkflowService#resolveAssignees}）。启动期没有登录态，锚定「第一家单位」（口径与
 * {@code V10__multi_company.sql} 第 3 节回填一致：{@code type='COMPANY'} 取最小 id，禁止按公司名反查）；
 * 库里没有任何单位时跳过整段。定义与节点表无单位维度（V10 未加 company_id），本类不改。</p>
 */
@Component
public class WorkflowDataSeeder implements CommandLineRunner {

  private final ProcessDefinitionRepository definitionRepository;
  private final ProcessNodeRepository nodeRepository;
  private final ProcessInstanceRepository instanceRepository;
  private final TaskRepository taskRepository;
  private final OrgQueryPort orgQueryPort;

  public WorkflowDataSeeder(ProcessDefinitionRepository definitionRepository,
      ProcessNodeRepository nodeRepository,
      ProcessInstanceRepository instanceRepository,
      TaskRepository taskRepository,
      OrgQueryPort orgQueryPort) {
    this.definitionRepository = definitionRepository;
    this.nodeRepository = nodeRepository;
    this.instanceRepository = instanceRepository;
    this.taskRepository = taskRepository;
    this.orgQueryPort = orgQueryPort;
  }

  @Override
  @Transactional
  public void run(String... args) {
    // 只认本类自己种的三条定义：任一条已存在即整体跳过（保守幂等，同时避免 code 唯一约束冲突）。
    if (definitionRepository.existsByCode("leave") || definitionRepository.existsByCode("payroll")
        || definitionRepository.existsByCode("reception")) {
      return;
    }
    Optional<OrgUnitRef> company = orgQueryPort.findFirstCompanyUnit();
    if (company.isEmpty()) {
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
    // 阶段 2（ADR-M008）行为变更：A/B/C 三处原按用户名指派（chenguodong/limingyuan/wanghaitao）。
    // 用户名不再全局唯一后，「按用户名指派」会在没有同名成员的被指派公司悬空（任务无人可审、流程卡死）；
    // 改为按角色码指派，由 WorkflowService 按「实例所属单位」解析审批人（角色码见 V10 第 6 节）。
    node(reception, 1, "APPROVAL", "A类接待-办公室主任审批", "ROLE", "OFFICE_DIRECTOR", "SINGLE",
        "level == 'A'");
    node(reception, 2, "APPROVAL", "B类接待-办公室副主任审批", "ROLE", "OFFICE_DEPUTY", "SINGLE",
        "level == 'B'");
    node(reception, 3, "APPROVAL", "C类接待-承办部门负责人审批", "ROLE", "DEPT_LEADER", "SINGLE",
        "level == 'C'");
    node(reception, 4, "END", "结束", null, null, null, null);

    seedInstances(leave, payroll, reception, company.get().id());
  }

  private void seedInstances(ProcessDefinition leave, ProcessDefinition payroll,
      ProcessDefinition reception, Long companyId) {
    LocalDateTime now = LocalDateTime.now();
    Long tianhe = 2L;

    // 1. 请假：赵启明发起，待项目部负责人（陈国栋/李明远）审批
    ProcessInstance leaveInstance = instance(leave, "赵启明-事假3天", "zhaoqiming", tianhe, "{}", 1,
        now.minusHours(2), companyId);
    task(leaveInstance, 1, "项目部负责人审批", "chenguodong", "PENDING", null, now.minusHours(2), null);
    task(leaveInstance, 1, "项目部负责人审批", "limingyuan", "PENDING", null, now.minusHours(2), null);

    // 2. 工资发放：方静发起，已过人资/财务审核，待主要负责人审批
    ProcessInstance payrollInstance = instance(payroll, "2026年8月工资发放审批", "fangjing", tianhe,
        "{}", 3, now.minusDays(1), companyId);
    task(payrollInstance, 1, "人资负责人审核", "fangjing", "APPROVED", "花名册与考勤已核对",
        now.minusDays(1), now.minusDays(1).minusHours(1));
    task(payrollInstance, 2, "财务负责人审核", "fangjing", "APPROVED", "薪酬数据已复核",
        now.minusDays(1).minusHours(1), now.minusHours(20));
    task(payrollInstance, 3, "主要负责人审批", "chenguodong", "PENDING", null, now.minusHours(20), null);
    task(payrollInstance, 3, "主要负责人审批", "limingyuan", "PENDING", null, now.minusHours(20), null);

    // 3. 公务接待 A 类：王海涛发起，条件路由到办公室主任
    ProcessInstance receptionInstance = instance(reception, "A类接待-上级检查组一行3人",
        "wanghaitao", tianhe, "{\"level\":\"A\"}", 1, now.minusHours(5), companyId);
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
      Long projectId, String variables, int currentNodeSeq, LocalDateTime createdAt, Long companyId) {
    return instanceRepository.save(ProcessInstance.builder()
        .definitionId(definition.getId()).bizType(definition.getBizType()).title(title)
        .initiator(initiator).projectId(projectId).status("RUNNING").variables(variables)
        .currentNodeSeq(currentNodeSeq).createdAt(createdAt).companyId(companyId).build());
  }

  private Task task(ProcessInstance instance, int nodeSeq, String nodeName, String assignee,
      String status, String comment, LocalDateTime createdAt, LocalDateTime finishedAt) {
    return taskRepository.save(Task.builder()
        .instanceId(instance.getId()).nodeSeq(nodeSeq).nodeName(nodeName).assignee(assignee)
        .status(status).comment(comment).createdAt(createdAt).finishedAt(finishedAt).build());
  }
}
