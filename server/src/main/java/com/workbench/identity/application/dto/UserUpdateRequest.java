package com.workbench.identity.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 更新成员请求。password 留空则不修改密码。 */
public record UserUpdateRequest(
    @NotBlank @Size(max = 64) String name,
    @Size(max = 120) String password,
    @Size(max = 20) String phone,
    @Size(max = 16) String employmentType,
    Long projectId,
    List<String> roleCodes,
    Boolean enabled
) {
}
