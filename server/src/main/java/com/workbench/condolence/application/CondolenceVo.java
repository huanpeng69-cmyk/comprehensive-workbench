package com.workbench.condolence.application;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;

/** 慰问金对外视图。workflowInstanceId 供前端跳转流程详情。 */
public record CondolenceVo(
    Long id,
    String applicant,
    String applicantUsername,
    String project,
    String category,
    String reason,
    BigDecimal amount,
    String amountInWords,
    String projectUnionOpinion,
    String companyUnionOpinion,
    String status,
    Long workflowInstanceId,
    String appliedAt) {

  public static String formatAppliedAt(java.time.LocalDateTime t) {
    return t == null ? null : t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
  }
}
