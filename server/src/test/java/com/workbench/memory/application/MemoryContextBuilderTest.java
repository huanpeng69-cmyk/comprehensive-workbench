package com.workbench.memory.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workbench.memory.domain.MemorySource;
import com.workbench.memory.domain.MemoryType;
import com.workbench.memory.domain.UserMemory;
import com.workbench.memory.dto.MemoryRecallResult;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 记忆块构建器回归：过滤（过期/空正文）、打分排序、去重、预算压缩、确定性。
 *
 * <p>本类是纯计算无副作用，所以断言可以落到<b>块文本本身</b>：排序、省略条数、截断痕迹都能逐字验证，
 * 而不是只验「返回了个非空对象」。{@code now} 全部显式传入，避免测试依赖真实时钟。</p>
 */
class MemoryContextBuilderTest {

  /** 固定基准时刻：所有记忆的 updatedAt 都相对它构造，分数因而完全确定。 */
  private static final LocalDateTime NOW = LocalDateTime.of(2025, 3, 10, 9, 0);

  private final MemoryContextBuilder builder = new MemoryContextBuilder();

  private static UserMemory memory(Long id, String content, int importance, LocalDateTime updatedAt) {
    return memory(id, content, importance, updatedAt, MemoryType.SEMANTIC);
  }

  private static UserMemory memory(
      Long id, String content, int importance, LocalDateTime updatedAt, MemoryType type) {
    return UserMemory.builder()
        .id(id)
        .companyId(1L)
        .ownerUsername("alice")
        .memoryType(type)
        .content(content)
        .contentHash(MemoryText.contentHash(content))
        .importance(importance)
        .source(MemorySource.MANUAL)
        .createdAt(updatedAt)
        .updatedAt(updatedAt)
        .build();
  }

  private static int occurrences(String text, String needle) {
    int count = 0;
    int index = text.indexOf(needle);
    while (index >= 0) {
      count++;
      index = text.indexOf(needle, index + needle.length());
    }
    return count;
  }

  // ---------- 过滤 ----------

  @Test
  void nullCandidatesProduceEmptyBlock() {
    MemoryRecallResult result = builder.build(null, "工伤怎么认定", 8, 600, NOW);

    assertEquals("", result.block());
    assertTrue(result.memories().isEmpty());
    assertEquals(0, result.estimatedTokens());
    assertEquals(0, result.omittedCount());
  }

  @Test
  void emptyCandidatesProduceEmptyBlock() {
    MemoryRecallResult result = builder.build(List.of(), "工伤怎么认定", 8, 600, NOW);

    assertEquals("", result.block());
    assertTrue(result.memories().isEmpty());
  }

  @Test
  void blankContentMemoryIsIgnored() {
    UserMemory blank = memory(1L, "   ", 100, NOW);

    MemoryRecallResult result = builder.build(List.of(blank), null, 8, 600, NOW);

    // 空正文会在进块前被滤掉，而不是渲染成「- [语义记忆] 」这样一行没有内容的残行
    assertEquals("", result.block());
    assertTrue(result.memories().isEmpty());
  }

  @Test
  void expiredMemoryIsIgnored() {
    UserMemory expired = memory(1L, "上个月的临时口径", 100, NOW);
    expired.setExpiresAt(NOW.minusSeconds(1));

    MemoryRecallResult result = builder.build(List.of(expired), "临时口径", 8, 600, NOW);

    // 仓储查询已按 SQL 过滤过一遍，这里仍然再滤一次：换成别的调用方也不能把过期记忆注入提示词
    assertEquals("", result.block());
  }

  @Test
  void memoryWithoutExpiryIsAlwaysKept() {
    UserMemory never = memory(1L, "正式口径长期有效", 50, NOW);

    MemoryRecallResult result = builder.build(List.of(never), null, 8, 600, NOW);

    assertTrue(result.block().contains("正式口径长期有效"));
  }

