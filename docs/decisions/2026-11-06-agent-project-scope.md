# Agent 工具的项目范围真判定（ADR-M014）

日期：2026-11-06

## 一句话结论

把 `AgentTool.needsProjectScope()` 从**只写审计的声明**升级为**调用前的真拒绝**：工具声明需要项目范围时，调用者既没有项目部（JWT `projectId`）也没有全公司数据范围（`DataScopeResolver#hasCompanyScope`）就一律 `【无权限】` + `DENIED/403` 审计，不进工具实现；调用者身份在主线程打包成不可变快照 `ToolCaller` 传给工具线程。

## 背景

《手脑分离与边界治理-设计方案》边界三（项目 ↔ 项目）写明：Agent 作为「代客操作者」必须继承发起人的项目范围，不能放大；并标注这是「最该优先做的一条」。改造前的实际状态是：

1. `AgentTool.needsProjectScope()` 只有默认值 `true`，`ToolInvoker` 仅在 `AI_TOOL_CALL` 审计里把它写成 `scope=PROJECT|GLOBAL` 一个字符串——**没有任何判定**，声明为 `PROJECT` 的工具照样会对没有项目上下文的账号执行。
2. `ToolCaller` 之前不存在：身份靠 `invoke(username, roleCodes, ...)` 两个散参数传递，第三批刚修完「工具线程读不到安全上下文导致角色判定恒为空」的缺陷，项目维度当时仍是空白。
3. 唯一在册工具 `query_knowledge_base` 查的是外部知识库、没有项目部概念（已声明 `needsProjectScope()=false`），所以缺口尚未暴露——但一旦接入任何业务数据工具，Agent 立刻成为跨项目部查询的漏洞口。

## 备选方案

- **B1 继续只记审计**：`needsProjectScope` 保持纯声明，范围过滤交给每个工具自己实现。问题是「每个工具自己实现」等于没有强制层，漏一个就是数据越界，且漏了没有任何信号。
- **B2 提前造「保留键注入 args（`_projectId`）」通道**：`ToolInvoker` 往 `args` 里塞 `projectId`，工具自行消费。当前**没有任何工具消费它**，写了只能自己读自己，属过度设计；而且一旦注入，`args` 里就出现模型可伪造的同名键，还要额外防覆盖。
- **B3（选定）真判定 + 透传 + 审计**：`ToolInvoker` 在执行前判定项目上下文，拒绝就返回说明性文本并留 `DENIED/403`；身份维度打包成 `ToolCaller` 显式传入；注入通道等第一个需要项目范围的工具接入时再做。

## 决策与原因

**选 B3。** 三条理由：

1. **判定用「全公司范围 OR 已绑定项目部」，而不是「`projectId` 非空」**。`DataScopeResolver` 的语义里 `COMPANY` 档角色合法地没有 `projectId`（`projectId=null` 表示不做项目部过滤，见 `CurrentUserHolder#currentProjectId` 的 javadoc）。硬按 `projectId` 判会**误拒所有公司级账号**——把安全策略做成了可用性事故。
2. **`companyScope` 必须在主线程算**。`hasCompanyScope()` 要查角色表（`RoleBasedDataScopeResolver`），在工具线程既拿不到安全上下文，又多一次线程内的库访问；因此由 `AgentService#resolveCaller()` 在主线程调一次 `DataScopeResolver`，把结果随快照带走。
3. **身份打包成不可变快照，而不是继续加散参数**。第三批的教训是「判定依据没传到位 → 判定恒为空且静默通过」。散参数每加一个维度要改两处签名，容易漏传；`ToolCaller` 是 record，构造时归一化 `roles`，漏传在类型层面就编不过。

**保守默认值的取舍**：`AgentTool.needsProjectScope()` 默认仍为 `true`（写错方向是「拒绝」而不是「越权」）；未注入 `DataScopeResolver` 时 `companyScope` 取 `false`（同样宁拒不放）。线上唯一在册工具已声明 `false`，因此**本次改动零行为变化**。

**对已批准方案的一处细化，如实记录**：原计划写的「工具执行前用 `DataScopeResolver` 注入当前用户的 `projectIds`」只做了前半句（判定 + 透传），注入通道推迟——理由见 B2，且当前没有消费方。

## 契约与约束（防再犯）

1. **工具线程不得回读安全上下文**。`username / roles / projectId / companyId / companyScope` 全部来自 `ToolCaller`；工具线程里 `CurrentUserHolder` 是空的（ThreadLocal 不跨线程池）。
2. **新增身份维度只改 `ToolCaller` 一处**，不要再往 `invoke` 里加散参数；`ToolInvoker.invoke` 的签名只认 `ToolCaller`。
3. **新工具必须显式声明 `needsProjectScope()`**。默认 `true` 是保守取值；工具确实与项目部无关（如查外部知识库）才写 `false`，否则会被判定层拦下。
4. **拒绝也是审计事件**：项目范围不足走 `auditService.denied(..., "403")`，与角色不足同口径，前端只看得到说明性文本（模型据此改路径）。
5. **`AgentService` 保留包私有 5 参构造器**（resolver = null，`companyScope` 保守取 false），容器侧走 `@Autowired` 的 5 参（含 resolver）——两套构造器都不改 `streamAgent(AgentRequest)` 的对外签名。

