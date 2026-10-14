package com.workbench.ai.repository;

import com.workbench.ai.domain.AiConfig;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiConfigRepository extends JpaRepository<AiConfig, Long> {

  /** ai_config 是单行配置表，取 id 最小的一行；表为空时返回 empty。 */
  Optional<AiConfig> findFirstByOrderByIdAsc();
}
