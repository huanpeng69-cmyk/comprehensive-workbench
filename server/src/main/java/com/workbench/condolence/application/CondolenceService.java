package com.workbench.condolence.application;

import com.workbench.audit.application.AuditService;
import com.workbench.condolence.domain.Condolence;
import com.workbench.condolence.repository.CondolenceRepository;
import com.workbench.platform.datascope.DataScopeResolver;
import com.workbench.platform.org.OrgQueryPort;
import com.workbench.platform.org.OrgUnitRef;
import com.workbench.platform.org.UserQueryPort;
import com.workbench.platform.org.UserRef;
import com.workbench.platform.org.WriteOwnershipResolver;
import com.workbench.security.CurrentUserHolder;
import com.workbench.workflow.application.BizCompletionRegistry;
import com.workbench.workflow.application.WorkflowService;
import com.workbench.workflow.application.dto.InstanceSummaryVo;
import com.workbench.workflow.application.dto.NodeCommentRecord;
import com.workbench.workflow.application.dto.StartProcessRequest;
import com.workbench.workflow.application.dto.TaskActionRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 慰问金业务。第二个接入 BizCompletionRegistry 工作流回调的业务模块（首个为请假）。
 *
 * <p>数据范围（阶段 2，ADR-M008）：先按单位隔离——只能看到/操作本单位（company_id）数据；
 * 本单位内 COMPANY 数据范围角色可见全部，PROJECT 数据范围角色仅可见本人项目部记录。
 * 列表过滤在服务端完成，禁止前端过滤。</p>
 *
 * <p>流程集成：create 时强制以当前登录用户为申请人并启动 condolence 流程（bizType=condolence），
 * 回写 workflow_instance_id；流程完结/驳回时由 BizCompletionRegistry 回调 applyWfStatus
 * 同步记录状态为 已发放/已驳回，并把两级工会审批意见从流程任务回填到记录。</p>
 */
@Service
public class CondolenceService {

  private final CondolenceRepository condolenceRepository;
  private final WorkflowService workflowService;
  private final BizCompletionRegistry completionRegistry;
  private final AuditService auditService;
  private final UserQueryPort userQueryPort;
  private final OrgQueryPort orgQueryPort;
  private final DataScopeResolver dataScopeResolver;
  private final WriteOwnershipResolver writeOwnershipResolver;

  public CondolenceService(CondolenceRepository condolenceRepository,
      WorkflowService workflowService, BizCompletionRegistry completionRegistry,
      AuditService auditService, UserQueryPort userQueryPort,
      OrgQueryPort orgQueryPort, DataScopeResolver dataScopeResolver,
      WriteOwnershipResolver writeOwnershipResolver) {
    this.condolenceRepository = condolenceRepository;
    this.workflowService = workflowService;
    this.completionRegistry = completionRegistry;
    this.auditService = auditService;
    this.userQueryPort = userQueryPort;
    this.orgQueryPort = orgQueryPort;
    this.dataScopeResolver = dataScopeResolver;
    this.writeOwnershipResolver = writeOwnershipResolver;
    // 注册流程完结回调：condolence 流程结束时同步慰问金记录状态（第二个接入的业务）
    completionRegistry.register("condolence", this::applyWfStatus);
  }

  /** 列表查询：COMPANY → 本单位全部（companyId 缺失一律空列表）；PROJECT → 仅本项目部。 */
  @Transactional(readOnly = true)
  public List<CondolenceVo> list() {
    List<Condolence> records;
    if (dataScopeResolver.hasCompanyScope()) {
      Long companyId = dataScopeResolver.currentCompanyId();
      records = companyId == null ? List.of()
          : condolenceRepository.findByCompanyIdOrderByCreatedAtDesc(companyId);
    } else {
      Long pid = dataScopeResolver.currentProjectId();
      records = pid == null ? List.of()
          : condolenceRepository.findByProjectIdOrderByCreatedAtDesc(pid);
    }
    return records.stream().map(this::toVo).toList();
  }

  /** 我发起的慰问金申请（限定本单位：阶段 2 起用户名不再全局唯一）。 */
  @Transactional(readOnly = true)
  public List<CondolenceVo> myCondolences() {
    Long companyId = dataScopeResolver.currentCompanyId();
    if (companyId == null) {
      return List.of();
    }
    return condolenceRepository
        .findByCompanyIdAndApplicantOrderByCreatedAtDesc(companyId,
            CurrentUserHolder.currentUsername())
        .stream().map(this::toVo).toList();
  }

