package com.workbench.workflow.application.dto;

import java.util.List;

/**
 * 流程定义快照（管理端保存与版本回滚共用）。
 *
 * <p>提交时 {@code nodes} 必须是该定义的<b>全部</b>节点——服务端按"先删后插"整体替换，
 * 不做增量合并。这样"哪个节点被删了"从提交内容里就能读出来，审计可回溯。</p>
 *
 * @param code        流程编码，全局唯一（ADR-M002：流程定义由公司统一制定）
 * @param name        流程名称
 * @param bizType     业务类型（发起流程时按它找定义）
 * @param enabled     是否启用
 * @param description 流程说明
 * @param changeNote  本次变更说明，会随版本快照一起存档
 * @param nodes       全部节点，按 seq 排序后落库
 */
public record DefinitionSnapshotVo(
    String code,
    String name,
    String bizType,
    Boolean enabled,
    String description,
    String changeNote,
    List<NodeInputVo> nodes) {
}
