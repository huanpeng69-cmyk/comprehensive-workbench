package com.workbench.memory.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记忆文本处理：归一化、内容指纹、轻量分词、token 估算、按长度截断。
 *
 * <p><b>为什么不用分词库</b>：检索打分只需要「查询词是否出现在记忆里」这个粗信号，
 * 引入 IK / jieba 这种中文分词器会带来词典体积、版本升级、启动耗时三笔长期成本，
 * 换来的精度提升在本场景并不关键。这里用「英文数字整词 + 中文二元组」的确定性切分：
 * 中文二元组（bigram）不需要词典，召回率足够，且完全可单测（同样的输入永远同样的输出）。</p>
 */
public final class MemoryText {

  private MemoryText() {
  }

  /** 连续英文/数字 或 连续中文，各自成一段。 */
  private static final Pattern RUN = Pattern.compile("[a-z0-9]+|[\\u4e00-\\u9fff]+");

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  /** 英文虚词：整词出现且属于这些词时不参与打分（它们在任何文本里都高频，纯噪声）。 */
  private static final Set<String> ASCII_STOP_WORDS = Set.of(
      "the", "and", "for", "with", "this", "that", "from", "are", "was", "were", "has", "have",
      "had", "but", "not", "all", "any", "can", "you", "your", "our", "its", "they", "them",
      "then", "than", "will", "would", "should", "could", "about", "into", "what", "when",
      "where", "which", "who", "how", "why", "did", "does", "one", "two", "get", "got", "let",
      "put", "use", "used", "using", "also", "just", "only", "very", "more", "most", "some",
      "such", "each", "other", "out", "own", "see", "way");

  /**
   * 中文虚词（单字）。判定规则是「二元组的两个字<b>都在</b>这个集合里才丢弃」，
   * 所以「喜欢」「记得」这类词不会被误伤，而「的这」「了一」会被丢掉。
   */
  private static final String CJK_STOP_CHARS = "的了是在和与就都而及或把被让给对从到为以这那我你他她它们个些上下中里好吗呢吧啊";

  /** 归一化：去首尾空白、内部连续空白折叠成一个空格、转小写。用于去重指纹与分词。 */
  public static String normalize(String text) {
    if (text == null) {
      return "";
    }
    return WHITESPACE.matcher(text.trim()).replaceAll(" ").toLowerCase(Locale.ROOT);
  }

  /** 内容指纹：归一化后的 SHA-256（十六进制 64 位），与 {@code ux_user_memory_owner_content} 配合去重。 */
  public static String contentHash(String rawContent) {
    String normalized = normalize(rawContent);
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(bytes.length * 2);
      for (byte b : bytes) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16));
        hex.append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 是 JDK 必备算法，走到这里说明运行环境被裁剪过，属于启动期就该暴露的问题。
      throw new IllegalStateException("当前 JVM 不支持 SHA-256，无法为记忆生成内容指纹", e);
    }
  }

  /** 是否中日韩统一表意文字（用于分词与 token 估算）。 */
  public static boolean isCjk(char c) {
    return c >= '\u4e00' && c <= '\u9fff';
  }

  /** 切词：英文数字整词（长度 ≥ 2 且非虚词）+ 中文二元组。返回顺序稳定（LinkedHashSet）。 */
  public static Set<String> tokenize(String text) {
    Set<String> tokens = new LinkedHashSet<>();
    if (text == null || text.isBlank()) {
      return tokens;
    }
    Matcher matcher = RUN.matcher(normalize(text));
    while (matcher.find()) {
      String run = matcher.group();
      if (isCjk(run.charAt(0))) {
        addCjkTokens(run, tokens);
      } else if (run.length() >= 2 && !ASCII_STOP_WORDS.contains(run)) {
        tokens.add(run);
      }
    }
    return tokens;
  }

  private static void addCjkTokens(String run, Set<String> out) {
    int length = run.length();
    if (length == 1) {
      if (!isCjkStopChar(run.charAt(0))) {
        out.add(run);
      }
      return;
    }
    for (int i = 0; i + 1 < length; i++) {
      char first = run.charAt(i);
      char second = run.charAt(i + 1);
      if (isCjkStopChar(first) && isCjkStopChar(second)) {
        continue;
      }
      out.add(run.substring(i, i + 2));
    }
    // 短词组整体也保留一份，让「慰问金标准」这类词组能被整体命中
    if (length <= 4) {
      out.add(run);
    }
  }

  private static boolean isCjkStopChar(char c) {
    return CJK_STOP_CHARS.indexOf(c) >= 0;
  }

  /**
   * 粗略估算 token 数，用于把记忆块压在预算内。
   *
   * <p>口径：一个汉字 ≈ 1 token，其余字符 ≈ 4 字符 1 token（空白也计入其余字符）。
   * 这是**保守估计**（真实分词器对汉字通常略少于 1 token/字），宁少不多，
   * 保证不会因为估算偏乐观而把提示词撑爆。</p>
   */
  public static int estimateTokens(String text) {
    if (text == null || text.isEmpty()) {
      return 0;
    }
    int cjk = 0;
    int others = 0;
    for (int i = 0; i < text.length(); i++) {
      if (isCjk(text.charAt(i))) {
        cjk++;
      } else {
        others++;
      }
    }
    return cjk + (int) Math.ceil(others / 4.0);
  }

  /** 按字符数截断并补省略号；{@code maxChars} 已包含省略号。 */
  public static String truncateToChars(String text, int maxChars) {
    if (text == null) {
      return "";
    }
    String trimmed = text.trim();
    if (trimmed.length() <= maxChars) {
      return trimmed;
    }
    if (maxChars <= 1) {
      return "…";
    }
    return trimmed.substring(0, maxChars - 1) + "…";
  }
}
