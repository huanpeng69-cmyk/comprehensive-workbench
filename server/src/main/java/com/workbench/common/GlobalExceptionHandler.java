package com.workbench.common;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常处理，把业务错误转成 Result 结构。
 *
 * <p><b>未预期异常必须记日志（ADR-M008 约束）</b>：{@link #handleOther(Exception)} 兜住所有
 * 未预期异常，若只 return 不 log，500 在应用日志里完全无痕，线上排障时连异常类型都拿不到
 * （2026-10-14 实测教训：只能临时打开
 * {@code ExceptionHandlerExceptionResolver} 的 DEBUG 才能看到被解析的异常）。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);

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
    return Result.fail("500", "服务异常，请稍后重试");
  }
}