  // ---------- 排序 ----------

  @Test
  void blankQueryRanksImportantMemoryFirstEvenIfOlder() {
    UserMemory important = memory(1L, "汇报要带数据", 90, NOW.minusDays(10));
    UserMemory trivial = memory(2L, "顺手记的一件小事", 10, NOW);

    MemoryRecallResult result = builder.build(List.of(trivial, important), null, 8, 600, NOW);

    // 空查询时相关度全为 0，靠「重要度 × 新鲜度」排序；半衰期 30 天，10 天前的 90 分仍应压过今天的 10 分
    assertTrue(result.block().indexOf("汇报要带数据") < result.block().indexOf("顺手记的一件小事"));
    assertEquals(90, result.memories().get(0).importance());
  }

  @Test
  void queryRelevanceOutranksImportance() {
    UserMemory relevant = memory(1L, "工伤认定要先做事故调查", 50, NOW);
    UserMemory irrelevant = memory(2L, "我喜欢用表格汇报", 90, NOW);

    MemoryRecallResult result = builder.build(List.of(irrelevant, relevant), "工伤认定流程", 8, 600, NOW);

    assertTrue(result.block().indexOf("工伤认定要先做事故调查") < result.block().indexOf("我喜欢用表格汇报"));
    assertEquals(1L, result.memories().get(0).id());
  }

  @Test
  void equalScoresFallBackToIdDescendingForStableOutput() {
    UserMemory older = memory(1L, "甲记忆内容", 50, NOW);
    UserMemory newer = memory(2L, "乙记忆内容", 50, NOW);

    MemoryRecallResult first = builder.build(List.of(older, newer), null, 8, 600, NOW);
    MemoryRecallResult second = builder.build(List.of(newer, older), null, 8, 600, NOW);

    // 分数完全并列时按 id 降序兜底：候选入参顺序不影响结果，块文本才能被逐字断言
    assertTrue(first.block().indexOf("乙记忆内容") < first.block().indexOf("甲记忆内容"));
    assertEquals(first.block(), second.block());
  }

  // ---------- 去重 ----------

  @Test
  void duplicateContentAppearsOnlyOnceAndHighestScoreWins() {
    UserMemory weak = memory(5L, "汇报要带数据", 20, NOW);
    UserMemory strong = memory(6L, "  汇报要带数据  ", 90, NOW);

    MemoryRecallResult result = builder.build(List.of(weak, strong), null, 8, 600, NOW);

    assertEquals(1, result.memories().size());
    assertEquals(90, result.memories().get(0).importance());
    assertEquals(1, occurrences(result.block(), "汇报要带数据"));
  }

  // ---------- 预算与省略摘要 ----------

  @Test
  void topKLimitsHowManyEntriesAreExpanded() {
    List<UserMemory> candidates = List.of(
        memory(1L, "第一条记忆内容", 90, NOW),
        memory(2L, "第二条记忆内容", 80, NOW),
        memory(3L, "第三条记忆内容", 70, NOW),
        memory(4L, "第四条记忆内容", 60, NOW));

    MemoryRecallResult result = builder.build(candidates, null, 2, 600, NOW);

    assertEquals(2, result.memories().size());
    assertTrue(result.block().contains("- [语义记忆] 第一条记忆内容"));
    assertTrue(result.block().contains("- [语义记忆] 第二条记忆内容"));
    assertFalse(result.block().contains("- [语义记忆] 第三条记忆内容"));
    // topK 截掉的不计入 omittedCount：该字段的契约是「因超出 token 预算而未逐条展开」，
    // 预算充裕、整块放得下，所以这里必须是 0。（详见 MemoryRecallResult 的字段说明）
    assertEquals(0, result.omittedCount());
  }