  /**
   * 提交慰问金申请：申请人强制取当前登录用户（不可客户端伪造），启动 condolence 流程并回写实例 id。
   * 任何已登录用户均可提交本人申请（自服务）。
   */
  @Transactional
  public CondolenceVo create(Condolence incoming) {
    String username = CurrentUserHolder.currentUsername();
    if (username == null) {
      throw new IllegalStateException("未登录");
    }
    UserRef user = userQueryPort
        .findByCompanyIdAndUsername(dataScopeResolver.currentCompanyId(), username)
        .orElseThrow(() -> new IllegalArgumentException("当前用户不存在"));
    Long pid = dataScopeResolver.currentProjectId();
    String pname = pid != null
        ? orgQueryPort.findById(pid).map(OrgUnitRef::name).orElse(null) : null;
    Long companyId = writeOwnershipResolver.requireCompanyId(pid, "CONDOLENCE", "提交慰问金申请");
    BigDecimal amount = incoming.getAmount() != null ? incoming.getAmount() : BigDecimal.ZERO;
    LocalDateTime now = LocalDateTime.now();

    Condolence condolence = Condolence.builder()
        .applicant(user.name())
        .applicantUsername(username)
        .projectId(pid)
        .project(pname)
        .companyId(companyId)
        .category(incoming.getCategory())
        .reason(incoming.getReason())
        .amount(amount)
        .amountInWords(incoming.getAmountInWords())
        .status("待审批")
        .createdAt(now)
        .updatedAt(now)
        .build();
    Condolence saved = condolenceRepository.save(condolence);

    StartProcessRequest req = new StartProcessRequest("condolence", saved.getId(),
        user.name() + "的" + saved.getCategory() + "慰问金申请（" + amount + "元）",
        Map.of("category", saved.getCategory(), "amount", amount));
    InstanceSummaryVo instance = workflowService.start(req);
    saved.setWorkflowInstanceId(instance.id());
    condolenceRepository.save(saved);

    auditService.log("CONDOLENCE_CREATE", "CONDOLENCE", String.valueOf(saved.getId()),
        "提交慰问金申请：" + saved.getApplicant() + " " + saved.getCategory());
    return toVo(saved);
  }

  /** 批准：经 WorkflowService 走真实审批（assigned 校验保证仅被指派工会审批人可批）。 */
  @Transactional
  public CondolenceVo approve(Long id, String comment) {
    Condolence existing = condolenceRepository.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("慰问金申请不存在：" + id));
    assertCanModify(existing);
    if (!"待审批".equals(existing.getStatus())) {
      throw new IllegalStateException("该申请已处理，不可重复审批");
    }
    if (existing.getWorkflowInstanceId() == null) {
      throw new IllegalStateException("该申请未关联流程实例");
    }
    workflowService.approveByInstance(existing.getWorkflowInstanceId(),
        new TaskActionRequest(comment));
    return toVo(condolenceRepository.findById(id).orElse(existing));
  }

  /** 驳回：语义同 approve。 */
  @Transactional
  public CondolenceVo reject(Long id, String comment) {
    Condolence existing = condolenceRepository.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("慰问金申请不存在：" + id));
    assertCanModify(existing);
    if (!"待审批".equals(existing.getStatus())) {
      throw new IllegalStateException("该申请已处理，不可重复审批");
    }
    if (existing.getWorkflowInstanceId() == null) {
      throw new IllegalStateException("该申请未关联流程实例");
    }
    workflowService.rejectByInstance(existing.getWorkflowInstanceId(),
        new TaskActionRequest(comment));
    return toVo(condolenceRepository.findById(id).orElse(existing));
  }

  /**
   * 流程完结回调（注册到 BizCompletionRegistry）。COMPLETED → 已发放（回填两级工会意见），
   * REJECTED → 已驳回。在 WorkflowService 的事务内被调用，直接更新记录并独立记录审计。
   */
  public void applyWfStatus(Long bizId, String status) {
    Condolence condolence = condolenceRepository.findById(bizId).orElse(null);
    if (condolence == null) {
      return;
    }
    if ("COMPLETED".equals(status)) {
      condolence.setStatus("已发放");
      if (condolence.getWorkflowInstanceId() != null) {
        // 节点 seq=1 项目工会意见，seq=2 二级单位工会意见
        workflowService.instanceTaskComments(condolence.getWorkflowInstanceId()).forEach(tc -> {
          if (tc.seq() == 1 && tc.comment() != null) {
            condolence.setProjectUnionOpinion(tc.comment());
          }
          if (tc.seq() == 2 && tc.comment() != null) {
            condolence.setCompanyUnionOpinion(tc.comment());
          }
        });
      }
      auditService.log("CONDOLENCE_WF", "CONDOLENCE", String.valueOf(bizId),
          "流程通过，状态更新为已发放");
    } else if ("REJECTED".equals(status)) {
      condolence.setStatus("已驳回");
      auditService.log("CONDOLENCE_WF", "CONDOLENCE", String.valueOf(bizId),
          "流程驳回，状态更新为已驳回");
    } else {
      return;
    }
    condolenceRepository.save(condolence);
  }

  /**
   * 写操作数据范围守卫（阶段 2 新增，ADR-M008）：先过单位边界——只能操作本单位记录，
   * COMPANY 数据范围也不例外。越权写 ACCESS_DENIED 审计并抛异常。
   * 项目部级判定不在本模块重复：慰问金审批由 WorkflowService 按被指派人把关。
   */
  private void assertCanModify(Condolence existing) {
    if (dataScopeResolver.canAccessCompany(existing.getCompanyId())) {
      return;
    }
    auditService.log(CurrentUserHolder.currentUsername(), "ACCESS_DENIED", "CONDOLENCE",
        String.valueOf(existing.getId()),
        "尝试越权操作非本单位的慰问金申请：" + existing.getApplicant());
    throw new IllegalStateException("无权操作其他单位的慰问金数据");
  }

  private CondolenceVo toVo(Condolence c) {
    return new CondolenceVo(c.getId(), c.getApplicant(), c.getApplicantUsername(), c.getProject(),
        c.getCategory(), c.getReason(), c.getAmount(), c.getAmountInWords(),
        c.getProjectUnionOpinion(), c.getCompanyUnionOpinion(), c.getStatus(),
        c.getWorkflowInstanceId(), CondolenceVo.formatAppliedAt(c.getCreatedAt()));
  }
}
