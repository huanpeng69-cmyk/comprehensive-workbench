package com.workbench.workflow.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workbench.audit.application.AuditService;
import com.workbench.platform.datascope.DataScopeResolver;
import com.workbench.platform.org.OrgQueryPort;
import com.workbench.platform.org.OrgUnitRef;
import com.workbench.platform.org.WriteOwnershipResolver;
import com.workbench.platform.org.UserQueryPort;
import com.workbench.security.CurrentUserHolder;
import com.workbench.workflow.application.dto.InstanceSummaryVo;
import com.workbench.workflow.application.dto.NodeCommentRecord;
import com.workbench.workflow.application.dto.ProcessDefinitionVo;
import com.workbench.workflow.application.dto.ProcessInstanceVo;
import com.workbench.workflow.application.dto.ProcessInstanceVo.NodeProgress;
import com.workbench.workflow.application.dto.ProcessInstanceVo.TaskHistory;
import com.workbench.workflow.application.dto.StartProcessRequest;
import com.workbench.workflow.application.dto.TaskActionRequest;
import com.workbench.workflow.application.dto.TaskVo;
import com.workbench.workflow.application.dto.TransmitRequest;
import com.workbench.workflow.domain.ProcessDefinition;
import com.workbench.workflow.domain.ProcessInstance;
import com.workbench.workflow.domain.ProcessNode;
import com.workbench.workflow.domain.Task;
import com.workbench.workflow.repository.ProcessDefinitionRepository;
import com.workbench.workflow.repository.ProcessInstanceRepository;
import com.workbench.workflow.repository.ProcessNodeRepository;
import com.workbench.workflow.repository.TaskRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 可配置流程引擎。
 *
 * <p>定义按节点 seq 顺序执行：START 隐含于发起动作。流转时取第一个条件命中的后续节点：
 * APPROVAL 生成待办任务后停下；CC 抄送自动完成后继续；END 完成实例。
 * 条件表达式为 SpEL（如 {@code level == 'A'}），变量来自发起时的 variables 且不可变，
 * 因此条件求值结果在实例生命周期内固定。</p>
 *
 * <p>审批人类型：USER 指定用户 / ROLE 角色 / PROJECT_LEADER 实例所属项目部经理 /
 * INITIATOR 发起人。multiType PARALLEL 为会签（全部通过才流转），SINGLE 为任一通过即流转。</p>
 *
 * <p>数据范围（阶段 2，ADR-M008）：实例按归属单位（company_id）隔离——待办、我发起的、
 * 实例详情与全部写操作只限本单位；越权访问先写 ACCESS_DENIED 审计再拒绝。</p>
 */
@Service
public class WorkflowService {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final ProcessDefinitionRepository definitionRepository;
  private final ProcessNodeRepository nodeRepository;
  private final ProcessInstanceRepository instanceRepository;
  private final TaskRepository taskRepository;
  private final UserQueryPort userQueryPort;
  private final OrgQueryPort orgQueryPort;
  private final AuditService auditService;
  private final BizCompletionRegistry completionRegistry;
  private final DataScopeResolver dataScopeResolver;
  private final WriteOwnershipResolver writeOwnershipResolver;
  private final ExpressionParser parser = new SpelExpressionParser();

  public WorkflowService(ProcessDefinitionRepository definitionRepository,
      ProcessNodeRepository nodeRepository,
      ProcessInstanceRepository instanceRepository,
      TaskRepository taskRepository,
      UserQueryPort userQueryPort,
      OrgQueryPort orgQueryPort,
      AuditService auditService,
      BizCompletionRegistry completionRegistry,
      DataScopeResolver dataScopeResolver,
      WriteOwnershipResolver writeOwnershipResolver) {
    this.definitionRepository = definitionRepository;
    this.nodeRepository = nodeRepository;
    this.instanceRepository = instanceRepository;
    this.taskRepository = taskRepository;
    this.userQueryPort = userQueryPort;
    this.orgQueryPort = orgQueryPort;
    this.auditService = auditService;
    this.completionRegistry = completionRegistry;
    this.dataScopeResolver = dataScopeResolver;
    this.writeOwnershipResolver = writeOwnershipResolver;
  }

