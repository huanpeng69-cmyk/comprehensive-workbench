# 治理底座：权限校验口径、审计结果字段、全链路跟踪（ADR-M012）

日期：2026-10-20

## 一句话结论

为现有系统补一层「治理底座」，落地三件事：权限校验口径收敛到「资源级 @PreAuthorize + 范围级 DataScope + 归属级 WriteOwnership」三层且各层有唯一权威；审计日志补 `result / result_code / trace_id` 字段并统一越权/异常留痕；新增基于 `X-Request-Id` 的全链路跟踪，让日志、审计、前端三处 traceId 对齐。

## 背景

系统已有权限管理（RBAC + 数据范围）、审计日志（`audit_log`）和登录/越权留痕，但存在三类治理缺口：

1. **权限校验位置不统一**：10 处 `@PreAuthorize` 用权限点/角色字面量；`AiConfigService` 硬编码 `CONFIG_EDITABLE_ROLES`；`UserService` 完全无 Service 层校验（仅靠 Controller 类级角色拦截）；`UserController` 的类级拦截无法覆盖「SYSTEM_ADMIN 给任意账号提权/改密」的路径。
2. **审计缺结果与链路**：`audit_log` 只有 `username/action/target/detail/createdAt`，没有结果码、IP、链路 id；方法级 `@PreAuthorize` 拒绝、401、500 异常**不留痕**；越权拒绝以 `action="ACCESS_DENIED"` 表达但无 `result` 语义。
3. **无全链路跟踪**：零 MDC、零 traceId；`http.ts` 与 SSE 旁路 `aiWriting.ts` 各写各的 fetch 头；SSE 用私有线程池，日志无法关联到发起请求。

## 备选方案

### 权限
- A1 保持现状（Controller 角色 + 部分权限点 + Service 硬编码角色）。
- A2 收敛为三层，但把权限点常量放 `identity.domain`（靠近实体）。
- **A3 收敛为三层，权限点常量放 `security`（FOUNDATION 底层模块），唯一校验位置是 `@PreAuthorize("hasAuthority(Permissions.X)")`。** 理由见下。

### 审计
- B1 只加 `result` 列，不动审计写入隔离。
- **B2 加 `result/result_code/trace_id` 三列，`AuditService` 新增 `denied/failed` 带结果方法，所有审计调用 try-catch 隔离（审计失败绝不影响业务响应）。** 理由见下。
- B3 用 Spring 事件异步审计——依赖事件总线与异步执行器，超出单体内聚范围。

### 全链路跟踪
- C1 引入 `micrometer-tracing` + Sleuth/OTel——新依赖，且与手建 SSE 线程池不兼容（TaskDecorator 够不着）。
- **C2 自建轻量 `TraceIdFilter` + MDC：链路 id 放 SLF4J MDC（不塞 SecurityContext.details，避免破坏 `instanceof Claims` 契约），SSE 用闭包捕获透传。** 理由见下。

## 决策与原因

**选 A3 / B2 / C2。** 三者共享一个约束：**在既有测试契约与「不改既有调用点」的前提下落地，且不让审计/链路成为业务成功路径的失败源。**

- **A3（权限）**：`security` 是 `ModuleBoundaryTest` 定义的 FOUNDATION 模块（`audit/common/security` 互不依赖业务模块），业务模块依赖它不违规；`identity.domain` 属业务模块，放那儿会放大耦合。权限点字面量从 10 处散落收敛到一处常量类，改权限时只动一个文件。
- **B2（审计）**：既有 `AuditService.log` 签名**全部不动**（59 处调用零修改）。为兼容既有 `action="ACCESS_DENIED"` 编码（如记忆模块 `requireOwner`），`log` 内部按 action 把 `result` 映射为 `DENIED`，使审计表语义自洽且不破坏测试。新增 `denied/failed` 供新代码显式标注。
- **C2（链路）**：链路 id 放 MDC 而非 `SecurityContext.details`——后者被 `JwtAuthFilterTest` 的 `instanceof Claims` 断言与 `CurrentUserHolder` 锁死，动它会破坏现有行为。SSE 的 `AgentService`/`AiWritingService` 是手建 8 线程池，Spring `TaskDecorator` 够不着，故在主线程捕获 traceId、闭包透传、finally 还原。

