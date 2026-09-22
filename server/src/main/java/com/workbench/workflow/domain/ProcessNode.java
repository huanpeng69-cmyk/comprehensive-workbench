package com.workbench.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 流程节点定义。nodeType: APPROVAL 审批 / CC 抄送（自动完成）/ END 结束。
 * assigneeType: USER 指定用户 / ROLE 角色 / PROJECT_LEADER 项目部负责人 / INITIATOR 发起人。
 * multiType: SINGLE 单人 / PARALLEL 会签（全部同意才流转）。
 * conditionExpr 为空表示无条件；支持 SpEL，变量来自流程变量（如 level、amount）。
 */
@Entity
@Table(name = "process_node")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessNode {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long definitionId;

  @Column(nullable = false)
  private Integer seq;

  @Column(nullable = false, length = 16)
  private String nodeType;

  @Column(nullable = false, length = 64)
  private String name;

  @Column(length = 16)
  private String assigneeType;

  @Column(length = 128)
  private String assigneeExpr;

  @Column(length = 16)
  private String multiType;

  @Column(length = 256)
  private String conditionExpr;
}
