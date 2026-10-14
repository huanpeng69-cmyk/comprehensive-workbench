package com.workbench.codegen.application;

import com.workbench.codegen.domain.CodeRule;
import com.workbench.codegen.domain.CodeSequence;
import com.workbench.codegen.repository.CodeRuleRepository;
import com.workbench.codegen.repository.CodeSequenceRepository;
import com.workbench.platform.codegen.CodeGenPort;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 文号生成实现（阶段 3，ADR-M009）：「类别 + 年度 + 序号」，不重号、不跳号。
 *
 * <p><b>并发安全的关键</b>：整个取号过程在一个事务里，且用
 * {@link CodeSequenceRepository#findForUpdate} 加行锁（PostgreSQL 的
 * {@code SELECT ... FOR UPDATE}）读取序号行。同一 {@code (category, period)} 的并发请求
 * 因此在数据库层被串行化，第二个请求必须等第一个提交后才能读到新值。</p>
 *
 * <p><b>顺序很重要：先校验模板，再取号</b>。模板非法属于配置错误，若放在取号之后判断，
 * 序号已经被 {@code save} 消耗掉（空号），违背「不跳号」承诺。这条顺序由
 * {@code CodeGenServiceTest#unknownPlaceholderIsRejectedInsteadOfLeakingLiteral} 锁定。</p>
 *
 * <p><b>边界口径</b>：</p>
 * <ul>
 *   <li>类别不存在 / 未启用 → 抛 {@link IllegalArgumentException}（调用方传错 key 属于参数错误，
 *       对外返回 400 而不是 500）；</li>
 *   <li>{@code prefix} 为 null 或空 → 模板里的 {@code {PREFIX}} 替换为空串，由规则自身决定
 *       是否需要分隔符，服务不擅自补字符；</li>
 *   <li>序号表第一行在首次取号时创建（V12 刻意不种序号数据），首号从 1 开始；</li>
 *   <li>序号超出 {@code seq_width} 时不截断——唯一性优先于格式好看。</li>
 * </ul>
 */
@Service
public class CodeGenService implements CodeGenPort {

  /** NONE 周期的固定 period 值：整个生命周期共用一行。 */
  private static final String PERIOD_ALL = "ALL";

  private static final DateTimeFormatter YEAR = DateTimeFormatter.ofPattern("yyyy", Locale.ROOT);

  /** 允许出现的占位符白名单；模板里出现别的 {@code {XXX}} 一律视为配置错误。 */
  private static final Pattern ALLOWED_PLACEHOLDERS =
      Pattern.compile("\\{(PREFIX|PERIOD|YYYY|YY|SEQ)}");

  private final CodeRuleRepository ruleRepository;
  private final CodeSequenceRepository sequenceRepository;

  public CodeGenService(CodeRuleRepository ruleRepository,
      CodeSequenceRepository sequenceRepository) {
    this.ruleRepository = ruleRepository;
    this.sequenceRepository = sequenceRepository;
  }

  @Override
  @Transactional
  public String next(String category, String prefix) {
    if (!StringUtils.hasText(category)) {
      throw new IllegalArgumentException("文号类别不能为空");
    }
    CodeRule rule = ruleRepository.findByCategory(category.trim())
        .orElseThrow(() -> new IllegalArgumentException("文号类别不存在: " + category));
    if (!Boolean.TRUE.equals(rule.getEnabled())) {
      throw new IllegalArgumentException("文号类别已停用: " + category);
    }

    String period = periodOf(rule);
    // 先校验模板再取号：模板非法时还没有消耗任何序号
    validatePattern(rule.getPattern());

    // 行锁读：并发请求在这里排队；拿不到行说明该周期还没发过号，下面新建（由唯一约束兜底并发建行）
    Optional<CodeSequence> existing =
        sequenceRepository.findForUpdate(rule.getCategory(), period);
    long nextSeq = existing.map(CodeSequence::getCurrentValue).map(v -> v + 1).orElse(1L);

    if (existing.isPresent()) {
      existing.get().setCurrentValue(nextSeq);
      sequenceRepository.save(existing.get());
    } else {
      sequenceRepository.save(CodeSequence.builder()
          .category(rule.getCategory())
          .period(period)
          .currentValue(nextSeq)
          .build());
    }

    return render(rule, period, prefix, nextSeq);
  }

  /** YEAR 周期取四位年份；NONE 周期固定 {@value #PERIOD_ALL}。 */
  private String periodOf(CodeRule rule) {
    return CodeRule.RESET_NONE.equals(rule.getResetCycle())
        ? PERIOD_ALL
        : LocalDate.now().format(YEAR);
  }

  /**
   * 校验模板：把白名单占位符全部抠掉后，残留的 {@code {}} 形式内容即为未知占位符。
   *
   * <p>这样做而不是「渲染后检查」——渲染后检查意味着序号已经消耗掉了。</p>
   */
  private void validatePattern(String pattern) {
    if (!StringUtils.hasText(pattern)) {
      throw new IllegalArgumentException("文号模板不能为空");
    }
    String leftover = ALLOWED_PLACEHOLDERS.matcher(pattern).replaceAll("");
    if (leftover.contains("{") || leftover.contains("}")) {
      throw new IllegalArgumentException("文号模板含未知占位符: " + pattern);
    }
  }

  /** 按模板渲染文号。调用前必须已通过 {@link #validatePattern(String)}。 */
  private String render(CodeRule rule, String period, String prefix, long seq) {
    String padded = pad(seq, rule.getSeqWidth());
    String year = period.equals(PERIOD_ALL) ? "" : period;
    return rule.getPattern()
        .replace("{PREFIX}", prefix == null ? "" : prefix)
        .replace("{PERIOD}", period)
        .replace("{YYYY}", year)
        .replace("{YY}", year.length() == 4 ? year.substring(2) : "")
        .replace("{SEQ}", padded);
  }

  /** 序号补零到指定宽度；超出宽度时不截断（保证唯一性优先于格式好看）。 */
  private String pad(long seq, int width) {
    String raw = Long.toString(seq);
    if (raw.length() >= width) {
      return raw;
    }
    return "0".repeat(width - raw.length()) + raw;
  }
}
