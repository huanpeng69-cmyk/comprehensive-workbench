package com.workbench.platform.codegen;

/**
 * 文号生成端口：平台内核向业务模块暴露的「统一编号」能力（阶段 3，ADR-M009）。
 *
 * <p>业务模块不得自己拼编号、也不得直连 {@code codegen} 的仓储——否则「不重号不跳号」
 * 的承诺会散落到各处，无法统一审计与调整格式。实现见
 * {@code com.workbench.codegen.application.CodeGenService}。</p>
 *
 * <p><b>语义承诺</b>：同一 {@code (category, period)} 下，每次调用返回的序号严格递增且不重复；
 * 失败（抛异常或事务回滚）时不消耗序号——即业务写入失败后重试，不会出现跳号。</p>
 */
public interface CodeGenPort {

  /**
   * 生成一个文号。
   *
   * @param category 类别码（对应 {@code code_rule.category}），如 {@code LEAVE}
   * @param prefix   文号前缀，替换模板里的 {@code {PREFIX}}；可为 null 或空，表示该类别无前缀
   * @return 完整文号，如 {@code QJ-2026-0001}
   * @throws IllegalArgumentException 类别不存在、未启用或模板不合法
   */
  String next(String category, String prefix);
}
