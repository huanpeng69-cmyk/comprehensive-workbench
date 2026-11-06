package com.workbench.kbproxy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 流式问答切片规则的最小护栏。
 *
 * <p>只测纯函数 {@link KbProxyController#splitAnswer}：切片只决定传输节奏，
 * 不得增删任何字符——否则前端逐帧拼回来的答案会与知识库原文不一致。</p>
 */
class KbProxyControllerStreamTest {

  @Test
  @DisplayName("空答案不产生任何帧")
  void blankAnswerYieldsNoPiece() {
    assertTrue(KbProxyController.splitAnswer(null).isEmpty());
    assertTrue(KbProxyController.splitAnswer("").isEmpty());
  }

  @Test
  @DisplayName("拼接后的片段必须逐字等于原文")
  void piecesConcatenateBackToOriginal() {
    String answer =
        "报销流程分三步。第一步在系统里提交申请；第二步由直属上级审批！"
            + "第三步财务复核后打款？如果超时未处理，请在企业微信联系财务共享中心，我们会尽快处理。";
    String joined = String.join("", KbProxyController.splitAnswer(answer));
    assertEquals(answer, joined);
  }

  @Test
  @DisplayName("在句末标点后断开，标点跟着前一段")
  void splitsAfterSentenceEnd() {
    List<String> pieces = KbProxyController.splitAnswer("第一句。第二句！第三句？第四句；");
    assertEquals(List.of("第一句。", "第二句！", "第三句？", "第四句；"), pieces);
  }

  @Test
  @DisplayName("换行也作为断点")
  void splitsAfterNewline() {
    assertEquals(List.of("步骤一：提交\n", "步骤二：审批\n"), KbProxyController.splitAnswer("步骤一：提交\n步骤二：审批\n"));
  }

  @Test
  @DisplayName("没有标点时按最大长度硬切")
  void splitsByLengthWithoutPunctuation() {
    String answer = "字".repeat(50);
    List<String> pieces = KbProxyController.splitAnswer(answer);
    assertEquals(3, pieces.size());
    assertEquals(KbProxyController.MAX_PIECE_CHARS, pieces.get(0).length());
    assertEquals(KbProxyController.MAX_PIECE_CHARS, pieces.get(1).length());
    assertEquals(2, pieces.get(2).length());
    assertEquals(answer, String.join("", pieces));
  }

  @Test
  @DisplayName("长度恰好等于上限时只产生一段，且不额外补空段")
  void exactMaxLengthYieldsSinglePiece() {
    String answer = "字".repeat(KbProxyController.MAX_PIECE_CHARS);
    List<String> pieces = KbProxyController.splitAnswer(answer);
    assertEquals(List.of(answer), pieces);
  }

  @Test
  @DisplayName("任何片段都不超过上限")
  void noPieceExceedsMaxLength() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      sb.append((char) ('a' + i % 26));
      if (i % 37 == 0) {
        sb.append('。');
      }
    }
    String answer = sb.toString();
    List<String> pieces = KbProxyController.splitAnswer(answer);
    assertTrue(
        pieces.stream().allMatch(p -> p.length() <= KbProxyController.MAX_PIECE_CHARS),
        "存在超过 " + KbProxyController.MAX_PIECE_CHARS + " 字的片段：" + pieces);
    assertEquals(answer, String.join("", pieces));
  }
}
