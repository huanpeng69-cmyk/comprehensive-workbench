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
 * 流程定义：按业务类型（bizType）区分，支持版本与启用开关。
 */
@Entity
@Table(name = "process_definition")
@Getter
 @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessDefinition {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 32)
  private String code;

  @Column(nullable = false, length = 64)
  private String name;

  @Column(nullable = false, length = 32)
  private String bizType;

  @Column(nullable = false)
  private Integer version;

  @Column(nullable = false)
  private Boolean enabled;
}