  @Test
  void tightBudgetExpandsWhatFitsAndReportsTheRest() {
    // 预算只够「第一条展开 + 一行简短摘要」：第一条放得下、第二条放不下，
    // 剩下的余量刚好容下「另有 4 条相关记忆未展开」这句。
    int budget = MemoryText.estimateTokens(MemoryContextBuilder.HEADER) + 40;
    List<UserMemory> candidates = List.of(
        memory(1L, "第1条记录：" + "内容".repeat(6), 90, NOW),
        memory(2L, "第2条记录：" + "内容".repeat(6), 80, NOW),
        memory(3L, "第3条记录：" + "内容".repeat(6), 70, NOW),
        memory(4L, "第4条记录：" + "内容".repeat(6), 60, NOW),
        memory(5L, "第5条记录：" + "内容".repeat(6), 50, NOW));

    MemoryRecallResult result = builder.build(candidates, null, 8, budget, NOW);

    assertEquals(1, result.memories().size());
    assertEquals(4, result.omittedCount());
    assertTrue(result.estimatedTokens() <= budget,
        "正常路径（还有条目能装下）必须守住预算，估算值 " + result.estimatedTokens() + " > " + budget);
    assertTrue(result.block().contains("- [语义记忆] 第1条记录"));
    assertFalse(result.block().contains("- [语义记忆] 第2条记录"));
    assertTrue(result.block().contains("另有 4 条相关记忆未展开"));
  }

  @Test
  void oversizedFirstMemoryIsHardTruncatedButViewKeepsFullContent() {
    String longContent = "记".repeat(300);
    UserMemory only = memory(1L, longContent, 50, NOW);

    MemoryRecallResult result = builder.build(List.of(only), null, 8, MemoryContextBuilder.MIN_TOKEN_BUDGET, NOW);

    // 预算连第一条都装不下时的兜底：截断后仍给一条，而不是返回空块让用户以为记忆功能失效。
    // 这一路径刻意允许略微超预算——宁可多十几个 token，也不能"有记忆却一条都不给"。
    assertEquals(1, result.memories().size());
    assertTrue(result.block().contains("…"));
    assertFalse(result.block().contains(longContent), "超长正文不应整段进入提示词");
    assertEquals(longContent, result.memories().get(0).content(), "视图里保留完整正文，截断只发生在注入块里");
  }

  // ---------- 块的形状 ----------

  @Test
  void blockStartsWithHeaderAndTagsEntriesWithTypeLabel() {
    UserMemory semantic = memory(1L, "我在综合办工作", 90, NOW, MemoryType.SEMANTIC);
    UserMemory procedural = memory(2L, "要表格不要套话", 80, NOW, MemoryType.PROCEDURAL);

    MemoryRecallResult result = builder.build(List.of(semantic, procedural), null, 8, 600, NOW);

    assertTrue(result.block().startsWith(MemoryContextBuilder.HEADER));
    assertTrue(result.block().contains("- [语义记忆] 我在综合办工作"));
    assertTrue(result.block().contains("- [程序记忆] 要表格不要套话"));
    assertEquals(MemoryText.estimateTokens(result.block()), result.estimatedTokens());
  }

  @Test
  void missingIdAndFutureTimestampDoNotFailRanking() {
    UserMemory noId = memory(null, "没有 id 的记忆", 50, null);
    UserMemory future = memory(2L, "时钟漂移导致的未来时间戳", 50, NOW.plusDays(3));

    MemoryRecallResult result = builder.build(List.of(noId, future), null, 8, 600, NOW);

    // 两种「异常时间戳」都不惩罚：未来时间戳按最新处理，updatedAt 为空同样按最新处理。
    // 于是两条分数完全并列，落到 id 降序兜底（缺失 id 当作最小 id），关键是都不抛异常。
    assertEquals(2, result.memories().size());
    assertTrue(result.block().contains("没有 id 的记忆"));
    assertTrue(result.block().indexOf("时钟漂移导致的未来时间戳") < result.block().indexOf("没有 id 的记忆"));
  }
}
