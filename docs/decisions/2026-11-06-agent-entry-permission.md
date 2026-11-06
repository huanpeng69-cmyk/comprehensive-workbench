# Agent 入口权限点：`ai:agent:use`（ADR-M013）

日期：2026-11-06

## 一句话结论

给通用 Agent 入口 `POST /api/ai/writing/agent` 加一道**资源级权限点** `ai:agent:use`（`@PreAuthorize("hasAuthority(...)")`）：权限点字典由迁移 `V20` 建，角色授权由 `DataInitializer#grantAgentUseToAllRoles()` 在每次启动时幂等补授给**全部现有角色**——上线当天可用性与上线前完全一致，此后按角色收回只需删 `sys_role_permission` 的一行，不必改代码发版。

## 背景

`/api/ai/writing/agent` 是带工具调用的对话入口（当前在册工具 `query_knowledge_base`，走 `ToolInvoker` 执行）。改造前它只要求「已登录」，等价于**任何账号都能借 Agent 间接使用全部已注册工具**；工具侧虽有 `requiredRoles` 声明层（第一批），但入口本身没有准入门槛，也没有任何地方能表达「这个账号不许用 AI 助手」。

治理现状对照（ADR-M012 立的三层口径）：资源级 `@PreAuthorize` 已有先例（流程定义管理 `workflow:definition:manage`），但 AI 侧此前只有「AI 配置编辑」用了 `hasAnyRole`，Agent 入口是空白。缺口有三：

1. **无准入门槛**：任意角色都能驱动工具执行链，工具越加越多时风险单向放大。
2. **无回收手段**：想禁掉某个账号用 AI，只能停用整个账号，颗粒度太粗。
3. **无审计线索**：入口没有「谁用了 AI 助手」的显式标记，只能从 `AI_TOOL%` 审计反推。

## 备选方案

- **A1 继续只有「已登录」**：不改。风险随工具数量线性放大，且无法回收。
- **A2 用 `hasAnyRole(...)` 列角色**：实现最短，但「收回某个角色」必须改代码改注解并发版；角色改名/新角色上线都会漏配。而且角色是组织语义，权限点是能力语义，二者混用会把权限面绑死在组织模型上。
- **A3（选定）新增权限点 `ai:agent:use` + `hasAuthority` + 「迁移建字典、启动期补授」**。理由见下。

## 决策与原因

**选 A3。** 三条理由：

1. **回收成本最低**：`hasAuthority` 的判定只查 JWT `permissions` claim，而该 claim 来自 `sys_role_permission`。收回 = 删一行关联，下一次登录即生效，零发版。
2. **可用性零变化**：上线当天把权限点授给全部既有角色，所有现存账号行为与改动前逐字节一致——这是「不改既有功能」的最直接落地方式。
3. **口径统一**：权限点码只从 `security/Permissions` 引用，与 `workflow:definition:manage` 同源同风格，并新增单测机器核对「`Permissions` 里每个权限点码都能在迁移里找到同名 `sys_permission` 行」，不靠人记。

**授权为什么不在 V20 里（对已批准方案的一处偏离，如实记录）**：原计划是「V20 迁移直接把权限点授予全部现有角色」。实施时核对到两条硬事实，该做法不成立：

- Flyway 迁移阶段 `sys_role` 表**是空的**——角色种子由 `DataInitializer`（演示数据）与演示 Seeder 写入，均在 Flyway 之后。`V14__workflow_definition_permission.sql` 已踩过这个坑并在脚本注释里写明「授权由 DataInitializer 负责」。
- `SchemaBaselineIT` 现有断言是 `select count(*) from sys_role_permission = 0`（Flyway-only 的空库）。迁移里写授权会让这条基线断言与迁移互相矛盾。

因此 V20 只做它自己该做的事（建字典 + 自检恰好 1 行），授权下沉到 `DataInitializer`。**副作用是必须在报告和本 ADR 里显式说明这句**：与 V14 的差别在授予面——V14 只给管理员，可以塞进「新建管理员账号」分支；`ai:agent:use` 要覆盖**已有账号的库**（开发库/生产库 `sys_user` 非空、走不到演示数据分支），所以每次启动遍历角色表补授。

**为什么只给 `/agent` 加、`/generate` 不动**：确认项针对的是「带工具调用的 Agent 入口」。`/generate` 是单轮文本生成，权限口径改变会收窄既有行为，不在本批范围。

## 契约与约束（防再犯）

1. **权限点码只能从 `Permissions` 引用，注解里禁止裸字面量**。新增权限点必须同时改三处：`Permissions` 常量、迁移里的 `sys_permission` 行、`PermissionsMigrationConsistencyTest` 覆盖到的路径（后两者由单测自动核对，漏了会红）。
2. **迁移只建字典，不建授权**。`V20` 的 `do $$` 自检刻意**只**断言权限点行数，不断言授权——与 V14 同因，新装空库里没有角色行。给迁移加「角色已获授权」断言会让新装库直接失败。
3. **`grantAgentUseToAllRoles()` 必须保持包私有**：`run()` 依赖组织单元无法单测，方法本身可被 Mockito 直测（`DataInitializerAgentPermissionTest`）。
4. **建 `Permission` 时必须显式 `.roles(new LinkedHashSet<>())`**：Lombok `@Builder` 忽略字段初始化表达式（编译告警已确认 `Permission.java:58`），不给就是 `null`，随后 `getRoles().add(...)` 直接 NPE。
5. **迁移编号**：本次用 `V20`（当前链尾），`SchemaBaselineIT.EXPECTED_MIGRATIONS=20` 必须同步，否则集成测试红。
6. **回滚必须代码与迁移同退**：只跑 `V20__rollback.sql` 不撤代码，则 `@PreAuthorize` 仍要求 `ai:agent:use` 而库里已无该权限点 → **所有账号调 `/agent` 一律 403**。

