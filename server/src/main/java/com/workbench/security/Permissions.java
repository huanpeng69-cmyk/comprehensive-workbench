package com.workbench.security;

/**
 * 权限点码常量（治理底座 P2）。
 *
 * <p>权限点码是 {@code @PreAuthorize("hasAuthority(...)")} 的唯一判定依据，
 * 也是 {@code sys_permission.code} 与 JWT {@code permissions} claim 的取值。
 * 此前这些字符串字面量散落在各 Controller 的注解里，改名要靠全局搜索，
 * 漏一处就是「注解写对了、库里没这个点」的静默失效。集中到此一处后：
 * <ul>
 *   <li><b>唯一来源</b>：代码侧只从常量引用，不写字面量；</li>
 *   <li><b>可核对</b>：本类列出的每个常量都必须能在迁移里找到同名 {@code sys_permission} 行；</li>
 *   <li><b>位置合规</b>：{@code security} 是 {@code ModuleBoundaryTest} 定义的 FOUNDATION 模块，
 *       业务模块依赖它不违规（与放在 {@code identity.domain} 相比不会放大业务耦合）。</li>
 * </ul>
 * 命名沿用既有的 {@code 域:对象:动作} 口径，值不得随手改——改了要同步迁移与已签发的 token。</p>
 */
public final class Permissions {

  /** 流程定义的后台配置（增删改、版本发布、回滚）。对应 V14 的 sys_permission.code。 */
  public static final String WORKFLOW_DEFINITION_MANAGE = "workflow:definition:manage";

  /**
   * 完整的 SpEL 表达式常量，直接用于 {@code @PreAuthorize}。
   *
   * <p>注解的 value 必须是<b>编译期字符串常量</b>；把权限点码与 hasAuthority 包装拼在一处，
   * 调用方只引用这里，避免「某处注解里写了裸字面量、与常量不一致却编译通过」的错位。</p>
   */
  public static final String HAS_WORKFLOW_DEFINITION_MANAGE =
      "hasAuthority('" + WORKFLOW_DEFINITION_MANAGE + "')";

  /**
   * 通用 Agent 对话入口（{@code POST /api/ai/writing/agent}）的使用准入，对应 V20 的 sys_permission.code。
   *
   * <p>没有写成 {@code hasAnyRole(...)} 的原因：Agent 会按登录身份调用工具，「能不能用」应当是
   * <b>可回收的授权</b>而不是钉死在角色上——收回时只需删一行 sys_role_permission，不必改代码再发版。
   * 上线当天由 {@code DataInitializer} 授给全部既有角色，因此不改变任何人的可用性（ADR-M013）。</p>
   */
  public static final String AI_AGENT_USE = "ai:agent:use";

  /** {@link #AI_AGENT_USE} 的 SpEL 表达式，供 {@code @PreAuthorize} 直接引用。 */
  public static final String HAS_AI_AGENT_USE = "hasAuthority('" + AI_AGENT_USE + "')";

  /**
   * AI 模型配置的「可编辑角色」口径：SYSTEM_ADMIN 与 HR_ADMIN。
   *
   * <p>此前 {@code AiConfigController} 的 {@code hasAnyRole} 与 {@code AiConfigService.CONFIG_EDITABLE_ROLES}
   * 各写一份，改一处忘另一处就会出现「按钮看得见、保存被 403」或「后端放行、按钮消失」。
   * 这里做成编译期常量，两边都引用同一份，从根上消除错位（ADR-M012）。</p>
   */
  public static final String HAS_AI_CONFIG_EDITOR =
      "hasAnyRole('SYSTEM_ADMIN', 'HR_ADMIN')";

  /** AI 配置可编辑角色的角色码，供 Service 侧做「是否可编辑」判定。 */
  public static final java.util.List<String> AI_CONFIG_EDITOR_ROLES =
      java.util.List.of("SYSTEM_ADMIN", "HR_ADMIN");
  private Permissions() {
  }
}