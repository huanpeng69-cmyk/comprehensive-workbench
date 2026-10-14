package com.workbench.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

/**
 * 全局异常处理契约（阶段 2，D2）。
 *
 * <p>锁住两件事：</p>
 * <ol>
 *   <li>各异常类型的 code / HTTP 状态逐字未变（前端按 code 分支提示）；</li>
 *   <li><b>未预期异常必须留下日志</b>——{@link GlobalExceptionHandler#handleOther(Exception)}
 *       早先只 return 500、不写日志，导致 500 在应用日志里完全无痕（2026-10-14 实测，
 *       排障时连异常类型都拿不到）。ADR-M008 已把「不得静默吞异常」写成约束。</li>
 * </ol>
 *
 * <p>「是否记了日志」用一个挂到 {@code ch.qos.logback} 的临时 appender 验证：本例不依赖
 * 具体日志实现，只统计 {@link GlobalExceptionHandler} 发出的 ERROR 级事件数量。</p>
 */
class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void validationErrorReturns400WithFieldMessage() {
    BindingResult bindingResult = new org.springframework.validation.BeanPropertyBindingResult(
        new Object(), "request");
    bindingResult.addError(new FieldError("request", "username", "请输入用户名"));

    Result<Void> result = handler.handleValidation(
        new MethodArgumentNotValidException(null, bindingResult));

    assertEquals("400", result.getCode());
    assertEquals("请输入用户名", result.getMessage());
  }

  @Test
  void accessDeniedReturns403() {
    Result<Void> result = handler.handleAccessDenied(new AccessDeniedException("no"));

    assertEquals("403", result.getCode());
    assertEquals("没有访问权限", result.getMessage());
  }

  @Test
  void notFoundReturns404() {
    Result<Void> result = handler.handleNotFound(new EntityNotFoundException("成员不存在"));

    assertEquals("404", result.getCode());
    assertEquals("成员不存在", result.getMessage());
  }

  @Test
  void unexpectedExceptionReturns500WithGenericMessage() {
    Result<Void> result = handler.handleOther(
        new IllegalStateException("boom: 内部细节不能外泄"));

    assertEquals("500", result.getCode());
    assertEquals("服务异常，请稍后重试", result.getMessage());
    assertNull(result.getData());
  }

  @Test
  void unexpectedExceptionIsLogged() {
    ch.qos.logback.classic.Logger target =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    List<ch.qos.logback.core.Appender<ch.qos.logback.classic.spi.ILoggingEvent>> before =
        appendersOf(target);

    CountingAppender appender = new CountingAppender();
    appender.start();
    target.addAppender(appender);
    try {
      handler.handleOther(new IllegalArgumentException("The given id must not be null"));
    } finally {
      target.detachAppender(appender);
      appender.stop();
    }

    assertEquals(1, appender.errorCount());
    assertEquals("未预期异常，已返回 500：The given id must not be null", appender.lastMessage());
    assertEquals(appendersOf(target), before);
  }

  @Test
  void httpStatusMappingIsUnchanged() {
    ResponseEntity<Result<Void>> validation =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(handler.handleValidation(new MethodArgumentNotValidException(null,
                new org.springframework.validation.BeanPropertyBindingResult(new Object(), "r"))));

    assertEquals(HttpStatus.BAD_REQUEST, validation.getStatusCode());
  }

  /** logback 的 {@code iteratorForAppenders()} 返回 Iterator，这里收成 List 便于断言与复原。 */
  private static List<ch.qos.logback.core.Appender<ch.qos.logback.classic.spi.ILoggingEvent>>
      appendersOf(ch.qos.logback.classic.Logger logger) {
    List<ch.qos.logback.core.Appender<ch.qos.logback.classic.spi.ILoggingEvent>> out =
        new java.util.ArrayList<>();
    logger.iteratorForAppenders().forEachRemaining(out::add);
    return out;
  }

  /** 只数 ERROR 事件的最小 logback appender。 */
  private static final class CountingAppender
      extends ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent> {

    private int errorCount;
    private String lastMessage;

    @Override
    protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) {
      if (event.getLevel() == ch.qos.logback.classic.Level.ERROR) {
        errorCount++;
        lastMessage = event.getFormattedMessage();
      }
    }

    int errorCount() {
      return errorCount;
    }

    String lastMessage() {
      return lastMessage;
    }
  }
}