## 改动范围（已实施）

| 文件 | 改动 |
|---|---|
| `security/Permissions.java` | 新增 `AI_AGENT_USE = "ai:agent:use"` 与 `HAS_AI_AGENT_USE`，javadoc 写明「为何不用 `hasAnyRole`：授权要可回收」 |
| `ai/api/WritingController.java` | `/agent` 方法加 `@PreAuthorize(Permissions.HAS_AI_AGENT_USE)`；补两个 import；javadoc 说明 403 由 `GlobalExceptionHandler` 返信封、前端已兜住。`/generate` 不动 |
| `db/migration/V20__ai_agent_permission.sql`（新） | 幂等 `insert ... where not exists` 建字典；`do $$` 自检恰好 1 行；注释写明授权归 `DataInitializer` |
| `db/rollback/V20__rollback.sql`（新） | 幂等 `do $$` 判存在后删 `sys_permission` 行；注释写明「必须与代码同退」、cascade 影响、备份命令 |
| `identity/application/DataInitializer.java` | `run()` 末尾调 `grantAgentUseToAllRoles()`；新增包私有方法：取/建权限点，遍历 `roleRepository.findAll()` 补授，`changed` 为真才 save |
| `test/.../DataInitializerAgentPermissionTest.java`（新） | 3 用例：空库补建并授予全部角色、只补缺角色（1 次 save）、已全授时 `never()).save(...)` |
| `test/.../PermissionsMigrationConsistencyTest.java`（新） | 2 用例：锁定 `ai:agent:use` 字面量与 SpEL、反射取 `Permissions` 全部权限点码 + 扫 `classpath:db/migration/*.sql` 逐码核对 |
| `test/.../SchemaBaselineIT.java` | `EXPECTED_MIGRATIONS` 19→20、版本清单补 `"20"`；新增 `agentEntryPermissionIsSeededWithoutGrants()`（字典 1 行、`sys_role_permission` 0 行） |

前端**零改动**：`LoginResponse` 不含 permissions，`web/src/api/aiWriting.ts` 已按 `!res.ok` 读 `message` 兜底，403 会显示后端文案。

## 回滚路径

按顺序执行（**顺序不能反**：先撤代码再删数据，中间不会出现「注解还在、权限点没了」的全员 403 窗口）：

1. 备份（删除不可逆）：
   `pg_dump -t sys_permission -t sys_role_permission <库名> > permission_before_rollback.sql`（与回滚脚本注释里的备份命令一致）
2. 撤代码：回退 `WritingController` 的 `@PreAuthorize` 与 `Permissions` 新增常量、`DataInitializer` 的调用与方法、两个新测试类、`SchemaBaselineIT` 的版本号（否则集成测试红）。
3. 撤数据：`psql -f server/src/main/resources/db/rollback/V20__rollback.sql`（cascade 删掉该权限点在 `sys_role_permission` 的关联；其他权限点与角色不受影响）。
4. 已签发的 JWT 不受影响（claim 里带着旧权限点），但**不会再有代码校验它**；重新登录后 claim 里不再有该权限点。

## 影响说明（上线必读）

- **已签发 token 需重新登录才拿到新权限点**。`permissions` claim 在登录时签发、默认有效期内不刷新，因此上线后未重登的会话调 `/agent` 会拿到 403，直到其重新登录。若不能接受，上线时强制全员重登（清 Redis 会话）或临时延长公告期。
- 收回某角色权限：删 `sys_role_permission` 关联 → 该角色用户**下一次登录**起失效（不是立即）。

## 验证记录（实测）

- 定向：`mvn -o -Dtest="DataInitializerAgentPermissionTest,PermissionsMigrationConsistencyTest,ModuleBoundaryTest,AuthServiceTest" test` → `Tests run: 19, Failures: 0, Errors: 0`。
- 全量单元：`mvn -o test` → **226 tests, 0 failures, BUILD SUCCESS**（改造前基线 221，新增 5）。
- 全量含集成：`mvn -o verify`（Docker 29.4.2 在线，Testcontainers 真实 PostgreSQL）→ **BUILD SUCCESS, exitCode 0**；Flyway 日志确认 `Migrating schema "public" to version "20 - ai agent permission"` 与 `V20 ok: 权限点 ai:agent:use 已就位`，`SchemaBaselineIT` 23 用例全绿、failsafe 合计 30 用例全绿。**即 V20 的 SQL 在真实 PG 上跑通了**。
- 未执行：浏览器端手工点击验证（本轮未启动 dev server）；未在已有角色数据的开发库上跑一遍升级（`grantAgentUseToAllRoles` 的「补授既有角色」路径由单测覆盖，真实库升级路径未实跑）。

## 遗留（不在本次范围，如实记录）

- **权限行缺失时 `grantDefinitionManage` 会 NPE**：`Permission.builder()` 未显式给 `roles`（同约束 4），当 `workflow:definition:manage` 行不存在时代码走 `orElseGet` 新建、`getRoles()` 为 `null` → 下一行 `contains` 直接 NPE。当前靠 V14 保证该行存在而没暴露。修法是一行（补 `.roles(new LinkedHashSet<>())`），属既有缺陷，未动。
- `DataInitializer` 第 7/9 行重复 `import Permission`、第 164–169 行有一条悬挂 javadoc（后面紧跟常量的 javadoc）——均为既有问题，本次未动。
- 工具级裁剪（哪些工具按项目部可用、COMPANY 级 `projectId=null` 如何表达）仍是待决策项，`needsProjectScope` 目前只记审计不拒绝。