## 契约与约束（防再犯）

1. **`SecurityContext` 的 `details` 只能放 `Claims`**，链路 id 走 `TraceContext`（MDC），二者互不干扰。新增读 `details` 的代码必须先问「这会破坏 `instanceof Claims` 吗」。
2. **审计是副作用**：任何审计调用（含 `GlobalExceptionHandler` 里）必须 try-catch 吞掉，审计写失败不得让业务成功变 500。
3. **链路 id 命名唯一**：只通过 `TraceContext` 接触（`traceId`），不再各自叫 requestId/correlationId。
4. **前端统一头构造**：SSE 旁路 `aiWriting.ts` 必须复用 `http.ts` 的 `buildHeaders()`，不得各拼一份。
5. **手建线程池 MDC 必须 finally 还原**：否则 8 线程池会把上一个请求的 traceId 串到下一个。
6. **迁移编号**：本机存在未提交的 `V18__ai_config_key_encrypted.sql`，本次治理字段用 `V19__audit_governance_fields.sql`（迁移链 18→19），`SchemaBaselineIT.EXPECTED_MIGRATIONS=19` 与之对齐。
7. **越权拒绝的 `result`**：`action="ACCESS_DENIED"` → `result=DENIED`（`AuditService.resultFor`），是既有编码到新字段的显式映射，不得删。

## 改动范围（已实施）

| 文件 | 改动 |
|---|---|
| `security/TraceContext.java`（新） | MDC 链路 id 工具：get/bind/clear/generate |
| `security/TraceIdFilter.java`（新） | 入口过滤器：读/生成 `X-Request-Id`，绑 MDC，写响应头 |
| `security/SecurityConfig.java` | 注入 `TraceIdFilter`，`addFilterBefore(traceIdFilter, JwtAuthFilter)` |
| `resources/logback-spring.xml`（新） | 日志 pattern 追加 `[traceId=%X{traceId:-}]` |
| `audit/domain/AuditLog.java` | 加 `result/result_code/trace_id` 三列 |
| `audit/application/AuditService.java` | 原 `log` 签名不动；新增 `denied/failed`；`resultFor` 映射；自动写 traceId |
| `common/GlobalExceptionHandler.java` | 方法级权限拒绝记 DENIED、未预期异常记 FAILURE；审计 try-catch 隔离 |
| `db/migration/V19__audit_governance_fields.sql`（新） | 三列 + `idx_audit_log_trace` 索引 |
| `db/rollback/V19__rollback.sql`（新） | 幂等回滚 |
| `schema/SchemaBaselineIT.java` | 迁移数 17→19（含 V18 契约断言） |
| `web/src/api/http.ts` | `traceId()` + `buildHeaders()` 统一注入 `X-Request-Id` |
| `web/src/api/aiWriting.ts` | SSE 旁路复用 `buildHeaders()` |

记忆模块（`MemoryService`）未改调用点：其成功操作经 `log` 记 SUCCESS，越权拒绝经 `action="ACCESS_DENIED"` 由 `resultFor` 映射为 DENIED，前端经 `http.ts` 自动带 traceId，均纳入治理口径。

## 回滚路径

- 代码：逐文件按 Git 提交回退；`AuditService.log` 签名未变，历史调用点天然兼容。
- 迁移：执行 `V19__rollback.sql`（会丢弃新三列数据，执行前先 `pg_dump -t audit_log`）。
- 前端：`http.ts`/`aiWriting.ts` 改回即恢复（`buildHeaders` 只是新增，未改 401/code!=='0' 契约）。

