package com.workbench.leave.application.dto;

import java.time.format.DateTimeFormatter;

/**
 * 请假记录对外视图。applicant 取显示名（与花名册姓名一致），
 * 供前端列表展示与工资工日勾稽（salary.ts 按姓名匹配）。
 */
public record LeaveVo(
    Long id,
    String applicant,
    String applicantUsername,
    String project,
    String type,
    String start,
    String end,
    Integer days,
    String reason,
    String status,
    String appliedAt) {

  public static String formatAppliedAt(java.time.LocalDateTime t) {
    return t == null ? null : t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
  }
}
