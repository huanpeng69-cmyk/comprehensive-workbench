package com.workbench.salary.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 工资核算配置（应出工日基准 + 薪档标准表），单行存储，data 为 JSON 文本。
 * 属于配置数据而非查询型业务数据，整体读写即可。
 */
@Entity
@Table(name = "salary_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalaryConfig {

  @Id
  private Integer id;

  @Column(nullable = false, columnDefinition = "text")
  private String data;
}
