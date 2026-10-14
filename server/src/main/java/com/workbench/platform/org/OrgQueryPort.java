package com.workbench.platform.org;

import java.util.Optional;

/**
 * 组织单元查询端口：平台内核向业务模块暴露的只读接口。
 *
 * <p>实现（适配器）收在 {@code com.workbench.platform.identity}，是唯一允许依赖
 * {@code identity.repository} 的地方；业务模块只依赖本接口，不再 import identity 的仓储或实体。</p>
 */
public interface OrgQueryPort {

  /** 按 id 查组织单元。id 为 null 时返回 empty（调用方未分配项目部）。 */
  Optional<OrgUnitRef> findById(Long id);

  /**
   * 按「单位 + 名称」精确查首个组织单元。name 为空白或 companyId 为 null 时返回 empty。
   *
   * <p>阶段 2 起组织单元名称<b>不再全局唯一</b>（各单位可各建同名「天河项目部」等下级单位），
   * 因此查询必须带单位维度，禁止跨单位猜测（ADR-M008）。</p>
   */
  Optional<OrgUnitRef> findFirstByNameAndCompanyId(String name, Long companyId);
  /**
   * 取第一家单位（{@code type='COMPANY'} 中 id 最小的那家）。
   *
   * <p>用途：启动期种子数据没有登录态，需要一个确定的归属单位锚点。口径与
   * {@code V10__multi_company.sql} 第 3 节回填一致：按 {@code type='COMPANY'} 取最小 id，
   * <b>禁止按公司名反查</b>（ADR-M008、方案 §9.2）。</p>
   *
   * <p>库里没有任何单位时返回 empty：调用方必须跳过写库，不得退化成不写 company_id。</p>
   */
  Optional<OrgUnitRef> findFirstCompanyUnit();
}
