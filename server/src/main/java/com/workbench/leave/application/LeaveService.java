package com.workbench.leave.application;

import com.workbench.audit.application.AuditService;
import com.workbench.platform.datascope.DataScopeResolver;
import com.workbench.platform.org.OrgQueryPort;
import com.workbench.platform.org.OrgUnitRef;
import com.workbench.platform.org.UserQueryPort;
import com.workbench.platform.org.UserRef;
import com.workbench.platform.org.WriteOwnershipResolver;
import com.workbench.leave.application.dto.LeaveVo;
import com.workbench.leave.domain.Leave;
import com.workbench.leave.repository.LeaveRepository;
import com.workbench.security.CurrentUserHolder;
import com.workbench.workflow.application.BizCompletionRegistry;
import com.workbench.workflow.application.WorkflowService;
import com.workbench.workflow.application.dto.InstanceSummaryVo;
import com.workbench.workflow.application.dto.StartProcessRequest;
import com.workbench.workflow.application.dto.TaskActionRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 请假业务。
 * 数据范围（阶段 2，ADR-M008）：先按单位隔离——只能看到/操作本单位（company_id）数据；
 * 本单位内 COMPANY 数据范围角色可见全部，PROJECT 数据范围角色仅可见本人项目部
 * （leave_record.project_id = 当前用户 projectId）。列表过滤在 SQL 层完成，禁止前端过滤。
 *
 * <p>流程集成：create 时强制以「当前登录用户」为申请人并启动 leave 流程（bizType=leave），
 * 回写 workflow_instance_id；流程完结/驳回时由 BizCompletionRegistry 回调 applyWfStatus
 * 同步记录状态为 已批准/已驳回。审批统一走 WorkflowService，保证只有被指派的 PROJECT_MANAGER 能批。</p>
 */
@Service
public class LeaveService {

  private final LeaveRepository leaveRepository;
  private final WorkflowService workflowService;
  private final BizCompletionRegistry completionRegistry;
  private final AuditService auditService;
  private final UserQueryPort userQueryPort;
  private final OrgQueryPort orgQueryPort;
  private final DataScopeResolver dataScopeResolver;
  private final WriteOwnershipResolver writeOwnershipResolver;

  public LeaveService(LeaveRepository leaveRepository, WorkflowService workflowService,
      BizCompletionRegistry completionRegistry, AuditService auditService,
      UserQueryPort userQueryPort, OrgQueryPort orgQueryPort,
      DataScopeResolver dataScopeResolver, WriteOwnershipResolver writeOwnershipResolver) {
    this.leaveRepository = leaveRepository;
    this.workflowService = workflowService;
    this.completionRegistry = completionRegistry;
    this.auditService = auditService;
    this.userQueryPort = userQueryPort;
    this.orgQueryPort = orgQueryPort;
    this.dataScopeResolver = dataScopeResolver;
    this.writeOwnershipResolver = writeOwnershipResolver;
    // 注册流程完结回调：leave 流程结束时同步请假记录状态
    completionRegistry.register("leave", this::applyWfStatus);
  }

  /** 列表查询：COMPANY → 本单位全部（companyId 缺失一律空列表）；PROJECT → 仅本项目部。 */
  @Transactional(readOnly = true)
  public List<LeaveVo> list() {
    List<Leave> records;
    if (dataScopeResolver.hasCompanyScope()) {
      Long companyId = dataScopeResolver.currentCompanyId();
      records = companyId == null ? List.of()
          : leaveRepository.findByCompanyIdOrderByCreatedAtDesc(companyId);
    } else {
      Long pid = dataScopeResolver.currentProjectId();
      records = pid == null ? List.of()
          : leaveRepository.findByProjectIdOrderByCreatedAtDesc(pid);
    }
    return records.stream().map(this::toVo).toList();
  }

  /** 我发起的请假（限定本单位：阶段 2 起用户名不再全局唯一）。 */
  @Transactional(readOnly = true)
  public List<LeaveVo> myLeaves() {
    Long companyId = dataScopeResolver.currentCompanyId();
    if (companyId == null) {
      return List.of();
    }
    return leaveRepository
        .findByCompanyIdAndApplicantOrderByCreatedAtDesc(companyId,
            CurrentUserHolder.currentUsername())
        .stream().map(this::toVo).toList();
  }

  /**
   * 提交请假：申请人强制取当前登录用户（不可客户端伪造），启动 leave 流程并回写实例 id。
   * 任何已登录用户均可提交本人请假（自服务）。
   */
  @Transactional
  public LeaveVo create(Leave incoming) {
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
    Long companyId = writeOwnershipResolver.requireCompanyId(pid, "LEAVE", "提交请假");
    int days = incoming.getDays() != null && incoming.getDays() > 0
        ? incoming.getDays() : daysBetween(incoming.getStartDate(), incoming.getEndDate());

    Leave leave = Leave.builder()
        .applicant(username)
        .applicantName(user.name())
        .projectId(pid)
        .project(pname)
        .companyId(companyId)
        .leaveType(incoming.getLeaveType())
        .startDate(incoming.getStartDate())
        .endDate(incoming.getEndDate())
        .days(days)
        .reason(incoming.getReason())
        .status("待审批")
        .build();
    Leave saved = leaveRepository.save(leave);

    StartProcessRequest req = new StartProcessRequest("leave", saved.getId(),
        user.name() + "的" + saved.getLeaveType() + "申请（" + days + "天）",
        Map.of("type", saved.getLeaveType(), "days", days));
    InstanceSummaryVo instance = workflowService.start(req);
    saved.setWorkflowInstanceId(instance.id());
    leaveRepository.save(saved);

    auditService.log("LEAVE_CREATE", "LEAVE", String.valueOf(saved.getId()),
        "提交请假：" + saved.getApplicantName() + " " + saved.getLeaveType() + " " + days + "天");
    return toVo(saved);
  }

