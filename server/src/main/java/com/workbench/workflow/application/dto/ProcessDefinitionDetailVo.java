package com.workbench.workflow.application.dto;

import java.util.List;

/** 管理端：流程定义完整配置（含全部节点）。 */
public record ProcessDefinitionDetailVo(
    Long id,
    String code,
    String name,
    String bizType,
    Integer version,
    Boolean enabled,
    String description,
    int nodeCount,
    List<NodeVo> nodes) {
}
