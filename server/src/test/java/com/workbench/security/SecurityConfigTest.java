package com.workbench.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 未认证响应契约回归测试。
 *
 * <p>2026-10-14 线上故障：JWT 过期后所有 /api 请求返回 Spring 默认的 403 裸错误体
 * （不带 {@code code} 字段），前端 {@code http.ts} 只认 {@code code!=='0'}，于是把
 * 「登录失效」当成普通业务错误吞掉——页面照常渲染、却显示「未配置」，用户看不出要重新登录。</p>
 *
 * <p>锁定的事实：未认证必须是 <b>HTTP 401</b> + 项目统一信封 {@code code="401"}，
 * 前端才能据此清 token 并跳登录。403 必须留给「已登录但无权限」。</p>
 */
class SecurityConfigTest {

  @Test
  void unauthenticatedResponseIsHttp401WithUnifiedEnvelope() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    SecurityConfig.writeUnauthorized(response, new ObjectMapper());

    assertEquals(401, response.getStatus(), "未认证必须是 401，不能沿用 Spring 默认的 403");
    assertTrue(response.getContentType().startsWith("application/json"),
        "必须是 JSON，否则前端 contentType 判断会走到「服务异常」分支");
    assertEquals(StandardCharsets.UTF_8.name(), response.getCharacterEncoding(),
        "必须显式声明 UTF-8，否则中文提示在部分客户端会乱码");

    var body = new ObjectMapper().readTree(response.getContentAsString());
    assertEquals("401", body.get("code").asText(), "前端靠 code==='401' 识别登录失效");
    assertEquals("登录已过期，请重新登录", body.get("message").asText());
    assertTrue(body.get("data").isNull(), "失败响应 data 必须为 null，与 Result.fail 契约一致");
  }
}
