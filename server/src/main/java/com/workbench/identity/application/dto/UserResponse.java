package com.workbench.identity.application.dto;

import java.util.List;

/** 成员对外视图，不含 passwordHash。 */
public record UserResponse(
    Long id,
    String username,
    String name,
    String phone,
    String employmentType,
    Long orgId,
    Long projectId,
    String projectName,
    List<String> roleCodes,
    List<String> roleNames,
    Boolean enabled
) {
}
