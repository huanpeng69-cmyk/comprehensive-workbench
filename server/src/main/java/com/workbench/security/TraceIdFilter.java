package com.workbench.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 全链路跟踪入口（治理底座 P0）：为每个请求建立唯一链路 id。
 *
 * <p>职责三件，都在 {@code JwtAuthFilter} 之前完成，保证后续所有日志/审计都能拿到 id：
 * <ol>
 *   <li><b>取值</b>：优先沿用调用方传入的 {@code X-Request-Id}（前端 {@code http.ts} 注入，
 *       便于浏览器端就能把一次交互与后端日志对上）；缺省时生成新 id。</li>
 *   <li><b>绑定</b>：写入 MDC（{@code TraceContext.MDC_KEY}），同步 appender 的日志自动带上。</li>
 *   <li><b>回传</b>：把 id 写进响应头 {@code X-Request-Id}，前端报错可原样回带。</li>
 * </ol>
 * </p>
 *
 * <p><b>不塞 SecurityContext</b>：如 {@link TraceContext} 注释所述，{@code details} 被
 * {@code CurrentUserHolder} 的 {@code instanceof Claims} 契约锁死，链路 id 放 MDC，二者互不干扰。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

  /** 请求/响应头的链路 id 名称。 */
  public static final String HEADER = "X-Request-Id";

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    String incoming = request.getHeader(HEADER);
    String traceId = (incoming == null || incoming.isBlank()) ? TraceContext.generate() : incoming;
    String prev = TraceContext.bind(traceId);
    try {
      response.setHeader(HEADER, traceId);
      filterChain.doFilter(request, response);
    } finally {
      // 请求结束必须还原/清空 MDC，否则线程池复用会把本条请求的 id 串到下一个请求。
      TraceContext.clear(prev);
    }
  }
}