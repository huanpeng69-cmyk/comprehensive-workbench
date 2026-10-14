package com.workbench.ai;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplate 工厂：统一从环境变量探测代理。
 *
 * <p>关键坑：Java 的 RestTemplate 不会像 curl 那样自动读取 {@code HTTPS_PROXY} / {@code HTTP_PROXY}
 * 环境变量，必须显式 {@code setProxy} 才会走代理。本机出网若依赖代理（企业网 / 沙箱），不设置会导致
 * 所有外部 API（Agnes / DeepSeek / 企业知识库等）直连超时——现象就是「接口连不上 / 知识库暂不可用」。
 *
 * <p>探测到代理就用，没探测到就直连，两种网络环境都能跑：本地直连外网的机器无需任何配置；
 * 走代理的机器只要设好 {@code HTTPS_PROXY} 环境变量即可，无需改代码。
 *
 * <p>代理类型：支持 HTTP 与 SOCKS（本地代理客户端如 Clash / v2rayN 常同时暴露 http 端口与
 * socks 端口，URL 写成 {@code socks5://127.0.0.1:7890} 即按 SOCKS 处理；写成
 * {@code http://127.0.0.1:7890} 则按 HTTP CONNECT 处理）。类型配错会出现
 * "Unable to tunnel through proxy" 或连接超时。
 */
public final class RestTemplateProxySupport {

  private RestTemplateProxySupport() {}

  /**
   * 按 HTTPS_PROXY > HTTP_PROXY（含大小写）顺序探测代理；都没有则返回 {@link Proxy#NO_PROXY}。
   * 可通过 {@code WORKBENCH_AI_NO_PROXY=true} 强制禁用代理（仅当你确认 JVM 能直连外网时使用；
   * 若直连超时，应改回 false 让 JVM 走系统代理）。
   */
  public static Proxy detectProxy() {
    String noProxy = System.getenv("WORKBENCH_AI_NO_PROXY");
    if (isTrue(noProxy)) {
      return Proxy.NO_PROXY;
    }
    String raw = firstNonBlank(
        System.getenv("HTTPS_PROXY"), System.getenv("https_proxy"),
        System.getenv("HTTP_PROXY"), System.getenv("http_proxy"));
    if (raw == null) {
      return Proxy.NO_PROXY;
    }
    try {
      URI uri = URI.create(raw.contains("://") ? raw : "http://" + raw);
      String host = uri.getHost();
      int port = uri.getPort() > 0
          ? uri.getPort()
          : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
      if (host == null || host.isBlank()) {
        return Proxy.NO_PROXY;
      }
      String scheme = uri.getScheme() == null ? "http" : uri.getScheme().toLowerCase();
      Proxy.Type type = scheme.startsWith("socks") ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
      return new Proxy(type, new InetSocketAddress(host, port));
    } catch (Exception e) {
      return Proxy.NO_PROXY;
    }
  }

  /** 当前生效的出网方式（不含任何密钥），用于启动日志与连接失败的错误提示，方便排查。 */
  public static String describeProxy() {
    if (isTrue(System.getenv("WORKBENCH_AI_NO_PROXY"))) {
      return "直连（WORKBENCH_AI_NO_PROXY=true）";
    }
    Proxy p = detectProxy();
    if (p == Proxy.NO_PROXY) {
      return "直连（未检测到 HTTPS_PROXY / HTTP_PROXY）";
    }
    return p.type() + " 代理 " + p.address();
  }

  /** 创建一个带连接/读取超时、并按环境自动走代理的 RestTemplate。 */
  public static RestTemplate create(int connectTimeoutMs, int readTimeoutMs) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(connectTimeoutMs);
    factory.setReadTimeout(readTimeoutMs);
    Proxy proxy = detectProxy();
    if (proxy != Proxy.NO_PROXY) {
      factory.setProxy(proxy);
    }
    System.out.println("[AI proxy] egress=" + describeProxy());
    return new RestTemplate(factory);
  }

  private static boolean isTrue(String v) {
    return v != null && (v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes"));
  }

  private static String firstNonBlank(String... values) {
    for (String v : values) {
      if (v != null && !v.isBlank()) {
        return v;
      }
    }
    return null;
  }
}
