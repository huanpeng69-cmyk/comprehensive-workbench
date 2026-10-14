package com.workbench.identity.application.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求。
 *
 * <p>阶段 2 起用户名只在单位内唯一（{@code (company_id, username)} 复合唯一，ADR-M006），
 * 故 {@code companyId} 是可选定位条件：三家单位账号不重名时可以省掉（前端本期不传，
 * 行为与改造前一致）；一旦重名则必须显式指定，否则由 AuthService 给出可读错误。</p>
 */
public record LoginRequest(
    @NotBlank(message = "请输入用户名") String username,
    @NotBlank(message = "请输入密码") String password,
    /** 数据归属单位 id（org_unit.type='COMPANY' 行），可空。 */
    Long companyId) {
}
