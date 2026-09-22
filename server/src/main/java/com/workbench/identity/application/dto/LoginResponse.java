package com.workbench.identity.application.dto;

import java.util.List;

public record LoginResponse(
    String token,
    Long userId,
    String username,
    String name,
    Long projectId,
    String projectName,
    List<String> roles) {
}