  @Transactional
  public LeaveVo update(Long id, Leave incoming) {
    Leave existing = leaveRepository.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("请假记录不存在：" + id));
    assertCanModify(existing);
    if (!"待审批".equals(existing.getStatus())) {
      throw new IllegalStateException("仅待审批状态的请假可修改");
    }
    existing.setLeaveType(incoming.getLeaveType());
    existing.setStartDate(incoming.getStartDate());
    existing.setEndDate(incoming.getEndDate());
    existing.setDays(incoming.getDays() != null && incoming.getDays() > 0
        ? incoming.getDays() : daysBetween(incoming.getStartDate(), incoming.getEndDate()));
    existing.setReason(incoming.getReason());
    Leave saved = leaveRepository.save(existing);
    auditService.log("LEAVE_UPDATE", "LEAVE", String.valueOf(saved.getId()),
        "修改请假：" + saved.getApplicantName());
    return toVo(saved);
  }

  @Transactional
  public void remove(Long id) {
    Leave existing = leaveRepository.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("请假记录不存在：" + id));
    assertCanModify(existing);
    leaveRepository.delete(existing);
    auditService.log("LEAVE_DELETE", "LEAVE", String.valueOf(id),
        "删除请假：" + existing.getApplicantName());
  }

  /** 批准：经 WorkflowService 走真实审批（assigned 校验保证仅被指派经理可批）。 */
  @Transactional
  public LeaveVo approve(Long id, String comment) {
    Leave existing = leaveRepository.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("请假记录不存在：" + id));
    assertCanModify(existing);
    if (!"待审批".equals(existing.getStatus())) {
      throw new IllegalStateException("该请假已处理，不可重复审批");
    }
    if (existing.getWorkflowInstanceId() == null) {
      throw new IllegalStateException("该请假未关联流程实例");
    }
    workflowService.approveByInstance(existing.getWorkflowInstanceId(),
        new TaskActionRequest(comment));
    return toVo(leaveRepository.findById(id).orElse(existing));
  }

  /** 驳回：语义同 approve。 */
  @Transactional
  public LeaveVo reject(Long id, String comment) {
    Leave existing = leaveRepository.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("请假记录不存在：" + id));
    assertCanModify(existing);
    if (!"待审批".equals(existing.getStatus())) {
      throw new IllegalStateException("该请假已处理，不可重复审批");
    }
    if (existing.getWorkflowInstanceId() == null) {
      throw new IllegalStateException("该请假未关联流程实例");
    }
    workflowService.rejectByInstance(existing.getWorkflowInstanceId(),
        new TaskActionRequest(comment));
    return toVo(leaveRepository.findById(id).orElse(existing));
  }

  /**
   * 流程完结回调（注册到 BizCompletionRegistry）。COMPLETED → 已批准，REJECTED → 已驳回。
   * 在 WorkflowService 的事务内被调用，直接更新记录并独立记录审计。
   */
  public void applyWfStatus(Long bizId, String status) {
    Leave leave = leaveRepository.findById(bizId).orElse(null);
    if (leave == null) {
      return;
    }
    String newStatus = switch (status) {
      case "COMPLETED" -> "已批准";
      case "REJECTED" -> "已驳回";
      default -> null;
    };
    if (newStatus == null || newStatus.equals(leave.getStatus())) {
      return;
    }
    leave.setStatus(newStatus);
    leaveRepository.save(leave);
    auditService.log("LEAVE_WF", "LEAVE", String.valueOf(bizId),
        "流程" + ("已批准".equals(newStatus) ? "通过" : "驳回") + "，状态更新为" + newStatus);
  }

  /**
   * 写操作数据范围守卫：
   * ① 先过单位边界（阶段 2，ADR-M008）—— 只能操作本单位记录，COMPANY 数据范围也不例外；
   * ② PROJECT 数据范围只能操作本人项目部记录。越权写 ACCESS_DENIED 审计并抛异常。
   */
  private void assertCanModify(Leave existing) {
    if (!dataScopeResolver.canAccessCompany(existing.getCompanyId())) {
      auditService.log(CurrentUserHolder.currentUsername(), "ACCESS_DENIED", "LEAVE",
          String.valueOf(existing.getId()),
          "尝试越权操作非本单位的请假：" + existing.getApplicantName());
      throw new IllegalStateException("无权操作其他单位的请假数据");
    }
    if (dataScopeResolver.canAccessProject(existing.getProjectId())) {
      return;
    }
    auditService.log(CurrentUserHolder.currentUsername(), "ACCESS_DENIED", "LEAVE",
        String.valueOf(existing.getId()),
        "尝试越权操作非本项目部请假：" + existing.getApplicantName());
    throw new IllegalStateException("无权操作其他项目部的请假数据");
  }

  private int daysBetween(String start, String end) {
    if (start == null || end == null) {
      return 0;
    }
    try {
      long s = LocalDate.parse(start).toEpochDay();
      long e = LocalDate.parse(end).toEpochDay();
      long d = e - s + 1;
      return d > 0 ? (int) d : 0;
    } catch (Exception ex) {
      return 0;
    }
  }

  private LeaveVo toVo(Leave l) {
    return new LeaveVo(l.getId(), l.getApplicantName(), l.getApplicant(), l.getProject(),
        l.getLeaveType(), l.getStartDate(), l.getEndDate(), l.getDays(), l.getReason(),
        l.getStatus(), LeaveVo.formatAppliedAt(l.getCreatedAt()));
  }
}
