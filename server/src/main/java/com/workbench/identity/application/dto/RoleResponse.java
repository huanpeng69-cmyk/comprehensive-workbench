package com.workbench.identity.application.dto;

/** 角色选项，供成员管理页角色下拉使用。 */
public record RoleResponse(
    String code,
    String name,
    String dataScope,
    Boolean enabled
) {
}
