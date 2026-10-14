package com.workbench.workflow.application.dto;

import java.time.LocalDateTime;

/** 管理端：版本列表条目（不含快照正文，避免列表接口过重）。 */
public record DefinitionVersionVo(
    Integer version,
    String changeType,
    String changeNote,
    String createdBy,
    LocalDateTime createdAt) {
}
