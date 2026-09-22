package com.workbench.workflow.application.dto;

/** 流程定义摘要：供管理端展示已有流程。 */
public record ProcessDefinitionVo(
    Long id,
    String code,
    String name,
    String bizType,
    Integer version) {
}
