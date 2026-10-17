package com.workbench.memory.application;

import com.workbench.memory.domain.UserMemory;
import com.workbench.memory.dto.MemoryRecallResult;
import com.workbench.memory.dto.MemoryView;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 把「某用户的一堆记忆」压成「一小块可注入提示词的文本」。
 *
 * <p><b>两件事，各管一段</b>：</p>
 * <ol>
 *   <li><b>检索打分</b>（{@link #score}）：相关度（查询词命中率）× 新鲜度（30 天半衰期）× 重要度。
 *       三项相乘而不是相加，是为了让「三个月没碰过、重要度又低」的记忆自然沉底，
 *       而不是靠人为设阈值剔除。</li>
 *   <li><b>预算内压缩</b>（{@link #build}）：按分数从高到低逐条展开，装不下的压成一行摘要。
 *       这是<b>抽取式</b>压缩——原文照抄 + 截断 + 计数摘要，不调用大模型改写。
 *       理由：压缩发生在「用户提问的同步链路」上，多一次 LLM 往返等于把首字延迟翻倍，
 *       而且改写过的记忆会引入不可追溯的失真。</li>
 * </ol>
 *
 * <p><b>确定性</b>：同样的输入（记忆列表 + 查询 + 预算 + 时刻）永远产出同样的块，
 * 分数并列时按 id 降序兜底，因此本类的输出可以被单元测试逐字断言。</p>
 *
 * <p>本类<b>不做权限判定</b>：调用方（{@code MemoryService}）必须已经把候选集限定在
 * 「当前单位 + 当前账号」内。这里只负责排序与压缩。</p>
 */
@Component
public class MemoryContextBuilder {

  /** 默认最多注入几条。 */
  public static final int DEFAULT_TOP_K = 8;

  public static final int MAX_TOP_K = 30;

  /** 默认 token 预算：约 600 token，占一次对话上下文的很小一部分。 */
  public static final int DEFAULT_TOKEN_BUDGET = 600;

  public static final int MAX_TOKEN_BUDGET = 2000;

  public static final int MIN_TOKEN_BUDGET = 64;

  /** 新鲜度半衰期（天）：30 天后权重降到一半。 */
  static final double HALF_LIFE_DAYS = 30.0;

  /** 相关度与重要度的权重（两者相加为 1）。 */
  static final double RELEVANCE_WEIGHT = 0.6;
  static final double IMPORTANCE_WEIGHT = 0.4;

  /** 单条记忆展开时的正文上限（字符）：防止一条超长记忆吃掉整个预算。 */
  static final int MAX_ITEM_CHARS = 200;

  /** 摘要行最多举几条被省略记忆的例子。 */
  static final int DIGEST_SAMPLE = 3;

  /** 摘要行里每条例子的截断长度。 */
  static final int DIGEST_LABEL_CHARS = 12;

  /** 记忆块的固定表头：既告诉模型这是什么，也明确「不要逐条复述」。 */
  static final String HEADER =
      "【用户长期记忆】\n"
          + "以下是系统为该用户保存的长期记忆，按相关度排序，仅供你参考；与本次问题无关时请忽略，不要逐条复述。\n";

  /** 用默认 topK / 预算召回。 */
  public MemoryRecallResult build(List<UserMemory> candidates, String query) {
    return build(candidates, query, DEFAULT_TOP_K, DEFAULT_TOKEN_BUDGET, LocalDateTime.now());
  }

  /**
   * 召回并压缩。
   *
   * @param candidates  候选记忆（调用方已限定为当前单位 + 当前账号；本方法会再滤掉过期与空正文）
   * @param query       当前话题；为空时退化为「重要度 + 新鲜度」排序
   * @param topK        最多取几条，&lt;=0 用 {@link #DEFAULT_TOP_K}，上限 {@link #MAX_TOP_K}
   * @param tokenBudget 记忆块 token 预算，&lt;=0 用 {@link #DEFAULT_TOKEN_BUDGET}，
   *                    区间 [{@link #MIN_TOKEN_BUDGET}, {@link #MAX_TOKEN_BUDGET}]
   * @param now         判定过期与新鲜度的基准时刻
   * @return 记忆块 + 实际展开的记忆 + 估算 token 数 + 省略条数；无可用记忆时块为空字符串
   */
  public MemoryRecallResult build(
      List<UserMemory> candidates, String query, int topK, int tokenBudget, LocalDateTime now) {
    LocalDateTime moment = now == null ? LocalDateTime.now() : now;
    int budget = clampBudget(tokenBudget);

    List<UserMemory> alive = (candidates == null ? List.<UserMemory>of() : candidates).stream()
        .filter(Objects::nonNull)
        .filter(memory -> memory.getContent() != null && !memory.getContent().isBlank())
        .filter(memory -> !memory.isExpiredAt(moment))
        .toList();
    if (alive.isEmpty()) {
      return MemoryRecallResult.empty();
    }

    List<Scored> ranked = rank(alive, MemoryText.tokenize(query), moment, clampTopK(topK));

    // 逐条展开：每次追加后重新量整块 token 数，超预算就回退（topK ≤ 30，重算成本可忽略）
    List<Scored> used = new ArrayList<>();
    String body = "";
    for (Scored scored : ranked) {
      String candidate = body + formatEntry(scored.memory(), scored.memory().getContent());
      if (MemoryText.estimateTokens(HEADER) + MemoryText.estimateTokens(candidate) > budget) {
        break;
      }
      body = candidate;
      used.add(scored);
    }

    // 极端情况：预算连第一条都装不下。此处硬截断第一条而不是返回空块——
    // 「有记忆却一条都不给」比「给一条截断的」更糟，用户会以为记忆功能坏了。
    if (used.isEmpty()) {
      Scored first = ranked.get(0);
      int availableChars = Math.max(8, budget - MemoryText.estimateTokens(HEADER) - 8);
      body = formatEntry(first.memory(),
          MemoryText.truncateToChars(first.memory().getContent(), availableChars));
      used.add(first);
    }

    List<UserMemory> omitted = ranked.subList(used.size(), ranked.size()).stream()
        .map(Scored::memory)
        .toList();

    String block = HEADER + body + fitDigest(body, omitted, budget);
    List<MemoryView> views = used.stream().map(scored -> MemoryView.of(scored.memory(), moment)).toList();
    return new MemoryRecallResult(block, views, MemoryText.estimateTokens(block), omitted.size());
  }

  /**
   * 省略摘要：尽量放完整摘要，放不下退化成只报条数，再放不下就不放。
   *
   * <p>注意摘要里的条数按<b>压缩后</b>的条数报（{@code omitted.size()}），
   * 而不是「数据库里一共有多少条」——用户看到的是「这次没展开几条」，不是库存。</p>
   */
  private String fitDigest(String body, List<UserMemory> omitted, int budget) {
    if (omitted.isEmpty()) {
      return "";
    }
    String full = "（另有 " + omitted.size() + " 条相关记忆未展开："
        + sample(omitted) + "）\n";
    if (fits(body, full, budget)) {
      return full;
    }
    String brief = "（另有 " + omitted.size() + " 条相关记忆未展开）\n";
    return fits(body, brief, budget) ? brief : "";
  }

  private boolean fits(String body, String suffix, int budget) {
    return MemoryText.estimateTokens(HEADER) + MemoryText.estimateTokens(body + suffix) <= budget;
  }

  private String sample(List<UserMemory> omitted) {
    StringBuilder sb = new StringBuilder();
    int shown = Math.min(DIGEST_SAMPLE, omitted.size());
    for (int i = 0; i < shown; i++) {
      if (i > 0) {
        sb.append("；");
      }
      sb.append(MemoryText.truncateToChars(omitted.get(i).getContent(), DIGEST_LABEL_CHARS));
    }
    if (omitted.size() > shown) {
      sb.append(" 等");
    }
    return sb.toString();
  }

  /** 单条记忆的展开行：类型标签 + 正文。类型标签而不是分组标题，才能在「按分数排序」下保持分组信息。 */
  private String formatEntry(UserMemory memory, String text) {
    String label = memory.getMemoryType() == null ? "记忆" : memory.getMemoryType().label();
    return "- [" + label + "] " + text.trim() + "\n";
  }

  /**
   * 打分：{@code (0.6 × 相关度 + 0.4 × 重要度) × 新鲜度}。
   *
   * <p>相关度 = 查询词在记忆里的命中率（0..1）；重要度归一化到 0..1；
   * 新鲜度按 {@link #HALF_LIFE_DAYS} 天半衰。</p>
   */
  double score(UserMemory memory, Set<String> queryTokens, LocalDateTime now) {
    double relevance = relevance(memory, queryTokens);
    int rawImportance = memory.getImportance() == null ? 0 : memory.getImportance();
    double importance = Math.max(0, Math.min(100, rawImportance)) / 100.0;
    return (RELEVANCE_WEIGHT * relevance + IMPORTANCE_WEIGHT * importance)
        * recency(memory.getUpdatedAt(), now);
  }

  private double relevance(UserMemory memory, Set<String> queryTokens) {
    if (queryTokens.isEmpty()) {
      return 0.0;
    }
    Set<String> memoryTokens = MemoryText.tokenize(memory.getContent());
    if (memoryTokens.isEmpty()) {
      return 0.0;
    }
    int hit = 0;
    for (String token : queryTokens) {
      if (memoryTokens.contains(token)) {
        hit++;
      }
    }
    return (double) hit / queryTokens.size();
  }

  /** 新鲜度：0.5 ^ (已过天数 / 半衰期)。未来时间戳（时钟漂移）按最新处理，不惩罚。 */
  private double recency(LocalDateTime updatedAt, LocalDateTime now) {
    if (updatedAt == null || updatedAt.isAfter(now)) {
      return 1.0;
    }
    double days = Duration.between(updatedAt, now).toMinutes() / 1440.0;
    return Math.pow(0.5, days / HALF_LIFE_DAYS);
  }

  /**
   * 归一化去重 + 按分数排序 + 截到 topK。
   *
   * <p>去重键是「类型 + 归一化正文」：库里已有唯一索引兜底，但同一批候选里仍可能出现
   * 大小写/空白差异造成的重复，这里再收敛一次，避免同一句话在提示词里出现两遍。</p>
   */
  private List<Scored> rank(
      List<UserMemory> memories, Set<String> queryTokens, LocalDateTime now, int topK) {
    Map<String, Scored> best = new LinkedHashMap<>();
    for (UserMemory memory : memories) {
      String key = memory.getMemoryType() + "\u0000" + MemoryText.normalize(memory.getContent());
      double score = score(memory, queryTokens, now);
      Scored previous = best.get(key);
      if (previous == null || score > previous.score()) {
        best.put(key, new Scored(memory, score));
      }
    }
    List<Scored> sorted = new ArrayList<>(best.values());
    // 分数降序、id 降序兜底：并列时结果仍然确定，测试才能逐字断言
    sorted.sort((a, b) -> {
      int byScore = Double.compare(b.score(), a.score());
      return byScore != 0 ? byScore : Long.compare(idOf(b.memory()), idOf(a.memory()));
    });
    return sorted.size() <= topK ? sorted : new ArrayList<>(sorted.subList(0, topK));
  }

  private static long idOf(UserMemory memory) {
    return memory.getId() == null ? Long.MIN_VALUE : memory.getId();
  }

  private static int clampTopK(int topK) {
    if (topK <= 0) {
      return DEFAULT_TOP_K;
    }
    return Math.min(topK, MAX_TOP_K);
  }

  private static int clampBudget(int tokenBudget) {
    if (tokenBudget <= 0) {
      return DEFAULT_TOKEN_BUDGET;
    }
    return Math.max(MIN_TOKEN_BUDGET, Math.min(tokenBudget, MAX_TOKEN_BUDGET));
  }

  /** 记忆 + 它的分数。 */
  private record Scored(UserMemory memory, double score) {
  }
}
