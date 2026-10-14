package com.workbench.identity.api;

import com.workbench.common.Result;
import com.workbench.identity.application.UserService;
import com.workbench.identity.application.dto.RoleResponse;
import com.workbench.identity.application.dto.UserCreateRequest;
import com.workbench.identity.application.dto.UserResponse;
import com.workbench.identity.application.dto.UserUpdateRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 成员（系统登录账号）管理接口。仅 SYSTEM_ADMIN 可访问（类级 @PreAuthorize 把关）。
 */
@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class UserController {

  private final UserService userService;

  public UserController(UserService userService) {
    this.userService = userService;
  }

  @GetMapping
  public Result<List<UserResponse>> list(
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Long projectId,
      @RequestParam(required = false) String roleCode) {
    return Result.success(userService.listUsers(keyword, projectId, roleCode));
  }

  @GetMapping("/roles")
  public Result<List<RoleResponse>> roles() {
    return Result.success(userService.listRoles());
  }

  @GetMapping("/{id}")
  public Result<UserResponse> get(@PathVariable Long id) {
    return Result.success(userService.getUser(id));
  }

  @PostMapping
  public Result<UserResponse> create(@Valid @RequestBody UserCreateRequest req) {
    return Result.success(userService.createUser(req));
  }

  @PutMapping("/{id}")
  public Result<UserResponse> update(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest req) {
    return Result.success(userService.updateUser(id, req));
  }

  @DeleteMapping("/{id}")
  public Result<Void> delete(@PathVariable Long id) {
    userService.deleteUser(id);
    return Result.success(null);
  }

  @PostMapping("/{id}/password")
  public Result<Void> resetPassword(@PathVariable Long id, @RequestBody Map<String, String> body) {
    userService.resetPassword(id, body.get("password"));
    return Result.success(null);
  }
}
