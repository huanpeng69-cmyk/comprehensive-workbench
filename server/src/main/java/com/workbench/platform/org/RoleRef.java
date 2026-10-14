package com.workbench.platform.org;

/**
 * 角色的只读视图。{@code dataScope} 取值 {@code PROJECT}（仅本人项目部）/ {@code COMPANY}（全公司）。
 *
 * <p>{@code enabled} 用基本类型 boolean：{@code sys_role.enabled} 在库中非空，
 * 适配层用 {@code Boolean.TRUE.equals(...)} 兜底，语义与改造前一致（null 视为未启用）。</p>
 */
public record RoleRef(String code, String dataScope, boolean enabled) {
}
