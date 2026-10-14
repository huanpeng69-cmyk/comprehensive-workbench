package com.workbench.workflow.repository;

import com.workbench.workflow.domain.ProcessDefinition;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessDefinitionRepository extends JpaRepository<ProcessDefinition, Long> {

  /** 按业务类型取最新启用版本。 */
  Optional<ProcessDefinition> findFirstByBizTypeAndEnabledTrueOrderByVersionDesc(String bizType);

  List<ProcessDefinition> findByEnabledTrueOrderByIdAsc();

  /** 按 code 判断定义是否已写入：不能用全表 count（V8 迁移已种 condolence 定义，新库 count 恒 > 0）。 */
  boolean existsByCode(String code);

  /** 管理端：全部定义（含停用），按 id 升序。 */
  List<ProcessDefinition> findAllByOrderByIdAsc();
}