  /** 发起流程。 */
  @Transactional
  public InstanceSummaryVo start(StartProcessRequest request) {
    ProcessDefinition definition = definitionRepository
        .findFirstByBizTypeAndEnabledTrueOrderByVersionDesc(request.bizType())
        .orElseThrow(() -> new IllegalArgumentException("未找到启用的流程定义: " + request.bizType()));
    Long projectId = CurrentUserHolder.currentProjectId();
    ProcessInstance instance = ProcessInstance.builder()
        .definitionId(definition.getId())
        .bizType(definition.getBizType())
        .bizId(request.bizId())
        .title(request.title())
        .initiator(CurrentUserHolder.currentUsername())
        .projectId(projectId)
        .companyId(writeOwnershipResolver.requireCompanyId(projectId, "PROCESS_INSTANCE", "发起流程"))
        .status("RUNNING")
        .variables(writeJson(request.variables()))
        .createdAt(LocalDateTime.now())
        .build();
    instance = instanceRepository.save(instance);
    auditService.log("START", "PROCESS_INSTANCE", String.valueOf(instance.getId()),
        "发起流程: " + request.title());
    advance(instance);
    return toSummary(instance);
  }

  /** 我的待办：名下未处理且实例进行中的任务，实例须归属本单位（用户名不再全局唯一，ADR-M006）。 */
  @Transactional(readOnly = true)
  public List<TaskVo> myTodos() {
    Long companyId = dataScopeResolver.currentCompanyId();
    if (companyId == null) {
      return List.of();
    }
    List<Task> tasks = taskRepository.findMyTodos(CurrentUserHolder.currentUsername(), companyId);
    if (tasks.isEmpty()) {
      return List.of();
    }
    Map<Long, ProcessInstance> instances = instanceRepository
        .findAllById(tasks.stream().map(Task::getInstanceId).distinct().toList())
        .stream().collect(Collectors.toMap(ProcessInstance::getId, i -> i));
    Map<String, String> names = nameMap(tasks.stream()
        .flatMap(t -> List.of(t.getAssignee(), instances.get(t.getInstanceId()).getInitiator())
            .stream())
        .distinct().toList());
    return tasks.stream()
        .map(t -> {
          ProcessInstance instance = instances.get(t.getInstanceId());
          return new TaskVo(t.getId(), t.getInstanceId(), instance.getTitle(), instance.getBizType(),
              instance.getInitiator(), names.get(instance.getInitiator()), t.getNodeName(),
              t.getAssignee(), names.get(t.getAssignee()), t.getStatus(), t.getCreatedAt());
        })
        .toList();
  }

  /** 我发起的流程（限本单位：用户名不再全局唯一，ADR-M006）。 */
  @Transactional(readOnly = true)
  public List<InstanceSummaryVo> myInstances() {
    Long companyId = dataScopeResolver.currentCompanyId();
    if (companyId == null) {
      return List.of();
    }
    return instanceRepository
        .findByCompanyIdAndInitiatorOrderByCreatedAtDesc(companyId,
            CurrentUserHolder.currentUsername())
        .stream()
        .map(this::toSummary)
        .toList();
  }

  /**
   * 实例各节点的审批意见（仅取 APPROVED 任务，按节点 seq 升序），供业务模块完结时回填真实审批结论。
   *
   * <p>必须过滤非 APPROVED 任务：SINGLE 节点中未轮到的一方任务会被置为 CANCELLED
   * （comment='其他审批人已处理'），若不过滤，forEach 回填时会用该占位 comment 覆盖真实意见。</p>
   */
  @Transactional(readOnly = true)
  public List<NodeCommentRecord> instanceTaskComments(Long instanceId) {
    return taskRepository.findByInstanceIdOrderByNodeSeqAscCreatedAtAsc(instanceId).stream()
        .filter(t -> "APPROVED".equals(t.getStatus()))
        .map(t -> new NodeCommentRecord(t.getNodeSeq(), t.getComment()))
        .toList();
  }