## 遗留（不在本次范围，如实记录）

- `UserService.updateUser` 的「SYSTEM_ADMIN 给自己提权/改密」特殊收权（Q2）未实施——涉及现有测试，需单独确认。
## 第二轮：P2 / P3 与记忆页收口（已实施）

| 文件 | 改动 |
|---|---|
| `security/Permissions.java`（新） | 权限点码 + SpEL 表达式 + AI 可编辑角色，唯一来源 |
| `workflow/api/WorkflowDefinitionController.java` | 3 处 `hasAuthority(...)` 字面量 → `Permissions.HAS_WORKFLOW_DEFINITION_MANAGE` |
| `ai/api/AiConfigController.java` | 2 处 `hasAnyRole(...)` 字面量 → `Permissions.HAS_AI_CONFIG_EDITOR`（`config()` 读接口保持无注解，行为不变） |
| `ai/application/AiConfigService.java` | `CONFIG_EDITABLE_ROLES` 改为引用 `Permissions.AI_CONFIG_EDITOR_ROLES`，消除 Controller/Service 双份口径 |
| `common/Result.java` | 追加 `traceId` 字段（沿用 `TraceContext`）；保留三参构造，`code/message/data` 契约与顺序不变 |
| `web/src/api/http.ts` | 新增 `ApiError extends Error`（携带 traceId）；`ApiResult` 加可选 `traceId` |

**记忆页面（`MemoryCenter.vue`）纳入结论**：记忆页不直连 fetch，全部经 `memory.ts → http.ts`，
因此自动获得 `X-Request-Id` 注入、`ApiError.traceId` 回带、后端 `audit_log.trace_id` 三处对齐；
记忆模块的审计结果口径由 `AuditService.resultFor` 统一给出（成功 SUCCESS / 越权 DENIED）。
记忆侧**零代码改动**，行为与改动前逐字节一致。

**为什么 `config()` 不加注解**：`GET /api/ai/config` 原本对任意登录用户可读（只返回脱敏结果），
加注解会收窄权限、改变现有行为，违反「保持现有功能行为不变」。

## 回滚路径

- 代码：逐文件按 Git 提交回退；`AuditService.log` 签名未变、`Result` 三参构造仍在，历史调用点天然兼容。
- 迁移：执行 `V19__rollback.sql`（会丢弃新三列数据，执行前先 `pg_dump -t audit_log`）。
- 前端：`http.ts`/`aiWriting.ts` 改回即恢复（`buildHeaders`、`ApiError` 均为新增，未改 401/code!=='0' 契约）；
  `Permissions` 是纯常量类，回退到字面量无需数据变更；`Result` 的 `traceId` 是附加字段，旧前端忽略即可。

## 验证记录（实测）

- 后端 `mvn -o -B verify`：**213 单元 + 22 集成全绿，BUILD SUCCESS**（V19 迁移在 Testcontainers 真实 PostgreSQL 上成功应用）。
- 前端 `vue-tsc --noEmit`：通过（exit 0）；`npm run build`：✓ built，`MemoryCenter` 正常产出。
- 未运行：浏览器端到端手工点击验证（本轮未启动 dev server）。

## 遗留（不在本次范围，如实记录）

- `UserService.updateUser` 的「SYSTEM_ADMIN 给自己提权/改密」特殊收权未实施——该类现有校验属 Controller
  类级拦截，加 Service 层收权会改变既有 403/成功语义，需单独确认后再做。
- 跨服务链路（`KbProxyController` 出站头、外部网关透传）未做——仅有单体入口到审计/日志的对齐。
- `AttachmentController`、`UserController` 的角色/登录注解未收敛为常量——它们是 Role 语义而非权限点，
  且收敛无收益；保持原样。
- 前端报错弹窗尚未展示 `traceId`：`ApiError.traceId` 已可用，展示属 UI 文案改动，另开任务。