package com.workbench.workflow.application.dto;

/** 流程实例某节点的审批意见（seq 为节点顺序，comment 为审批人填写的意见）。 */
public record NodeCommentRecord(Integer seq, String comment) {
}
