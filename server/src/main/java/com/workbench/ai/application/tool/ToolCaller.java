package com.workbench.ai.application.tool;

import java.util.Set;

/**
 * 一次工具调用的「代客身份」：谁在调、有什么角色、落在哪个项目部与哪家单位。
 *
 * <p>为什么打包成一个不可变快照：这些值都必须在<b>主线程</b>从安全上下文取出——工具跑在线程池里，
 * ThreadLocal 不跨线程，工具线程回读只会拿到空值。塞成五六个散参数会让每新增一个字段就要改两处签名，
 * 也容易漏传；漏传的后果是「判定恒为空」，正是「角色判定恒为空」那个缺陷的同类事故。
 *
 * <p>{@code companyScope} 由 {@code DataScopeResolver} 在主线程算好（要查角色表，不能在工具线程做）；
 * {@code projectId} / {@code companyId} 直接来自 JWT claim，签发时就定了，无需再查库。
 *
 * @param username     发起对话的用户名（安全上下文取下，允许 null：系统调用场景）
 * @param roles        角色码（去掉 {@code ROLE_} 前缀），null 归一为空集
 * @param projectId    项目部 id，null 表示未绑定（全公司级角色合法地为 null）
 * @param companyId    数据归属单位 id，null 表示未指定（旧 token / 系统 token）
 * @param companyScope 是否具备全公司数据范围（{@code data_scope=COMPANY}）
 */
public record ToolCaller(
    String username, Set<String> roles, Long projectId, Long companyId, boolean companyScope) {

  public ToolCaller {
    roles = roles == null ? Set.of() : Set.copyOf(roles);
  }

  /**
   * 是否具备「项目上下文」：全公司数据范围，或已绑定具体项目部。
   *
   * <p>两者都没有时，声明了 {@link AgentTool#needsProjectScope()} 的工具必须拒绝执行——
   * 放行等于让 Agent 成为「跨项目部查询」的漏洞口（README §4.2：所有业务模块按 project 隔离）。
   */
  public boolean hasProjectContext() {
    return companyScope || projectId != null;
  }

  /** 审计用的范围摘要，例：{@code projectId=7, companyId=1, companyScope=false}。 */
  public String scopeSummary() {
    return "projectId=" + projectId + ", companyId=" + companyId
        + ", companyScope=" + companyScope;
  }
}
