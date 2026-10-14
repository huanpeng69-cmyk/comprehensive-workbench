package com.workbench.platform.org;

/**
 * 组织单元的只读视图（id + 名称 + 所属单位 id）。
 *
 * <p>{@code companyId} 供写路径上溯「这条记录属于哪个单位」用（阶段 2 软隔离，ADR-M002）。</p>
 *
 * <p>刻意不暴露 {@code com.workbench.identity.domain.OrgUnit}：业务模块只能通过
 * {@link OrgQueryPort} 拿到本记录，从而保持「业务模块不得依赖 identity 仓储/实体」的模块边界
 * （见 {@code ModuleBoundaryTest}）。</p>
 */
public record OrgUnitRef(Long id, String name, Long companyId) {
}
