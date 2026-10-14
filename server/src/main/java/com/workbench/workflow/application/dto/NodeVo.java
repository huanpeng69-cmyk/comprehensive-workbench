package com.workbench.workflow.application.dto;

/** 管理端：单个节点的当前配置。 */
public record NodeVo(
    Long id,
    Integer seq,
    String nodeType,
    String name,
    String assigneeType,
    String assigneeExpr,
    String multiType,
    String conditionExpr,
    String description) {
}
