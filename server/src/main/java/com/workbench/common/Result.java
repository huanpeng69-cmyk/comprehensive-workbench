package com.workbench.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 统一接口返回结构。
 */
@Getter
@AllArgsConstructor
public class Result<T> {

  private final String code;
  private final String message;
  private final T data;

  public static <T> Result<T> success(T data) {
    return new Result<>("0", "成功", data);
  }

  public static <T> Result<T> success() {
    return new Result<>("0", "成功", null);
  }

  public static <T> Result<T> fail(String code, String message) {
    return new Result<>(code, message, null);
  }
}
