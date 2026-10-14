package com.workbench.platform.datascope;

/**
 * 数据范围判定的唯一入口：业务模块不得再自己拼「取当前用户角色 → 查角色表 → 看 dataScope」
 * 这套逻辑（阶段 1 之前 hr / leave / condolence / party / salary 各写了一份重复实现）。
 *
 * <p>落两档（{@code PROJECT} / {@code COMPANY}）：{@code COMPANY} 档在阶段 2 起语义就是
 * 「当前用户所属单位（{@code company_id}）的全部数据」。阶段 1 曾预留第三档
 * {@code COMPANY_TREE}（本公司及下属单位），阶段 2 落地 {@code company_id} 后它与
 * {@code COMPANY} 等价，故不再单列。</p>
 *
 * <p><b>调用约定</b>（现有语义，不得放松）：判定不通过时由<b>调用方</b>写
 * {@code ACCESS_DENIED} 审计并抛 {@link IllegalStateException}——审计的 targetType / 文案
 * 各模块不同（EMPLOYEE / LEAVE / CONDOLENCE / PARTY_FEE / SALARY），所以留在调用方。</p>
 */
public interface DataScopeResolver {

  /**
   * 当前用户是否具备全公司数据范围：任一<b>启用</b>角色的 dataScope 为 {@code COMPANY} 即视为是；
   * 未登录、无角色、角色全部未启用一律为 false。
   */
  boolean hasCompanyScope();

  /** 当前用户所属项目部 id（来自 JWT 的 projectId），null 表示未指定。 */
  Long currentProjectId();

  /**
   * 当前用户的数据归属单位 id（来自 JWT 的 {@code companyId}，即 {@code org_unit.type='COMPANY'} 行）。
   *
   * <p>null 表示未指定（阶段 2 之前签发的旧 token、不带单位的系统 token）。调用方必须显式处理
   * null：<b>查询侧</b>按「查不到任何数据」处理，<b>写入侧</b>按「无法确定数据归属单位」拒绝，
   * 不得退化成不过滤或落到任意一家公司。</p>
   */
  Long currentCompanyId();

  /**
   * 能否操作指定项目部的记录：全公司数据范围放行；否则要求本人 projectId 非空且与记录一致。
   * 记录本身未归属项目部（{@code recordProjectId == null}）时只有全公司数据范围可操作。
   *
   * <p>阶段 2 起调用方必须<b>先</b>过 {@link #canAccessCompany(Long)}：本方法只在「同一单位内」
   * 区分 COMPANY / PROJECT 两档，不负责跨单位隔离。</p>
   */
  boolean canAccessProject(Long recordProjectId);

  /**
   * 能否操作指定单位（{@code company_id}）的记录：当前用户 companyId 非空且与记录一致才放行。
   *
   * <p>阶段 2 新增，用于给「全公司数据范围」加单位边界：COMPANY 档的语义是「<b>本公司</b>全部数据」，
   * 不是「全库全部数据」。故 {@code recordCompanyId} 为 null（历史脏数据）或当前用户 companyId 为 null
   * （旧 token / 系统 token）一律拒绝，不得退化成放行。本方法是纯委托（不查角色表）。</p>
   */
  boolean canAccessCompany(Long recordCompanyId);
}
