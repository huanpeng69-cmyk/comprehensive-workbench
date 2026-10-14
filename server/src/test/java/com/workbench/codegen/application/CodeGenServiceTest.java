package com.workbench.codegen.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.workbench.codegen.domain.CodeRule;
import com.workbench.codegen.domain.CodeSequence;
import com.workbench.codegen.repository.CodeRuleRepository;
import com.workbench.codegen.repository.CodeSequenceRepository;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 文号生成契约（阶段 3，ADR-M009）。
 *
 * <p>锁住的语义：</p>
 * <ol>
 *   <li>序号从 1 开始、严格递增、按 {@code seq_width} 补零；</li>
 *   <li>模板占位符全部替换，含未知占位符时拒绝（不把 {@code {XXX}} 当业务数据发出去）；</li>
 *   <li>类别不存在 / 已停用 → {@link IllegalArgumentException}（对外 400，不是 500）；</li>
 *   <li><b>取号必须走带行锁的 {@code findForUpdate}</b>——这是不重号的唯一实现基础，
 *       改成普通查询会让并发取号拿到同一个号。</li>
 * </ol>
 */
class CodeGenServiceTest {

  private final CodeRuleRepository ruleRepository = mock(CodeRuleRepository.class);
  private final CodeSequenceRepository sequenceRepository = mock(CodeSequenceRepository.class);

  private final CodeGenService service =
      new CodeGenService(ruleRepository, sequenceRepository);

  private static CodeRule rule(String pattern, int width, String resetCycle, boolean enabled) {
    return CodeRule.builder()
        .id(1L)
        .category("LEAVE")
        .name("请假单")
        .pattern(pattern)
        .seqWidth(width)
        .resetCycle(resetCycle)
        .enabled(enabled)
        .build();
  }

  @Test
  void firstCodeStartsAtOneWithPaddingAndYearPeriod() {
    when(ruleRepository.findByCategory("LEAVE"))
        .thenReturn(Optional.of(rule("{PREFIX}-{YYYY}-{SEQ}", 4, CodeRule.RESET_YEAR, true)));
    when(sequenceRepository.findForUpdate("LEAVE", String.valueOf(LocalDate.now().getYear())))
        .thenReturn(Optional.empty());

    String code = service.next("LEAVE", "QJ");

    String expected = "QJ-" + LocalDate.now().getYear() + "-0001";
    assertEquals(expected, code);
    verify(sequenceRepository).save(any(CodeSequence.class));
  }

  @Test
  void secondCodeIncrementsExistingSequenceRow() {
    String year = String.valueOf(LocalDate.now().getYear());
    when(ruleRepository.findByCategory("LEAVE"))
        .thenReturn(Optional.of(rule("{PREFIX}-{YYYY}-{SEQ}", 4, CodeRule.RESET_YEAR, true)));
    when(sequenceRepository.findForUpdate("LEAVE", year))
        .thenReturn(Optional.of(CodeSequence.builder().id(7L).category("LEAVE").period(year)
            .currentValue(41L).build()));

    assertEquals("QJ-" + year + "-0042", service.next("LEAVE", "QJ"));
    verify(sequenceRepository).save(any(CodeSequence.class));
  }

  @Test
  void sequenceWiderThanConfiguredWidthIsNotTruncated() {
    String year = String.valueOf(LocalDate.now().getYear());
    when(ruleRepository.findByCategory("LEAVE"))
        .thenReturn(Optional.of(rule("{SEQ}", 3, CodeRule.RESET_YEAR, true)));
    when(sequenceRepository.findForUpdate("LEAVE", year))
        .thenReturn(Optional.of(CodeSequence.builder().category("LEAVE").period(year)
            .currentValue(999L).build()));

    // 唯一性优先于格式：超出宽度时不截断
    assertEquals("1000", service.next("LEAVE", null));
  }

  @Test
  void noneResetCycleSharesOnePeriodAcrossYears() {
    when(ruleRepository.findByCategory("LEAVE"))
        .thenReturn(Optional.of(rule("{PREFIX}-{PERIOD}-{SEQ}", 4, CodeRule.RESET_NONE, true)));
    when(sequenceRepository.findForUpdate("LEAVE", "ALL"))
        .thenReturn(Optional.of(CodeSequence.builder().category("LEAVE").period("ALL")
            .currentValue(6L).build()));

    assertEquals("QJ-ALL-0007", service.next("LEAVE", "QJ"));
  }

  @Test
  void unknownPlaceholderIsRejectedInsteadOfLeakingLiteral() {
    when(ruleRepository.findByCategory("LEAVE"))
        .thenReturn(Optional.of(rule("{PREFIX}-{BOGUS}", 4, CodeRule.RESET_YEAR, true)));

    assertThrows(IllegalArgumentException.class, () -> service.next("LEAVE", "QJ"));
    // 模板非法时不得消耗序号
    verify(sequenceRepository, never()).save(any());
  }

  @Test
  void blankCategoryIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> service.next("  ", "QJ"));
    verify(ruleRepository, never()).findByCategory(anyString());
  }

  @Test
  void unknownCategoryIsRejectedAsBadRequest() {
    when(ruleRepository.findByCategory("NOPE")).thenReturn(Optional.empty());

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> service.next("NOPE", "QJ"));
    assertEquals("文号类别不存在: NOPE", error.getMessage());
    verify(sequenceRepository, never()).save(any());
  }

  @Test
  void templateIsValidatedBeforeAnySequenceIsConsumed() {
    // 防回归：模板非法若放在取号之后判断，序号已被 save 消耗（空号），违背「不跳号」。
    // 断言的是顺序：既不能碰序号表，也不能写序号表。
    when(ruleRepository.findByCategory("LEAVE"))
        .thenReturn(Optional.of(rule("{PREFIX}-{BOGUS}", 4, CodeRule.RESET_YEAR, true)));

    assertThrows(IllegalArgumentException.class, () -> service.next("LEAVE", "QJ"));

    verify(sequenceRepository, never()).findForUpdate(anyString(), anyString());
    verify(sequenceRepository, never()).save(any());
  }

  @Test
  void disabledCategoryIsRejected() {
    when(ruleRepository.findByCategory("LEAVE"))
        .thenReturn(Optional.of(rule("{SEQ}", 4, CodeRule.RESET_YEAR, false)));

    assertThrows(IllegalArgumentException.class, () -> service.next("LEAVE", "QJ"));
    verify(sequenceRepository, never()).save(any());
  }

  @Test
  void lookupAlwaysGoesThroughTheLockedQuery() {
    String year = String.valueOf(LocalDate.now().getYear());
    when(ruleRepository.findByCategory("LEAVE"))
        .thenReturn(Optional.of(rule("{SEQ}", 4, CodeRule.RESET_YEAR, true)));
    when(sequenceRepository.findForUpdate("LEAVE", year)).thenReturn(Optional.empty());

    service.next("LEAVE", null);

    // 防回归：不重号完全依赖这一行加锁读取
    verify(sequenceRepository).findForUpdate("LEAVE", year);
  }
}