 /** 实例详情：含节点进度与审批记录。 */
  @Transactional(readOnly = true)
  public ProcessInstanceVo instanceDetail(Long instanceId) {
    ProcessInstance instance = instanceRepository.findById(instanceId)
        .orElseThrow(() -> new EntityNotFoundException("流程实例不存在"));
    assertCompanyAccess(instance);
    List<ProcessNode> nodes = nodeRepository.findByDefinitionIdOrderBySeq(instance.getDefinitionId());
    List<Task> tasks = taskRepository.findByInstanceIdOrderByNodeSeqAscCreatedAtAsc(instanceId);
    Map<Integer, List<Task>> tasksByNode = tasks.stream()
        .collect(Collectors.groupingBy(Task::getNodeSeq));
    Map<String, String> names = nameMap(tasks.stream().map(Task::getAssignee).distinct().toList());
    names.putAll(nameMap(List.of(instance.getInitiator())));

    List<NodeProgress> progress = nodes.stream()
        .map(node -> new NodeProgress(node.getSeq(), node.getName(),
            nodeState(node, instance, tasksByNode.getOrDefault(node.getSeq(), List.of()))))
        .toList();
    List<TaskHistory> history = tasks.stream()
        .map(t -> new TaskHistory(t.getId(), t.getNodeSeq(), t.getNodeName(), t.getAssignee(),
            names.get(t.getAssignee()), t.getStatus(), t.getComment(),
            t.getCreatedAt(), t.getFinishedAt()))
        .toList();
    String currentNodeName = nodes.stream()
        .filter(n -> Objects.equals(n.getSeq(), instance.getCurrentNodeSeq()))
        .map(ProcessNode::getName)
        .findFirst()
        .orElse(null);
    String projectName = instance.getProjectId() == null ? null
        : orgQueryPort.findById(instance.getProjectId()).map(OrgUnitRef::name).orElse(null);
    return new ProcessInstanceVo(instance.getId(), instance.getBizType(), instance.getTitle(),
        instance.getInitiator(), names.get(instance.getInitiator()), projectName, instance.getStatus(),
        instance.getCreatedAt(), instance.getFinishedAt(), currentNodeName, progress, history);
  }

  /** 已启用流程定义列表。 */
  @Transactional(readOnly = true)
  public List<ProcessDefinitionVo> listDefinitions() {
    return definitionRepository.findByEnabledTrueOrderByIdAsc().stream()
        .map(d -> new ProcessDefinitionVo(d.getId(), d.getCode(), d.getName(), d.getBizType(),
            d.getVersion()))
        .toList();
  }

  /** 通过任务。 */
  @Transactional
  public void approve(Long taskId, TaskActionRequest request) {
    Task task = loadTask(taskId);
    ensureAssignee(task);
    ProcessInstance instance = loadInstance(task.getInstanceId());
        task.setStatus("APPROVED");
    task.setComment(request.comment());
    task.setFinishedAt(LocalDateTime.now());
    taskRepository.save(task);
    auditService.log("APPROVE", "TASK", String.valueOf(task.getId()),
        "通过「" + task.getNodeName() + "」: " + instance.getTitle());

    List<Task> nodeTasks = taskRepository
        .findByInstanceIdAndNodeSeq(instance.getId(), task.getNodeSeq());
    ProcessNode node = findNode(instance, task.getNodeSeq());
    if ("PARALLEL".equals(node.getMultiType())) {
      // 会签：全部任务通过后才流转
      if (nodeTasks.stream().anyMatch(t -> "PENDING".equals(t.getStatus()))) {
        return;
      }
    } else {
      // 单人审批：其余待处理任务作废
      nodeTasks.stream()
          .filter(t -> "PENDING".equals(t.getStatus()))
          .forEach(t -> {
            t.setStatus("CANCELLED");
            t.setComment("其他审批人已处理");
            t.setFinishedAt(LocalDateTime.now());
            taskRepository.save(t);
          });
    }
    advance(instance);
  }

