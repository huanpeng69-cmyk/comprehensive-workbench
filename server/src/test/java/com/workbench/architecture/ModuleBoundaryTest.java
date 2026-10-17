package com.workbench.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 中台化回归基线（G8）：模块边界的现状快照。
 *
 * <p>G8 记录的不合规现状是「6 个业务模块（condolence/hr/leave/party/salary/workflow）直接 import
 * {@code com.workbench.identity.repository}}」。阶段 1 抽出平台内核（{@code platform/org} 端口 +
 * {@code platform/identity} 适配层 + {@code platform/datascope} 数据范围判定）后，业务侧已全部改走端口，
 * 因此这里把旧白名单删掉，改成直接禁止，并新增一条规则把例外锁死成「只有 platform 适配层可以直连
 * identity 仓储」。本测试的作用不变：<b>锁住现状 + 任何新增越界立刻变红</b>。</p>
 *
 * <p>新增例外必须改 {@link #REPOSITORY_ACCESS_EXCEPTIONS} 并写明理由（决策记录见 ADR-M007）。</p>
 */
@AnalyzeClasses(packages = "com.workbench", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

  /** 全部模块根包。新增模块必须登记进这里，否则边界规则对它不生效。 */
  private static final List<String> ALL_MODULES = List.of(
      "ai", "audit", "attachment", "codegen", "common", "condolence", "hr", "identity", "kb", "kbproxy",
      "leave", "memory", "notice", "party", "platform", "salary", "security", "workflow");

  /** 底层模块：不得依赖任何业务模块。 */
  private static final List<String> FOUNDATION_MODULES = List.of("audit", "common", "security");

  /** 平台内核模块：只允许依赖底层模块与 identity（identity 仅经适配层访问）。 */
  private static final String PLATFORM_MODULE = "platform";
  private static final Set<String> PLATFORM_ALLOWED_MODULES =
      Set.of("audit", "common", "security", "identity");

  /**
   * 「模块 → 允许它直连的其它模块 repository 包」例外表。
   *
   * <p>阶段 1 之前这里是 6 个业务模块的 G8 白名单（已清空）。现在只剩一条：
   * {@code platform.identity} 的三个适配器包装 identity 仓储，是业务模块访问 identity 的<b>唯一</b>收口。
   * 要新增例外，先改这里并说明理由，不允许散落在业务模块里。</p>
   */
  private static final Map<String, Set<String>> REPOSITORY_ACCESS_EXCEPTIONS =
      Map.of(PLATFORM_MODULE, Set.of("identity"));

  @ArchTest
  static final ArchRule 模块之间不得直连对方仓储层 = crossModuleRepositoryAccess();

  @ArchTest
  static final ArchRule 底层模块不得依赖业务模块 =
      noClasses().that().resideInAnyPackage(patternsOf(FOUNDATION_MODULES))
          .should().dependOnClassesThat().resideInAnyPackage(
              patternsOf(ALL_MODULES.stream()
                  .filter(m -> !FOUNDATION_MODULES.contains(m))
                  .toList()));

  @ArchTest
  static final ArchRule 平台内核不得依赖业务模块 =
      noClasses().that().resideInAnyPackage(patternsOf(List.of(PLATFORM_MODULE)))
          .should().dependOnClassesThat().resideInAnyPackage(
              patternsOf(ALL_MODULES.stream()
                  .filter(m -> !PLATFORM_ALLOWED_MODULES.contains(m) && !m.equals(PLATFORM_MODULE))
                  .toList()));

  /**
   * 把「唯一例外」锁死：除 identity 模块自身与 platform 适配层外，任何类不得依赖 identity.repository。
   *
   * <p>本规则不依赖 {@link #ALL_MODULES} 的登记，因此新建的模块根包若直连 identity 仓储同样会变红。</p>
   */
  @ArchTest
  static final ArchRule 只有平台适配层可直连身份仓储 =
      noClasses().that().resideInAPackage("com.workbench..")
          .and().resideOutsideOfPackages("com.workbench.identity..", "com.workbench.platform..")
          .should().dependOnClassesThat().resideInAPackage("com.workbench.identity.repository..");

  private static String[] patternsOf(List<String> modules) {
    return modules.stream().map(m -> "com.workbench." + m + "..").toArray(String[]::new);
  }

  /**
   * 对每个模块生成一条规则：不得依赖「其他模块的 repository 包」。
   * {@link #REPOSITORY_ACCESS_EXCEPTIONS} 里登记的模块额外豁免指定模块。
   */
  private static ArchRule crossModuleRepositoryAccess() {
    ArchRule composite = null;
    for (String module : ALL_MODULES) {
      List<String> banned = new ArrayList<>();
      for (String other : ALL_MODULES) {
        if (!other.equals(module)) {
          banned.add("com.workbench." + other + ".repository..");
        }
      }
      for (String allowed : REPOSITORY_ACCESS_EXCEPTIONS.getOrDefault(module, Set.of())) {
        banned.remove("com.workbench." + allowed + ".repository..");
      }
      ArchRule rule = noClasses().that().resideInAPackage("com.workbench." + module + "..")
          .should().dependOnClassesThat().resideInAnyPackage(banned.toArray(String[]::new))
          .allowEmptyShould(true);
      composite = composite == null ? rule : CompositeArchRule.of(composite).and(rule);
    }
    return composite;
  }
}
