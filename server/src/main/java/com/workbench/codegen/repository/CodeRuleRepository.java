package com.workbench.codegen.repository;

import com.workbench.codegen.domain.CodeRule;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 文号规则仓储。 */
public interface CodeRuleRepository extends JpaRepository<CodeRule, Long> {

  Optional<CodeRule> findByCategory(String category);

  /** 后台列表用：按类别码排序，保证展示顺序稳定。 */
  List<CodeRule> findAllByOrderByCategoryAsc();
}
