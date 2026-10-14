package com.workbench.workflow.application.dto;

/**
 * 节点输入（管理端保存流程定义时提交）。
 *
 * @param seq           节点顺序，从 1 开始；服务端按 seq 排序后整体替换
 * @param nodeType      APPROVAL 审批 / CC 抄送（自动完成）/ END 结束
 * @param name          节点名称
 * @param assigneeType  USER / ROLE / PROJECT_LEADER / INITIATOR；END 节点可空
 * @param assigneeExpr  审批人表达式：USER 为登录名（逗号分隔多个人）、ROLE 为角色码
 * @param multiType     SINGLE 单人 / PARALLEL 会签；可空
 * @param conditionExpr 条件表达式（SpEL，如 {@code level == 'A'}）；空表示无条件
 * @param description   节点说明：向管理员解释该节点的审批口径
 */
public record NodeInputVo(
    int seq,
    String nodeType,
    String name,
    String assigneeType,
    String assigneeExpr,
    String multiType,
    String conditionExpr,
    String description) {
}
