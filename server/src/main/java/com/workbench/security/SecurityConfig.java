package com.workbench.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workbench.common.Result;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

  private final JwtAuthFilter jwtAuthFilter;
  private final TraceIdFilter traceIdFilter;
  private final ObjectMapper objectMapper;

  @Value("${workbench.cors.allowed-origins}")
  private String allowedOrigins;

  public SecurityConfig(JwtAuthFilter jwtAuthFilter, TraceIdFilter traceIdFilter,
      ObjectMapper objectMapper) {
    this.jwtAuthFilter = jwtAuthFilter;
    this.traceIdFilter = traceIdFilter;
    this.objectMapper = objectMapper;
  }


  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // 未认证（无 token / token 过期 / token 非法）统一返回项目信封 + HTTP 401。
        // Spring Security 默认走 Http403ForbiddenEntryPoint，返回的是不带 code 的裸错误体，
        // 前端无法识别为「登录失效」，只能静默失败（页面正常渲染却显示「未配置」）。
        .exceptionHandling(ex -> ex.authenticationEntryPoint((req, res, authEx) -> writeUnauthorized(res, objectMapper)))
        .authorizeHttpRequests(auth -> auth
            // /error 必须放行：异步（SSE）请求出错时容器会转发到 /error，若这里仍要求认证，
            // 错误页会被当成匿名请求拦掉，客户端只能看到 403「没有访问权限」，真实原因（上游
            // 401/超时）被抹掉。放行 /error 仅放开容器错误页这一个末端口，业务接口不受影响。
            .requestMatchers("/api/auth/**", "/actuator/health", "/error").permitAll()
            .anyRequest().authenticated())
        .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
        // 链路 id 必须在鉴权之前建立：JWT 解析、审计、日志都要拿到它。
        // 两者都是 OncePerRequestFilter，同一次请求即使还被 Boot 自动注册一次，
        // 也会被 alreadyFiltered 去重，不会执行两遍。
        .addFilterBefore(traceIdFilter, JwtAuthFilter.class);
    return http.build();
  }

  /**
   * 未认证响应体：项目统一信封，HTTP 401。
   *
   * <p>必须显式写 401 而不是沿用 Spring 默认的 403：前端以 401 作为「登录失效」信号去
   * 清 token + 跳登录；403 保留给「已登录但无权限」（{@code GlobalExceptionHandler}
   * 里的 AccessDeniedException 分支），两者混在一起会把无权限用户误踢下线。</p>
   */
  static void writeUnauthorized(HttpServletResponse response, ObjectMapper objectMapper)
      throws java.io.IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    objectMapper.writeValue(response.getWriter(), Result.fail("401", "登录已过期，请重新登录"));
  }

  private CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    for (String origin : allowedOrigins.split(",")) {
      config.addAllowedOrigin(origin.trim());
    }
    config.addAllowedHeader("*");
    config.addAllowedMethod("*");
    config.setAllowCredentials(true);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }
}
