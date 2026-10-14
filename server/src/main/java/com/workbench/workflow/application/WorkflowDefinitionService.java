package com.workbench.workflow.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workbench.audit.application.AuditService;
import com.workbench.security.CurrentUserHolder;
import com.workbench.workflow.application.dto.DefinitionSnapshotVo;
import com.workbench.workflow.application.dto.DefinitionVersionVo;
import com.workbench.workflow.application.dto.NodeInputVo;
import com.workbench.workflow.application.dto.ProcessDefinitionDetailVo;
import com.workbench.workflow.domain.ProcessDefinition;
import com.workbench.workflow.domain.ProcessDefinitionVersion;
import com.workbench.workflow.domain.ProcessNode;
import com.workbench.workflow.repository.ProcessDefinitionRepository;
import com.workbench.workflow.repository.ProcessDefinitionVersionRepository;
import com.workbench.workflow.repository.ProcessNodeRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 流程定义管理（阶段 3 任务 4，ADR-M010）：把 {@code WorkflowDataSeeder} 的硬编码定义
 * 变成后台可管理的配置数据。
 *
 * <p><b>核心约束</b>：</p>
 * <ol>
 *   <li><b>版本只增不减</b>：每次改动都先存一份完整快照（定义 + 全部节点）再落库，
 *       版本号单调递增，历史版本一行不删——它们是审计与回退的依据；</li>
 *   <li><b>回滚 = 写回旧快照 + 产生新版本</b>，不是删掉中间版本。因此"回到 v1"之后，
 *       当前版本号是 3 而不是 1，审计链完整；</li>
 *   <li><b>改动不影响运行中的实例</b>：实例按 {@code definitionId} 读取节点，
 *       而节点行是就地更新的——这一点必须在文档里向管理员说清楚（见 {@link #save} 注释）；</li>
 *   <li><b>节点整体替换</b>：后台提交的是该定义的全部节点，服务端先删后插。
 *       不做"增量合并"——增量会让"某个节点被谁删了"无法从提交内容里读出来；</li>
 *   <li><b>定义不加 company_id</b>：三家单位共用一套定义（ADR-M002 / 方案 §9.1 结论 2）。
 *       权限与审计由代码侧负责，不靠数据行隔离。</li>
 * </ol>
 *
 * <p>权限点 {@code workflow:definition:manage} 由 Controller 的 {@code @PreAuthorize} 把关；
 * 本类不再自行判断角色——避免同一份权限口径散落两处。</p>
 */
@Service
public class WorkflowDefinitionService {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final ProcessDefinitionRepository definitionRepository;
  private final ProcessNodeRepository nodeRepository;
  private final ProcessDefinitionVersionRepository versionRepository;
  private final AuditService auditService;

  public WorkflowDefinitionService(ProcessDefinitionRepository definitionRepository,
      ProcessNodeRepository nodeRepository,
      ProcessDefinitionVersionRepository versionRepository,
      AuditService auditService) {
    this.definitionRepository = definitionRepository;
    this.nodeRepository = nodeRepository;
    this.versionRepository = versionRepository;
    this.auditService = auditService;
  }

  /** 管理端：全部定义（含节点数），按 id 升序。 */
  @Transactional(readOnly = true)
  public List<ProcessDefinitionDetailVo> listDefinitions() {
    return definitionRepository.findAllByOrderByIdAsc().stream()
        .map(this::toDetailVo)
        .toList();
  }

  /** 管理端：单个定义的完整配置（含全部节点，按 seq 升序）。 */
  @Transactional(readOnly = true)
  public ProcessDefinitionDetailVo getDefinition(Long id) {
    return toDetailVo(loadDefinition(id));
  }

  /** 管理端：某个定义的全部版本，新→旧。 */
  @Transactional(readOnly = true)
  public List<DefinitionVersionVo> listVersions(Long id) {
    loadDefinition(id);
    return versionRepository.findByDefinitionIdOrderByVersionDesc(id).stream()
        .map(v -> new DefinitionVersionVo(v.getVersion(), v.getChangeType(), v.getChangeNote(),
            v.getCreatedBy(), v.getCreatedAt()))
        .toList();
  }

  /**
   * 新建流程定义（含全部节点），并落第一个版本。
   *
   * <p>{@code code} 全局唯一（ADR-M002：流程定义由公司统一制定）；重复 code 直接拒绝，
   * 不做"自动改名"——静默改名会让调用方按 code 发起时找不到定义。</p>
   */
  @Transactional
  public ProcessDefinitionDetailVo create(DefinitionSnapshotVo snapshot) {
    String code = requireText(snapshot.code(), "流程编码");
    if (definitionRepository.existsByCode(code)) {
      throw new IllegalArgumentException("流程编码已存在: " + code);
    }
    ProcessDefinition definition = definitionRepository.save(ProcessDefinition.builder()
        .code(code)
        .name(requireText(snapshot.name(), "流程名称"))
        .bizType(requireText(snapshot.bizType(), "业务类型"))
        .version(1)
        .enabled(Boolean.TRUE.equals(snapshot.enabled()))
        .description(blankToNull(snapshot.description()))
        .build());
    List<ProcessNode> nodes = replaceNodes(definition, snapshot.nodes());
    int version = saveVersion(definition, nodes, ProcessDefinitionVersion.CHANGE_INIT,
        snapshot.changeNote());
    auditService.log(CurrentUserHolder.currentUsername(), "WF_DEF_CREATE", "PROCESS_DEFINITION",
        String.valueOf(definition.getId()),
        "新建流程定义「" + definition.getName() + "」，节点 " + nodes.size() + " 个，版本 v" + version);
    return toDetailVo(definition);
  }

  /**
   * 更新流程定义（含全部节点）：先存快照，再落库。
   *
   * <p><b>已知影响面（必须向管理员明示）</b>：节点行是就地更新的，因此<b>已在运行中的实例</b>
   * 在下次流转时会读到新节点配置。若管理员只想让改动对"以后发起的流程"生效，
   * 需要先把定义 {@code enabled} 置 false、等存量实例跑完再改——这个口径由后台提示承担，
   * 服务层不擅自冻结运行中的实例（那会让"紧急修正审批人"变得不可能）。</p>
   */
  @Transactional
  public ProcessDefinitionDetailVo update(Long id, DefinitionSnapshotVo snapshot) {
    ProcessDefinition definition = loadDefinition(id);
    String code = requireText(snapshot.code(), "流程编码");
    if (!code.equals(definition.getCode()) && definitionRepository.existsByCode(code)) {
      throw new IllegalArgumentException("流程编码已被其它定义占用: " + code);
    }
    definition.setCode(code);
    definition.setName(requireText(snapshot.name(), "流程名称"));
    definition.setBizType(requireText(snapshot.bizType(), "业务类型"));
    definition.setEnabled(Boolean.TRUE.equals(snapshot.enabled()));
    definition.setDescription(blankToNull(snapshot.description()));
    definitionRepository.save(definition);

    List<ProcessNode> nodes = replaceNodes(definition, snapshot.nodes());
    int version = saveVersion(definition, nodes, ProcessDefinitionVersion.CHANGE_UPDATE,
        snapshot.changeNote());
    auditService.log(CurrentUserHolder.currentUsername(), "WF_DEF_UPDATE", "PROCESS_DEFINITION",
        String.valueOf(definition.getId()),
        "修改流程定义「" + definition.getName() + "」，节点 " + nodes.size() + " 个，版本 v" + version);
    return toDetailVo(definition);
  }

  /**
   * 回滚到指定版本：把该版本的快照写回当前定义，并产生一个 {@code ROLLBACK} 新版本。
   *
   * <p>回滚目标必须是已存在的版本；回滚到"当前版本"没有意义，直接拒绝（避免产生空转版本号）。</p>
   */
  @Transactional
  public ProcessDefinitionDetailVo rollback(Long id, Integer targetVersion, String note) {
    ProcessDefinition definition = loadDefinition(id);
    ProcessDefinitionVersion target = versionRepository
        .findByDefinitionIdAndVersion(id, targetVersion)
        .orElseThrow(() -> new EntityNotFoundException("版本不存在: v" + targetVersion));
    Integer current = versionRepository.findFirstByDefinitionIdOrderByVersionDesc(id);
    if (Objects.equals(current, targetVersion)) {
      throw new IllegalArgumentException("目标版本就是当前版本，无需回滚");
    }

    DefinitionSnapshotVo snapshot = readSnapshot(target.getSnapshot());
    definition.setCode(snapshot.code());
    definition.setName(snapshot.name());
    definition.setBizType(snapshot.bizType());
    definition.setEnabled(Boolean.TRUE.equals(snapshot.enabled()));
    definition.setDescription(blankToNull(snapshot.description()));
    definitionRepository.save(definition);

    List<ProcessNode> nodes = replaceNodes(definition, snapshot.nodes());
    int version = saveVersion(definition, nodes, ProcessDefinitionVersion.CHANGE_ROLLBACK,
        StringUtils.hasText(note) ? note : "回滚到 v" + targetVersion);
    auditService.log(CurrentUserHolder.currentUsername(), "WF_DEF_ROLLBACK", "PROCESS_DEFINITION",
        String.valueOf(definition.getId()),
        "回滚流程定义「" + definition.getName() + "」到 v" + targetVersion + "，产生 v" + version);
    return toDetailVo(definition);
  }

  // --------------------------------------------------------------------------
  // 内部实现
  // --------------------------------------------------------------------------

  /**
   * 整体替换某定义的节点：先删后插。
   *
   * <p>顺序很重要——先删后插才能在"节点数变少"时不留残留行；而删除必须在同一个事务里，
   * 否则中途失败会留下"定义还在、节点没了"的半成品状态。</p>
   */
  private List<ProcessNode> replaceNodes(ProcessDefinition definition,
      List<NodeInputVo> inputs) {
    if (inputs == null || inputs.isEmpty()) {
      throw new IllegalArgumentException("流程至少要配置一个节点");
    }
    List<NodeInputVo> sorted = inputs.stream()
        .sorted(Comparator.comparingInt(NodeInputVo::seq))
        .toList();
    nodeRepository.deleteByDefinitionId(definition.getId());
    return sorted.stream()
        .map(in -> nodeRepository.save(ProcessNode.builder()
            .definitionId(definition.getId())
            .seq(in.seq())
            .nodeType(requireText(in.nodeType(), "节点类型"))
            .name(requireText(in.name(), "节点名称"))
            .assigneeType(blankToNull(in.assigneeType()))
            .assigneeExpr(blankToNull(in.assigneeExpr()))
            .multiType(blankToNull(in.multiType()))
            .conditionExpr(blankToNull(in.conditionExpr()))
            .description(blankToNull(in.description()))
            .build()))
        .toList();
  }

  /** 落一份完整快照（定义 + 全部节点），返回新版本号。 */
  private int saveVersion(ProcessDefinition definition, List<ProcessNode> nodes,
      String changeType, String changeNote) {
    // 必须是 Integer：没有版本时该方法返回 null，赋给 int 会先拆箱炸 NPE。
    // 必须是 Integer：没有版本时该方法返回 null，赋给 int 会先拆箱炸 NPE。
    Integer latest = versionRepository.findFirstByDefinitionIdOrderByVersionDesc(definition.getId());
    int next = (latest == null ? 0 : latest) + 1;
    versionRepository.save(ProcessDefinitionVersion.builder()
        .definitionId(definition.getId())
        .version(next)
        .changeType(changeType)
        .changeNote(blankToNull(changeNote))
        .snapshot(writeSnapshot(definition, nodes))
        .createdBy(StringUtils.hasText(CurrentUserHolder.currentUsername())
            ? CurrentUserHolder.currentUsername() : "system")
        .build());
    return next;
  }

  private ProcessDefinitionDetailVo toDetailVo(ProcessDefinition definition) {
    List<ProcessNode> nodes = nodeRepository.findByDefinitionIdOrderBySeq(definition.getId());
    return new ProcessDefinitionDetailVo(definition.getId(), definition.getCode(),
        definition.getName(), definition.getBizType(), definition.getVersion(),
        definition.getEnabled(), definition.getDescription(), nodes.size(),
        nodes.stream().map(WorkflowDefinitionService::toNodeVo).toList());
  }

  private static com.workbench.workflow.application.dto.NodeVo toNodeVo(ProcessNode node) {
    return new com.workbench.workflow.application.dto.NodeVo(node.getId(), node.getSeq(),
        node.getNodeType(), node.getName(), node.getAssigneeType(), node.getAssigneeExpr(),
        node.getMultiType(), node.getConditionExpr(), node.getDescription());
  }

  private ProcessDefinition loadDefinition(Long id) {
    return definitionRepository.findById(id)
        .orElseThrow(() -> new EntityNotFoundException("流程定义不存在: " + id));
  }

  private String writeSnapshot(ProcessDefinition definition, List<ProcessNode> nodes) {
    try {
      Map<String, Object> snapshot = new LinkedHashMap<>();
      Map<String, Object> def = new LinkedHashMap<>();
      def.put("code", definition.getCode());
      def.put("name", definition.getName());
      def.put("bizType", definition.getBizType());
      def.put("enabled", definition.getEnabled());
      def.put("description", definition.getDescription());
      snapshot.put("definition", def);
      snapshot.put("nodes", nodes.stream().map(n -> {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("seq", n.getSeq());
        node.put("nodeType", n.getNodeType());
        node.put("name", n.getName());
        node.put("assigneeType", n.getAssigneeType());
        node.put("assigneeExpr", n.getAssigneeExpr());
        node.put("multiType", n.getMultiType());
        node.put("conditionExpr", n.getConditionExpr());
        node.put("description", n.getDescription());
        return node;
      }).toList());
      return JSON.writeValueAsString(snapshot);
    } catch (Exception e) {
      throw new IllegalStateException("流程定义快照序列化失败", e);
    }
  }

  private DefinitionSnapshotVo readSnapshot(String json) {
    try {
      Map<String, Object> snapshot = JSON.readValue(json, new TypeReference<>() {
      });
      @SuppressWarnings("unchecked")
      Map<String, Object> def = (Map<String, Object>) snapshot.get("definition");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> nodes = (List<Map<String, Object>>) snapshot.get("nodes");
      return new DefinitionSnapshotVo(
          (String) def.get("code"),
          (String) def.get("name"),
          (String) def.get("bizType"),
          (Boolean) def.get("enabled"),
          (String) def.get("description"),
          null,
          nodes.stream().map(n -> new NodeInputVo(
              ((Number) n.get("seq")).intValue(),
              (String) n.get("nodeType"),
              (String) n.get("name"),
              (String) n.get("assigneeType"),
              (String) n.get("assigneeExpr"),
              (String) n.get("multiType"),
              (String) n.get("conditionExpr"),
              (String) n.get("description"))).toList());
    } catch (Exception e) {
      throw new IllegalStateException("流程定义快照解析失败", e);
    }
  }

  private static String requireText(String value, String field) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(field + "不能为空");
    }
    return value.trim();
  }

  private static String blankToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

}