## 改动范围（已实施）

| 文件 | 改动 |
|---|---|
| `ai/application/tool/ToolCaller.java`（新） | 不可变身份快照 record；`roles` 在紧凑构造器里归一（null → 空集、复制防外部改动）；`hasProjectContext() = companyScope \|\| projectId != null`；`scopeSummary()` 供审计 |
| `ai/application/tool/AgentTool.java` | `needsProjectScope()` javadoc 从「仅作声明、写入审计」改为**真拒绝**语义，写明默认 `true` 是保守取值、无关工具须显式 `false` |
| `ai/application/tool/ToolInvoker.java` | `invoke(ToolCaller, AgentTool, String, Map, AtomicBoolean)` 替换原 `invoke(String, Set<String>, ...)`；角色判定之后新增项目范围判定（拒绝 + `DENIED/403` + 说明性文本）；`AI_TOOL_CALL` 审计追加 `caller=projectId=…, companyId=…, companyScope=…` |
| `ai/application/AgentService.java` | 新增 `DataScopeResolver` 字段（可 null）；`@Autowired` 构造器加第 5 参、保留包私有 5 参与新增包私有 6 参；新增包级 `resolveCaller()`（主线程取身份 + 算 `companyScope`）；`streamAgent` 调 `resolveCaller()`；`runAgent` 参数由 `username + roles` 收敛为 `ToolCaller` |
| `test/.../ToolInvokerTest.java` | 9 处调用改传 `ToolCaller`（新增 `caller(roles)` 夹具，默认 `projectId=7`）；`StubTool` 增 `withNeedsProjectScope` 且**默认 false**（保持既有用例语义）；新增 3 用例：无项目上下文必拒 + 无项目部也必拒审计、COMPANY 级放行、有 `projectId` 放行 |
| `test/.../ToolCallerTest.java`（新） | 6 用例：`roles` 归一、复制防外部改动、全公司范围算上下文、绑项目部算上下文、两者皆无不算、审计摘要含三维度 |
| `test/.../AgentServiceCallerTest.java`（新） | 4 用例：身份取自 JWT（用户名/角色码去前缀/projectId/companyId）、`companyScope` 取自 resolver、未注入 resolver 时保守 false、未登录时无身份 |

## 回滚路径

纯代码改动、无迁移、无数据变更，回滚即代码回退：

1. 回退上述 4 个主代码文件与 3 个测试文件（`ToolCaller` 与两个新测试类可直接删除）。
2. `AgentService` 回退后 `runAgent` 恢复 `username + roles` 两参；`ToolInvoker.invoke` 恢复旧签名。
3. 无库表/数据影响：`AI_TOOL_CALL` 审计里的 `caller=` 字段回退后不再出现，历史行保留无副作用。

## 验证记录（实测）

- 定向：`mvn -o test -Dtest="ToolInvokerTest,ToolCallerTest,AgentServiceCallerTest,AgentServiceMemoryInjectionTest,ModuleBoundaryTest"` → 全绿（exitCode 0）。
- 全量单元：`mvn -o test` → **240 tests, 0 failures, 0 errors, BUILD SUCCESS**（改造前基线 227；新增 `ToolCallerTest` 6 + `AgentServiceCallerTest` 4 + `ToolInvokerTest` 3 = 13）。
- `ModuleBoundaryTest` 全绿：`ai → platform.datascope` 是**接口依赖**，未触碰 `identity.repository`，边界规则不变。
- 全量含集成：`mvn -o verify`（Docker + Testcontainers 真实 PostgreSQL）→ **BUILD SUCCESS, exitCode 0**；Flyway 迁移至 `20 - ai agent permission`，`SchemaBaselineIT` 23 例、failsafe 合计 30 例全绿。本次无迁移/SQL 变更，该轮仅用于确认 Spring 容器能装配新增的 `DataScopeResolver`（`RoleBasedDataScopeResolver` 是 `@Component`）与 `AgentService` 新构造器。
- 未执行：浏览器端手工验证（本轮未启动 dev server，`/agent` 的 SSE 交互路径未点测）；真实库上「公司级账号调声明了 `needsProjectScope` 的工具」的端到端链路（当前无此类工具接入，判定层由单测覆盖）。

## 遗留（不在本次范围，如实记录）

- **注入通道未做**：`DataScopeResolver#canAccessProject(Long recordProjectId)` 这类**记录级**判定还没有接进工具链，需要第一个「按记录过滤」的业务工具接入时再做（届时还要解决 `args` 保留键的防伪造）。
- **公司内多项目部未展开**：`ToolCaller.projectId` 目前是单值（JWT 里就是一个）。若将来账号可跨多个项目部，`hasProjectContext` 需改为集合判定。
- **取消语义不受本次影响**：`cancelled` 仍在判定之前检查，顺序为「已取消 → 角色 → 项目范围 → 审计 → 执行」。
