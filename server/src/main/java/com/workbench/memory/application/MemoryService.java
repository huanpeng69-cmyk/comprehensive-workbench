package com.workbench.memory.application;

import com.workbench.audit.application.AuditService;
import com.workbench.memory.domain.MemorySource;
import com.workbench.memory.domain.MemoryType;
import com.workbench.memory.domain.UserMemory;
import com.workbench.memory.dto.MemoryCreateRequest;
import com.workbench.memory.dto.MemoryRecallResult;
import com.workbench.memory.dto.MemoryUpdateRequest;
import com.workbench.memory.dto.MemoryView;
import com.workbench.memory.repository.UserMemoryRepository;
import com.workbench.security.CurrentUserHolder;
import jakarta.persistence.EntityNotFoundException;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户长期记忆的门面：写入（含去重与超限淘汰）、列表、删除、检索注入。
 *
 * <p><b>归属判定只有一处</b>：{@link #currentOwnerOrNull()} 从安全上下文取
 * (companyId, username)。所有读写方法的第一件事都是拿 Owner，并且把 owner 的两个字段
 * 一起传给仓储——不存在「先按 id 查再比对归属」这种容易漏判的写法。</p>
 *
 * <p><b>读写不对称是刻意的</b>（全项目约定）：</p>
 * <ul>
 *   <li>读路径（{@link #list()} / {@link #recall}）拿不到归属单位时返回空集——宁可看不到，
 *       也绝不猜一个单位出来；</li>
 *   <li>写路径（{@link #remember} / {@link #update} / {@link #forget}）拿不到归属单位时拒绝并留审计——
 *       写错单位比写不进去严重得多。</li>
 * </ul>
 *
 * <p><b>本类不直连 identity 仓储</b>：用 username 而不是 userId 做归属键，既满足
 * 「每个用户一份记忆」，又不必绕过 {@code ModuleBoundaryTest} 的模块边界（业务模块禁止依赖
 * {@code com.workbench.identity.repository}）。</p>
 */
@Service
public class MemoryService {

  /** 单条记忆正文上限，与 {@link MemoryCreateRequest} 的 @Size 保持一致。 */
  public static final int MAX_CONTENT_LENGTH = 1000;

  /** 每个用户最多保留多少条记忆；超出后从最不重要、最旧的开始淘汰。 */
  public static final int MAX_MEMORIES_PER_USER = 200;

  /** 未指定重要度时的默认值：中间偏上，既不压过用户显式标注的，也不会被优先淘汰。 */
  public static final int DEFAULT_IMPORTANCE = 50;

  private static final String AUDIT_TARGET_TYPE = "USER_MEMORY";

  private final UserMemoryRepository repository;
  private final MemoryContextBuilder contextBuilder;
  private final AuditService auditService;

  public MemoryService(
      UserMemoryRepository repository, MemoryContextBuilder contextBuilder, AuditService auditService) {
    this.repository = repository;
    this.contextBuilder = contextBuilder;
    this.auditService = auditService;
  }

  /**
   * 用户手写记忆（source 固定 {@link MemorySource#MANUAL}）。
   *
   * @throws IllegalArgumentException 正文为空或超长、类型非法、重要度越界
   */
  @Transactional
  public MemoryView remember(MemoryCreateRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("记忆内容不能为空");
    }
    return remember(request.content(), MemoryType.parse(request.type()), request.importance(),
        request.ttlDays(), MemorySource.MANUAL);
  }

  /**
   * 落一条记忆：手写与 AI 会话沉淀共用这条路径。
   *
   * <p><b>去重不靠「先查后插」</b>：内容指纹相同（同单位 + 同人 + 同类型 + 同归一化正文）时
   * 走合并——只提升重要度、刷新更新时间，不新增行。库里的唯一索引是最终兜底，
   * 这里先查一次只是为了让「重复写入」表现成幂等更新而不是报约束冲突。</p>
   *
   * <p>合并时重要度取<b>较大值</b>：用户上次说「这条对我很重要」（80），这次顺口重提（默认 50），
   * 不该被降级。过期时间则相反，显式传了就覆盖（用户重新给了有效期，说明想延长）。</p>
   *
   * @param content    正文，必填
   * @param type       类型，null 视为 {@link MemoryType#SEMANTIC}
   * @param importance 重要度 0..100，null 用 {@link #DEFAULT_IMPORTANCE}
   * @param ttlDays    有效期天数，null 表示永不过期
   * @param source     来源，null 视为 {@link MemorySource#MANUAL}
   */
  @Transactional
  public MemoryView remember(
      String content, MemoryType type, Integer importance, Integer ttlDays, MemorySource source) {
    Owner owner = requireOwner("保存记忆");
    String text = validateContent(content);
    MemoryType memoryType = type == null ? MemoryType.SEMANTIC : type;
    int value = validateImportance(importance);

    LocalDateTime now = LocalDateTime.now();
    LocalDateTime expiresAt = ttlDays == null ? null : now.plusDays(ttlDays);
    String hash = MemoryText.contentHash(text);

    UserMemory existing = repository
        .findByCompanyIdAndOwnerUsernameAndMemoryTypeAndContentHash(
            owner.companyId(), owner.username(), memoryType, hash)
        .orElse(null);

    boolean created = existing == null;
    UserMemory memory;
    if (created) {
      memory = UserMemory.builder()
          .companyId(owner.companyId())
          .ownerUsername(owner.username())
          .memoryType(memoryType)
          .content(text)
          .contentHash(hash)
          .importance(value)
          .source(source == null ? MemorySource.MANUAL : source)
          .createdAt(now)
          .updatedAt(now)
          .expiresAt(expiresAt)
          .build();
    } else {
      memory = existing;
      memory.setImportance(Math.max(memory.getImportance() == null ? 0 : memory.getImportance(), value));
      memory.setUpdatedAt(now);
      if (expiresAt != null) {
        memory.setExpiresAt(expiresAt);
      }
    }

    UserMemory saved = repository.save(memory);
    evictOverflow(owner);
    auditService.log(owner.username(), created ? "MEMORY_CREATE" : "MEMORY_MERGE", AUDIT_TARGET_TYPE,
        String.valueOf(saved.getId()),
        (created ? "新增" : "合并") + memoryType.label() + "，重要度 " + value
            + "：" + MemoryText.truncateToChars(text, 40));
    return MemoryView.of(saved, now);
  }

  /**
   * 修改自己的一条记忆（PUT /api/memories/{id}）。
   *
   * <p><b>为什么需要这个方法，而不是让前端「删掉再建」</b>：删旧建新会把 {@code created_at}
   * 与来源一起丢掉，审计里也会变成两条互不相关的记录；用户点的是「编辑」，
   * 那这条记忆就应该真的被改过，而不是被替换成一个新身份。</p>
   *
   * <p><b>改了正文就必须重算指纹</b>：{@code content_hash} 是去重键的一段，
   * 不重算会留下「内容是新、指纹是旧」的怪状态——既查不到也去不掉。改类型同理，
   * 类型也是去重键的一段，所以类型变化后要按新类型重新查重。</p>
   *
   * <p><b>改到与另一条重复时报 400，不静默合并</b>：与 {@link #remember} 的幂等合并不同，
   * 这里的语义是「用户正在明确修改第 N 条」；若悄悄把它并进另一条，用户看到的现象是
   * 「我改的那条消失了」，比直接报错难排查得多。</p>
   *
   * <p>字段语义见 {@link MemoryUpdateRequest}：null = 保持原值，{@code ttlDays} 传 0 = 清除有效期。</p>
   *
   * @throws EntityNotFoundException  记忆不存在，或存在但不属于当前用户——对外统一报「不存在」
   * @throws IllegalArgumentException 正文为空/超长、类型非法、重要度越界、改后与另一条内容重复
   */
  @Transactional
  public MemoryView update(Long id, MemoryUpdateRequest request) {
    Owner owner = requireOwner("修改记忆");
    if (id == null) {
      throw new IllegalArgumentException("记忆 id 不能为空");
    }
    if (request == null) {
      throw new IllegalArgumentException("记忆内容不能为空");
    }
    UserMemory memory = repository.findByIdAndCompanyIdAndOwnerUsername(id, owner.companyId(), owner.username())
        .orElseThrow(() -> new EntityNotFoundException("记忆不存在或不属于当前用户：" + id));

    String text = validateContent(request.content());
    MemoryType memoryType = request.type() == null
        ? memory.getMemoryType()
        : MemoryType.parse(request.type());
    int importance = request.importance() == null
        ? validateImportance(memory.getImportance())
        : validateImportance(request.importance());
    String hash = MemoryText.contentHash(text);

    UserMemory duplicate = repository
        .findByCompanyIdAndOwnerUsernameAndMemoryTypeAndContentHash(
            owner.companyId(), owner.username(), memoryType, hash)
        .orElse(null);
    if (duplicate != null && !duplicate.getId().equals(memory.getId())) {
      throw new IllegalArgumentException(
          "已存在内容相同的" + memoryType.label() + "，请直接修改那一条，不必重复保存");
    }

    LocalDateTime now = LocalDateTime.now();
    memory.setMemoryType(memoryType);
    memory.setContent(text);
    memory.setContentHash(hash);
    memory.setImportance(importance);
    memory.setUpdatedAt(now);
    if (request.ttlDays() != null) {
      // 0 = 显式清除有效期；正数 = 从「现在」重新起算，而不是在原到期时间上叠加
      memory.setExpiresAt(request.ttlDays() == 0 ? null : now.plusDays(request.ttlDays()));
    }

    UserMemory saved = repository.save(memory);
    auditService.log(owner.username(), "MEMORY_UPDATE", AUDIT_TARGET_TYPE, String.valueOf(id),
        "修改" + memoryType.label() + "，重要度 " + importance
            + "：" + MemoryText.truncateToChars(text, 40));
    return MemoryView.of(saved, now);
  }

  /** 当前用户的记忆列表（含已过期，按更新时间倒序）：管理界面用，过期项由 {@code expired} 标注。 */
  @Transactional(readOnly = true)
  public List<MemoryView> list() {
    Owner owner = currentOwnerOrNull();
    if (owner == null) {
      return List.of();
    }
    LocalDateTime now = LocalDateTime.now();
    return repository.findByCompanyIdAndOwnerUsernameOrderByUpdatedAtDesc(owner.companyId(), owner.username())
        .stream()
        .map(memory -> MemoryView.of(memory, now))
        .toList();
  }

  /**
   * 删除自己的一条记忆。
   *
   * @throws EntityNotFoundException 记忆不存在，或存在但不属于当前用户——两种情况对外都报「不存在」，
   *                                 不泄露「这条 id 是别人的」这一信息
   */
  @Transactional
  public void forget(Long id) {
    Owner owner = requireOwner("删除记忆");
    if (id == null) {
      throw new IllegalArgumentException("记忆 id 不能为空");
    }
    UserMemory memory = repository.findByIdAndCompanyIdAndOwnerUsername(id, owner.companyId(), owner.username())
        .orElseThrow(() -> new EntityNotFoundException("记忆不存在或不属于当前用户：" + id));
    repository.delete(memory);
    auditService.log(owner.username(), "MEMORY_DELETE", AUDIT_TARGET_TYPE, String.valueOf(id),
        "删除记忆：" + MemoryText.truncateToChars(memory.getContent(), 40));
  }

  /**
   * 按当前话题检索记忆并压成可注入的块。
   *
   * @param query       当前话题，可空（空则退化为按重要度 + 新鲜度排序）
   * @param topK        最多取几条，null 用默认值
   * @param tokenBudget token 预算，null 用默认值
   * @return 无归属单位或无可用记忆时返回 {@link MemoryRecallResult#empty()}（块为空字符串）
   */
  @Transactional(readOnly = true)
  public MemoryRecallResult recall(String query, Integer topK, Integer tokenBudget) {
    Owner owner = currentOwnerOrNull();
    if (owner == null) {
      return MemoryRecallResult.empty();
    }
    LocalDateTime now = LocalDateTime.now();
    List<UserMemory> candidates = repository
        .findByCompanyIdAndOwnerUsernameAndExpiresAtIsNullOrExpiresAtAfterOrderByUpdatedAtDesc(
            owner.companyId(), owner.username(), now);
    return contextBuilder.build(candidates, query,
        topK == null ? MemoryContextBuilder.DEFAULT_TOP_K : topK,
        tokenBudget == null ? MemoryContextBuilder.DEFAULT_TOKEN_BUDGET : tokenBudget,
        now);
  }

  /**
   * Agent 提示词注入用的便捷入口：默认条数与预算。
   *
   * <p>调用方（{@code AgentService}）必须自己在主线程取用并 try/catch——记忆是增强项，
   * 检索失败只降级为「本轮不注入」，绝不打断对话。</p>
   */
  @Transactional(readOnly = true)
  public MemoryRecallResult recallForPrompt(String query) {
    return recall(query, null, null);
  }

  /** 正文校验：空、超长都在这里挡掉，返回 trim 后的正文。 */
  private static String validateContent(String content) {
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("记忆内容不能为空");
    }
    String text = content.trim();
    if (text.length() > MAX_CONTENT_LENGTH) {
      throw new IllegalArgumentException("记忆内容不能超过 " + MAX_CONTENT_LENGTH + " 个字符");
    }
    return text;
  }

  private static int validateImportance(Integer importance) {
    int value = importance == null ? DEFAULT_IMPORTANCE : importance;
    if (value < 0 || value > 100) {
      throw new IllegalArgumentException("重要度必须在 0 到 100 之间");
    }
    return value;
  }

  /**
   * 超上限时淘汰：先按「重要度升序、更新时间升序」取全部，删掉队首的溢出条数。
   *
   * <p>淘汰口径刻意选「最不重要 + 最旧」而不是单纯最旧：一条刚写的重要记忆不应该
   * 因为用户半年没提就被挤掉。</p>
   */
  private void evictOverflow(Owner owner) {
    long total = repository.countByCompanyIdAndOwnerUsername(owner.companyId(), owner.username());
    if (total <= MAX_MEMORIES_PER_USER) {
      return;
    }
    int overflow = (int) (total - MAX_MEMORIES_PER_USER);
    List<UserMemory> ordered = repository
        .findByCompanyIdAndOwnerUsernameOrderByImportanceAscUpdatedAtAsc(owner.companyId(), owner.username());
    int removed = 0;
    for (int i = 0; i < overflow && i < ordered.size(); i++) {
      repository.delete(ordered.get(i));
      removed++;
    }
    if (removed > 0) {
      auditService.log(owner.username(), "MEMORY_EVICT", AUDIT_TARGET_TYPE, "-",
          "记忆条数超过上限 " + MAX_MEMORIES_PER_USER + "，淘汰最不重要的 " + removed + " 条");
    }
  }

  /**
   * 当前用户归属；任一要素缺失都返回 null。
   *
   * <p>username 与 companyId 必须同时在：只有 username 无法隔离单位，只有 companyId 无法区分人。</p>
   */
  private Owner currentOwnerOrNull() {
    Long companyId = CurrentUserHolder.currentCompanyId();
    String username = CurrentUserHolder.currentUsername();
    if (companyId == null || username == null || username.isBlank()) {
      return null;
    }
    return new Owner(companyId, username);
  }

  /** 写路径专用：拿不到归属就留审计并拒绝。 */
  private Owner requireOwner(String action) {
    Owner owner = currentOwnerOrNull();
    if (owner == null) {
      auditService.log(CurrentUserHolder.currentUsername(), "ACCESS_DENIED", AUDIT_TARGET_TYPE, "-",
          "登录态缺少归属单位或账号，拒绝" + action);
      throw new IllegalStateException("无法确定当前账号的归属单位，请重新登录后再" + action);
    }
    return owner;
  }

  /** 记忆的归属：单位 + 账号，成对使用，不允许拆开。 */
  private record Owner(Long companyId, String username) {
  }
}
