package com.workbench.common;

import com.workbench.audit.application.AuditService;
import com.workbench.security.CurrentUserHolder;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 统一异常处理，把业务错误转成 Result 结构。
 *
 * <p><b>未预期异常必须记日志（ADR-M008 约束）</b>：{@link #handleOther(Exception)} 兜住所有
 * 未预期异常，若只 return 不 log，500 在应用日志里完全无痕，线上排障时连异常类型都拿不到
 * （2026-10-14 实测教训：只能临时打开
 * {@code ExceptionHandlerExceptionResolver} 的 DEBUG 才能看到被解析的异常）。</p>
 *
 * <p><b>治理底座 P1（审计）</b>：{@link #handleAccessDenied}（方法级权限拒绝）统一留痕
 * {@code DENIED} 审计，{@link #handleOther}（未预期异常）留痕 {@code FAILURE} 审计，
 * 都只对非 GET 写操作记（列表页轮询 GET 拒绝会打爆审计表）。审计是副作用，<b>绝不能
 * 反过来让业务响应从成功变 500</b>，故所有审计调用 try-catch 吞掉；测试直接
 * {@code new GlobalExceptionHandler()} 时 {@code auditService} 为 null，跳过审计。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @Autowired(required = false)
  private AuditService auditService;

  public GlobalExceptionHandler() {
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public Result<Void> handleValidation(MethodArgumentNotValidException ex) {
    FieldError fieldError = ex.getBindingResult().getFieldError();
    String message = fieldError != null ? fieldError.getDefaultMessage() : "参数校验失败";
    return Result.fail("400", message);
  }

  @ExceptionHandler(AuthenticationException.class)
  @ResponseStatus(HttpStatus.UNAUTHORIZED)
  public Result<Void> handleAuthentication(AuthenticationException ex) {
    return Result.fail("401", "用户名或密码错误");
  }

  @ExceptionHandler(AccessDeniedException.class)
  @ResponseStatus(HttpStatus.FORBIDDEN)
  public Result<Void> handleAccessDenied(AccessDeniedException ex) {
    HttpServletRequest request = currentRequest();
    if (request != null && !"GET".equalsIgnoreCase(request.getMethod())) {
      recordDenied("AUTHZ_REJECTED", request.getRequestURI(), "403");
    }
    return Result.fail("403", "没有访问权限");
  }

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public Result<Void> handleIllegalArgument(IllegalArgumentException ex) {
    return Result.fail("400", ex.getMessage());
  }

  @ExceptionHandler(EntityNotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public Result<Void> handleNotFound(EntityNotFoundException ex) {
    return Result.fail("404", ex.getMessage());
  }

  @ExceptionHandler(IllegalStateException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public Result<Void> handleIllegalState(IllegalStateException ex) {
    return Result.fail("400", ex.getMessage());
  }

  @ExceptionHandler(Exception.class)
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public Result<Void> handleOther(Exception ex) {
    log.error("未预期异常，已返回 500：{}", ex.getMessage(), ex);
    HttpServletRequest request = currentRequest();
    if (auditService != null) {
      try {
        auditService.failed(CurrentUserHolder.currentUsername(), "UNEXPECTED_ERROR",
            "SYSTEM", request == null ? "-" : request.getRequestURI(),
            ex.getClass().getSimpleName(), "500");
      } catch (RuntimeException ignored) {
        // 审计写失败不影响业务响应（见 AuditService REQUIRES_NEW 隔离说明）
      }
    }
    return Result.fail("500", "服务异常，请稍后重试");
  }

  private void recordDenied(String action, String targetId, String resultCode) {
    if (auditService == null) {
      return;
    }
    try {
      auditService.denied(CurrentUserHolder.currentUsername(), action, "API",
          targetId, "方法级权限拒绝", resultCode);
    } catch (RuntimeException ignored) {
      // 审计写失败不影响业务响应
    }
  }

  private static HttpServletRequest currentRequest() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
      return attrs.getRequest();
    }
    return null;
  }
}