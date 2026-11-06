package com.workbench.workflow.api;

import com.workbench.common.Result;
import com.workbench.security.Permissions;
import com.workbench.workflow.application.WorkflowDefinitionService;
import com.workbench.workflow.application.dto.DefinitionSnapshotVo;
import com.workbench.workflow.application.dto.DefinitionVersionVo;
import com.workbench.workflow.application.dto.ProcessDefinitionDetailVo;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 流程定义管理接口（阶段 3 任务 4，ADR-M010）。
 *
 * <p><b>权限口径收在这一处</b>：写操作一律要求 {@code workflow:definition:manage}
 * （本期只授予 SYSTEM_ADMIN，见 V14）；只读操作只要登录即可。权限用
 * {@code @PreAuthorize} 而不是在 Service 里判断角色，避免同一份口径散落两处。</p>
 *
 * <p><b>为什么不给定义加单位维度</b>：流程定义由公司统一制定，三家单位共用一套
 * （ADR-M002 / 方案 §9.1 结论 2）。加了 company_id 反而会让"谁有权改共用定义"变成扯皮。</p>
 */
@RestController
@RequestMapping("/api/workflow/admin/definitions")
public class WorkflowDefinitionController {

  /** 管理流程定义所需的权限点。 */
  public static final String PERMISSION_MANAGE = "workflow:definition:manage";

  private final WorkflowDefinitionService definitionService;

  public WorkflowDefinitionController(WorkflowDefinitionService definitionService) {
    this.definitionService = definitionService;
  }

  /** 全部定义（含停用与节点），按 id 升序。 */
  @GetMapping
  public Result<List<ProcessDefinitionDetailVo>> list() {
    return Result.success(definitionService.listDefinitions());
  }

  /** 单个定义的完整配置。 */
  @GetMapping("/{id}")
  public Result<ProcessDefinitionDetailVo> get(@PathVariable Long id) {
    return Result.success(definitionService.getDefinition(id));
  }

  /** 某个定义的全部版本，新→旧。 */
  @GetMapping("/{id}/versions")
  public Result<List<DefinitionVersionVo>> versions(@PathVariable Long id) {
    return Result.success(definitionService.listVersions(id));
  }

  /**
   * 新建流程定义（含全部节点）。
   *
   * <p>{@code nodes} 必须是全部节点，服务端整体替换（先删后插）。</p>
   */
  @PostMapping
  @PreAuthorize(Permissions.HAS_WORKFLOW_DEFINITION_MANAGE)
  public Result<ProcessDefinitionDetailVo> create(
      @Valid @RequestBody DefinitionSnapshotVo snapshot) {
    return Result.success(definitionService.create(snapshot));
  }

  /**
   * 更新流程定义（含全部节点），自动产生一个新版本。
   *
   * <p><b>影响面（前端必须提示管理员）</b>：节点是就地更新的，运行中的实例下次流转会读到新配置。
   * 只想对以后发起的流程生效，请先把定义停用、等存量实例跑完再改。</p>
   */
  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('workflow:definition:manage')")
  public Result<ProcessDefinitionDetailVo> update(@PathVariable Long id,
      @Valid @RequestBody DefinitionSnapshotVo snapshot) {
    return Result.success(definitionService.update(id, snapshot));
  }

  /**
   * 回滚到指定版本：把该版本快照写回当前定义，并产生一个 ROLLBACK 新版本。
   *
   * @param note 回滚原因（可选），随新版本一起存档
   */
  @PostMapping("/{id}/rollback/{version}")
  @PreAuthorize(Permissions.HAS_WORKFLOW_DEFINITION_MANAGE)
  public Result<ProcessDefinitionDetailVo> rollback(@PathVariable Long id,
      @PathVariable Integer version, @RequestParam(required = false) String note) {
    return Result.success(definitionService.rollback(id, version, note));
  }
}
