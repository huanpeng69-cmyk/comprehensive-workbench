package com.workbench.ai.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workbench.ai.application.AgentService.TrimmedHistory;
import com.workbench.ai.dto.AgentMessage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 上下文压缩的回归：{@link AgentService#trimHistory(List)} 的淘汰顺序与边界。
 *
 * <p>钉住三件事：① 超预算时从<b>最旧</b>的消息开始丢，最近的保留；② 最新一条（本轮提问）
 * 无论多长都保留——丢掉它等于让这轮对话答非所问；③ 被省略的条数如实上报，
 * 供 {@link AgentService#omittedNote(int)} 告诉模型「更早的对话你看不到」。
 */
class AgentServiceHistoryTrimTest {

  private static AgentMessage msg(String role, String content) {
    return new AgentMessage(role, content);
  }

  /** 造一条内容长度为 length 的消息。 */
  private static AgentMessage filler(String role, int length) {
    return msg(role, "a".repeat(length));
  }

  private static int totalChars(List<AgentMessage> list) {
    int sum = 0;
    for (AgentMessage m : list) {
      sum += m == null || m.content() == null ? 0 : m.content().length();
    }
    return sum;
  }

  @Test
  void nullHistoryYieldsEmptyKept() {
    TrimmedHistory trimmed = AgentService.trimHistory(null);

    assertEquals(0, trimmed.kept().size());
    assertEquals(0, trimmed.dropped());
  }

  @Test
  void emptyHistoryYieldsEmptyKept() {
    TrimmedHistory trimmed = AgentService.trimHistory(List.of());

    assertTrue(trimmed.kept().isEmpty());
    assertEquals(0, trimmed.dropped());
  }

  @Test
  void shortHistoryKeptUntouched() {
    List<AgentMessage> history = List.of(msg("user", "你好"), msg("assistant", "你好，有什么可以帮你？"));

    TrimmedHistory trimmed = AgentService.trimHistory(history);

    assertEquals(2, trimmed.kept().size());
    assertEquals(0, trimmed.dropped());
    assertSame(history.get(0), trimmed.kept().get(0));
    assertSame(history.get(1), trimmed.kept().get(1));
  }

  @Test
  void overflowDropsOldestAndKeepsNewest() {
    // 4 条各 20000 字 = 80000 > 60000 预算：只能留下最近 3 条。
    List<AgentMessage> history = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      history.add(filler(i % 2 == 0 ? "user" : "assistant", 20_000));
    }

    TrimmedHistory trimmed = AgentService.trimHistory(history);

    assertEquals(3, trimmed.kept().size());
    assertEquals(1, trimmed.dropped());
    assertTrue(totalChars(trimmed.kept()) <= 60_000);
    // 留下的是「最近」三条，最后一条必须与本轮提问是同一条。
    assertSame(history.get(3), trimmed.kept().get(2));
    assertSame(history.get(1), trimmed.kept().get(0));
  }

  @Test
  void newestMessageSurvivesEvenWhenItAloneExceedsBudget() {
    // 本轮提问本身就超预算：宁可超限，也不能把它丢掉，否则这轮直接答非所问。
    List<AgentMessage> history = List.of(filler("user", 20_000), filler("user", 70_000));

    TrimmedHistory trimmed = AgentService.trimHistory(history);

    assertEquals(1, trimmed.kept().size());
    assertEquals(1, trimmed.dropped());
    assertSame(history.get(1), trimmed.kept().get(0));
  }

  @Test
  void manySmallMessagesTrimmedToBudget() {
    // 100 条 × 1000 字 = 100000 > 60000：应恰好留下最近 60 条。
    List<AgentMessage> history = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      history.add(filler(i % 2 == 0 ? "user" : "assistant", 1_000));
    }

    TrimmedHistory trimmed = AgentService.trimHistory(history);

    assertEquals(60, trimmed.kept().size());
    assertEquals(40, trimmed.dropped());
    assertEquals(60_000, totalChars(trimmed.kept()));
    assertSame(history.get(99), trimmed.kept().get(59));
  }

  @Test
  void nullContentCountsAsZeroAndIsNotDropped() {
    // 内容为空的消息不占预算，不能因为「长度 0」以外的任何理由被丢掉。
    List<AgentMessage> history = new ArrayList<>();
    history.add(msg("user", null));
    history.add(msg("user", "abc"));

    TrimmedHistory trimmed = AgentService.trimHistory(history);

    assertEquals(2, trimmed.kept().size());
    assertEquals(0, trimmed.dropped());
  }

  @Test
  void omittedNoteMentionsDroppedCount() {
    String note = AgentService.omittedNote(40);

    assertTrue(note.contains("40"));
    assertTrue(note.contains("省略"));
  }
}