  /** 驳回任务：实例终止，业务侧可重新发起。 */
  @Transactional
  public void reject(Long taskId, TaskActionRequest request) {
    Task task = loadTask(taskId);
    ensureAssignee(task);
    ProcessInstance instance = loadInstance(task.getInstanceId());
        task.setStatus("REJECTED");
    task.setComment(request.comment());
    task.setFinishedAt(LocalDateTime.now());
    taskRepository.save(task);
    // 同节点其余待处理任务作废，避免残留在待办列表
    taskRepository.findByInstanceIdAndNodeSeq(instance.getId(), task.getNodeSeq()).stream()
        .filter(t -> "PENDING".equals(t.getStatus()))
        .forEach(t -> {
          t.setStatus("CANCELLED");
          t.setComment("流程已驳回");
          t.setFinishedAt(LocalDateTime.now());
          taskRepository.save(t);
        });
    instance.setStatus("REJECTED");
    instance.setFinishedAt(LocalDateTime.now());
    instanceRepository.save(instance);
    auditService.log("REJECT", "PROCESS_INSTANCE", String.valueOf(instance.getId()),
        "驳回「" + task.getNodeName() + "」: " + instance.getTitle());
    completionRegistry.complete(instance.getBizType(), instance.getBizId(), "REJECTED");
  }

  /**
   * 按实例直接通过：找到该实例首个待处理任务并审批。
   * 供业务模块内联审批调用（如请假页「批准」按钮）；审批人校验由 approve 内部 ensureAssignee 保证。
   */
  @Transactional
  public void approveByInstance(Long instanceId, TaskActionRequest request) {
    Task task = taskRepository
        .findFirstByInstanceIdAndStatusOrderByNodeSeqAscCreatedAtAsc(instanceId, "PENDING")
        .orElseThrow(() -> new IllegalStateException("该流程当前无待处理任务"));
    approve(task.getId(), request);
  }

  /** 按实例直接驳回：语义同 approveByInstance。 */
  @Transactional
  public void rejectByInstance(Long instanceId, TaskActionRequest request) {
    Task task = taskRepository
        .findFirstByInstanceIdAndStatusOrderByNodeSeqAscCreatedAtAsc(instanceId, "PENDING")
        .orElseThrow(() -> new IllegalStateException("该流程当前无待处理任务"));
    reject(task.getId(), request);
  }

  /** 转办：原任务留痕，新任务指派给目标用户。 */
  @Transactional
  public void transmit(Long taskId, TransmitRequest request) {
    Task task = loadTask(taskId);
    ensureAssignee(task);
    ProcessInstance instance = loadInstance(task.getInstanceId());
    if (!userQueryPort.existsByCompanyIdAndUsername(instance.getCompanyId(),
        request.toUsername())) {
      throw new IllegalArgumentException("目标用户不存在: " + request.toUsername());
    }
    task.setStatus("TRANSMITTED");
    task.setComment("转办给 " + request.toUsername()
        + (StringUtils.hasText(request.comment()) ? "：" + request.comment() : ""));
    task.setFinishedAt(LocalDateTime.now());
    taskRepository.save(task);
    Task forwarded = Task.builder()
        .instanceId(instance.getId())
        .nodeSeq(task.getNodeSeq())
        .nodeName(task.getNodeName())
        .assignee(request.toUsername())
        .status("PENDING")
        .comment("由 " + CurrentUserHolder.currentUsername() + " 转办")
        .createdAt(LocalDateTime.now())
        .build();
    taskRepository.save(forwarded);
    auditService.log("TRANSMIT", "TASK", String.valueOf(task.getId()),
        "转办「" + task.getNodeName() + "」给 " + request.toUsername());
  }

  /**
   * 从当前节点向后流转：取第一个条件命中的节点执行。
   * CC 节点自动完成后继续推进，APPROVAL 节点生成任务后返回，END 节点完成实例。
   */
  private void advance(ProcessInstance instance) {
    List<ProcessNode> nodes = nodeRepository.findByDefinitionIdOrderBySeq(instance.getDefinitionId());
    int fromSeq = instance.getCurrentNodeSeq() == null ? 0 : instance.getCurrentNodeSeq();
    for (ProcessNode node : nodes) {
      if (node.getSeq() <= fromSeq) {
        continue;
      }
      if (!matchesCondition(node, instance)) {
        continue;
      }
      switch (node.getNodeType()) {
        case "END" -> {
          instance.setStatus("COMPLETED");
          instance.setFinishedAt(LocalDateTime.now());
          instanceRepository.save(instance);
          auditService.log("COMPLETE", "PROCESS_INSTANCE", String.valueOf(instance.getId()),
              "流程完成: " + instance.getTitle());
          completionRegistry.complete(instance.getBizType(), instance.getBizId(), "COMPLETED");
          return;
        }
        case "CC" -> {
          List<String> assignees = resolveAssignees(node, instance);
          for (String assignee : assignees) {
            createTask(instance, node, assignee, "CC", null);
          }
          auditService.log("CC", "PROCESS_INSTANCE", String.valueOf(instance.getId()),
              "抄送「" + node.getName() + "」: " + String.join("、", assignees));
        }
        case "APPROVAL" -> {
          List<String> assignees = resolveAssignees(node, instance);
          if (assignees.isEmpty()) {
            throw new IllegalStateException("节点「" + node.getName() + "」未解析到审批人");
          }
          for (String assignee : assignees) {
            createTask(instance, node, assignee, "PENDING", null);
          }
          instance.setCurrentNodeSeq(node.getSeq());
          instanceRepository.save(instance);
          return;
        }
        default -> throw new IllegalStateException("未知节点类型: " + node.getNodeType());
      }
    }
    // 未配置 END 节点，直接完成
    instance.setStatus("COMPLETED");
    instance.setFinishedAt(LocalDateTime.now());
    instanceRepository.save(instance);
  }

  private Task createTask(ProcessInstance instance, ProcessNode node, String assignee,
      String status, String comment) {
    Task task = Task.builder()
        .instanceId(instance.getId())
        .nodeSeq(node.getSeq())
        .nodeName(node.getName())
        .assignee(assignee)
        .status(status)
        .comment(comment)
        .createdAt(LocalDateTime.now())
        .finishedAt("PENDING".equals(status) ? null : LocalDateTime.now())
        .build();
    return taskRepository.save(task);
  }

  /** SpEL 求条件，变量以 Map 为根对象，支持 {@code level == 'A'} 这类裸写法。 */
  private boolean matchesCondition(ProcessNode node, ProcessInstance instance) {
    if (!StringUtils.hasText(node.getConditionExpr())) {
      return true;
    }
    Map<String, Object> vars = readJson(instance.getVariables());
    StandardEvaluationContext context = new StandardEvaluationContext(vars);
    context.addPropertyAccessor(new MapAccessor());
    return Boolean.TRUE.equals(parser.parseExpression(node.getConditionExpr())
        .getValue(context, Boolean.class));
  }

  /**
   * 单位边界（阶段 2，ADR-M008）：只能查看/操作本单位的流程实例。越权先写 ACCESS_DENIED 审计
   * 再拒绝；实例 company_id 为空同样拒绝——宁可拒绝也不跨单位猜测。
   */
  private void assertCompanyAccess(ProcessInstance instance) {
    if (dataScopeResolver.canAccessCompany(instance.getCompanyId())) {
      return;
    }
    auditService.log(CurrentUserHolder.currentUsername(), "ACCESS_DENIED", "PROCESS_INSTANCE",
        String.valueOf(instance.getId()), "尝试越权访问非本单位的流程实例");
    throw new AccessDeniedException("无权访问其他单位的流程实例");
  }

  private List<String> resolveAssignees(ProcessNode node, ProcessInstance instance) {
    String expr = node.getAssigneeExpr();
    switch (node.getAssigneeType()) {
      case "USER":
        return StringUtils.hasText(expr)
            ? Arrays.stream(expr.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()
            : List.of();
      case "ROLE":
        return userQueryPort.findEnabledUsernamesByRoleCodeAndCompanyId(expr, instance.getCompanyId());
      case "PROJECT_LEADER":
        return userQueryPort.findEnabledUsernamesByRoleCodeAndProjectId(
            StringUtils.hasText(expr) ? expr : "PROJECT_MANAGER", instance.getProjectId());
      case "INITIATOR":
        return List.of(instance.getInitiator());
      default:
        throw new IllegalStateException("未知审批人类型: " + node.getAssigneeType());
    }
  }

  private String nodeState(ProcessNode node, ProcessInstance instance, List<Task> nodeTasks) {
    if ("END".equals(node.getNodeType())) {
      return "RUNNING".equals(instance.getStatus()) ? "PENDING" : "DONE";
    }
    boolean running = "RUNNING".equals(instance.getStatus());
    int currentSeq = instance.getCurrentNodeSeq() == null ? 0 : instance.getCurrentNodeSeq();
    if (running && node.getSeq() == currentSeq) {
      return "CURRENT";
    }
    if (!nodeTasks.isEmpty()) {
      return "DONE";
    }
    if (!running || node.getSeq() < currentSeq
        || (StringUtils.hasText(node.getConditionExpr()) && !matchesCondition(node, instance))) {
      return "SKIPPED";
    }
    return node.getSeq() == currentSeq ? "CURRENT" : "PENDING";
  }

  private ProcessNode findNode(ProcessInstance instance, Integer seq) {
    return nodeRepository.findByDefinitionIdOrderBySeq(instance.getDefinitionId()).stream()
        .filter(n -> Objects.equals(n.getSeq(), seq))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("节点不存在: seq=" + seq));
  }

  private InstanceSummaryVo toSummary(ProcessInstance instance) {
    String currentNodeName = null;
    if (instance.getCurrentNodeSeq() != null) {
      currentNodeName = nodeRepository
          .findByDefinitionIdOrderBySeq(instance.getDefinitionId()).stream()
          .filter(n -> Objects.equals(n.getSeq(), instance.getCurrentNodeSeq()))
          .map(ProcessNode::getName)
          .findFirst()
          .orElse(null);
    }
    return new InstanceSummaryVo(instance.getId(), instance.getBizType(), instance.getTitle(),
        instance.getInitiator(), instance.getStatus(), currentNodeName, instance.getCreatedAt(),
        instance.getFinishedAt());
  }

  private Map<String, String> nameMap(Collection<String> usernames) {
    if (usernames == null || usernames.isEmpty()) {
      return new HashMap<>();
    }
    // instanceDetail 会对本方法返回值做 putAll，必须返回可变 Map（不依赖适配器实现）
    return new HashMap<>(userQueryPort.findNamesByUsernames(usernames));
  }

  private Task loadTask(Long taskId) {
    return taskRepository.findById(taskId)
        .orElseThrow(() -> new EntityNotFoundException("任务不存在"));
  }

  private ProcessInstance loadInstance(Long instanceId) {
    ProcessInstance instance = instanceRepository.findById(instanceId)
        .orElseThrow(() -> new EntityNotFoundException("流程实例不存在"));
    assertCompanyAccess(instance);
    if (!"RUNNING".equals(instance.getStatus())) {
      throw new IllegalStateException("流程已结束，无法继续操作");
    }
    return instance;
  }

  private void ensureAssignee(Task task) {
    if (!Objects.equals(task.getAssignee(), CurrentUserHolder.currentUsername())) {
      throw new AccessDeniedException("只能处理本人名下的任务");
    }
  }

  private String writeJson(Map<String, Object> variables) {
    try {
      return variables == null ? null : JSON.writeValueAsString(variables);
    } catch (Exception e) {
      throw new IllegalArgumentException("流程变量序列化失败", e);
    }
  }

  private Map<String, Object> readJson(String json) {
    if (!StringUtils.hasText(json)) {
      return new HashMap<>();
    }
    try {
      return JSON.readValue(json, new TypeReference<>() {
      });
    } catch (Exception e) {
      throw new IllegalStateException("流程变量解析失败", e);
    }
  }
}
