# 企业中台化与多单位隔离方向决策（2026-10-13）

> 关联方案：`docs/中台化改造方案.md`。本文件只记录「为什么这么选」，任务拆解在方案文档里。

---

## ADR-M001：中台目标形态与服务对象

### 背景

现有系统面向单个二级单位，`org_unit` 是「公司 / 项目部 / 部门」三级树，`sys_user.project_id` 单一，JWT 只带 `projectId`。用户提出改造为「企业中台」。中台一词在不同语境下指三件不同的事：① 服务本单位多个项目部；② 服务公司多个二级单位/分公司；③ 对外输出为多租户产品。三者对隔离模型、运维成本的要求完全不同，必须先定性再动手。

### 备选方案

1. 只服务本单位，沿用现有 `project_id` 模型，不动数据模型。
2. 服务公司多个二级单位/分公司：引入公司维度，单位之间数据互不可见。
3. 对外输出多租户产品：独立库/独立 schema 硬隔离。

### 决策与原因

**选方案 2。** 用户明确目标是公司多个二级单位/分公司。方案 1 不满足要求；方案 3 的隔离与定制成本远超当前团队规模，且对外输出是尚未确认的商业目标，过早投入属于投机。

同时明确：**中台化不等于微服务化。** 沿用 `综合管理工作台技术方案与架构分层说明.md` 的 ADR-T001（模块化单体 + 分层 + 可插拔适配层）。拆微服务只解决部署与扩容问题，解决不了「能力复用」和「接入成本」——而后者才是当前的真问题。

### 约束

- 不为中台化引入微服务、消息中间件或微前端。
- 目标判据只有一条：新增一个业务域，需要改多少公共代码、改多少前端代码、发几次版。

---

## ADR-M002：多单位隔离模型

### 背景

数据库中存在多处**全局唯一**约束与单行配置结构，单位多于一个时必然冲突：

- `sys_user.username unique`（`V1__init_identity.sql:15`）
- `sys_role.code unique`（`V1__init_identity.sql:28`）
- `process_definition.code unique`（`V2__init_workflow.sql:4`）
- `salary_month.month unique`（`V4__init_salary.sql:7`）
- `salary_record.id` / `perf_record.id` 为 `varchar(64)` 主键（`V4__init_salary.sql:12,55`）
- `salary_config.id integer primary key default 1`（`V4__init_salary.sql:76`，全系统共用一份工资核算配置）

### 备选方案

1. **软隔离**：同库同表，所有业务表与 `sys_*` 表增加 `company_id`，全局唯一约束改复合唯一。
2. **硬隔离**：每个单位独立 schema 或独立数据库，共用一套代码。
3. **折中**：靠 `org_unit.full_path` 前缀推导公司，不落冗余列。

### 决策与原因

**选方案 1（软隔离）。** 理由：

- 一套部署、一份备份、一份迁移脚本，运维成本与现在几乎相同；硬隔离（方案 2）会让 Flyway 迁移、跨单位统计、人员调动都成倍复杂。
- 现有 `org_unit` 顶层已有「公司」节点，公司实体天然存在，只需补齐外键列，无需新建主体。
- 方案 3 每次查询都要 join 组织树取前缀，索引与性能都吃亏，省下的一列不值得。

**关键设计**：软隔离必须做成「可切换」——只要所有表规范带 `company_id`，将来真要抽库，就是按列分片导出，业务代码零改动。这是选软隔离而不选「什么都不做」的前提。

### 约束

- 每份 `V10+` 迁移必须同时提供回滚 SQL，并在执行前 `pg_dump` 全量备份。
- 所有查询强制注入 `company_id` 过滤，与现有 `project_id` 过滤在**同一处**实现，禁止业务模块各自拼条件。
- ~~`salary_config` 单行表必须按 `company_id` 分片，不得保留全局单行。~~ → **更正（阶段 2 实施后）**：**保持全局单行**，三家单位共用一份工资核算配置。依据为方案 §9.1 结论 2（「是否要各单位的流程定义与工资配置 → 不需要，3 个单位共用一套」）优先于本 ADR 的早期设想；`V10__multi_company.sql:28` 已把 `salary_config` 列入「刻意不加 `company_id`」清单，真实数据副本实测其列为 `(id, data)`、1 行、无 `company_id`。若将来某单位要求独立配置，属新需求，须单独开 ADR 并做 `(company_id)` 分片迁移。
- ~~跨单位数据访问（总部看全局）走显式的 `COMPANY_TREE` 数据范围，不得靠「不加过滤条件」实现。~~ → **更正（阶段 2 实施后）**：`COMPANY_TREE` **本期不新增**（理由见方案 §3.4 更正 ①）。每个二级单位把自己视为一个公司根节点，`COMPANY` 档（`company_id = 登录态单位`）已覆盖本期全部用例，**本期不存在"跨公司可见"的档位**；「靠不加过滤条件实现可见」依然禁止。将来若真要「集团公司看全部下级公司」，必须单独开 ADR，并先定义跨公司的上下级口径（当前表结构没有这一口径）。
- `sys_user.username` 的唯一约束改为 `(company_id, username)` 复合唯一，并新增可空的 `external_id` 列预留给统一身份认证（决策与理由见 ADR-M006）。

---

## ADR-M003：角色在单位内生效的实现

### 背景

`sys_user_role` 只有 `(user_id, role_id)`，`sys_role.code` 全局唯一。多单位下，A 单位的 HR_ADMIN 与 B 单位的 HR_ADMIN 是不同的人，不应共享同一个角色实例；但也不应为了隔离而让各单位各建一套角色码（会导致权限清单碎片化）。

### 备选方案

1. 在 `sys_user_role` 增加 `company_id`，授权时记录该角色在哪个单位生效。
2. 保持 `sys_user_role` 不变，由**用户所属公司**决定其角色的生效范围（角色码全局唯一，但只在本公司数据内生效）。
3. `sys_role` 增加 `company_id`，每单位各建一套角色记录。

### 决策与原因

**选方案 2。** 改动最小、语义最直白：权限就是「用户在自己单位内能做什么」，角色码作为权限清单保持全局统一，便于审计与统一维护。方案 1 为「一人多单位任职」预留了空间，但当前没有这个需求，先不做，将来若出现再演进（届时只影响 `sys_user_role` 一张表）。方案 3 会让角色码碎片化，权限清单无法统一治理。

### 约束

- 角色校验一律经 `platform` 的统一权限服务，禁止业务模块直接查 `sys_user_role`。
- 若将来出现「一人跨单位任职」，必须通过 ADR 修订本决策，不得在业务代码里打补丁。

---

## ADR-M004：改造顺序（先测试基线，后多单位）

### 背景

全后端只有 3 个测试文件（`AiConfigServiceTest`、`AiWritingServiceTest`、`ToolRegistryTest`，均在 `ai` 模块），无模块边界约束测试。而多单位改造要改唯一约束、字符串主键、单行配置表，属于不可逆风险最高的那类变更。

### 备选方案

1. 直接开做多单位（阶段 2），先拿收益。
2. 先补回归测试基线（阶段 0），再抽平台内核（阶段 1），最后落多单位（阶段 2）。

### 决策与原因

**选方案 2。** 数据权限逻辑目前在 **5 个模块里重复实现 7 处**：`callerHasCompanyScope()` 4 处（`EmployeeService:38`、`LeaveService:61`、`SalaryService:54`、`CondolenceService:63`），`assertCompanyScope()` 3 处（`EmployeeService:128`、`SalaryService:66`、`PartyFeeService:41`）。多单位改造必须把过滤条件加进**每一处**——如果不在改造前先把这 7 处收敛成 1 处，就等于在改造中同时面对「改错位置」和「改漏位置」两种风险，而且还无法验证。

顺序合理性：测试基线保证「重构后行为不变」可验证 → 平台内核把过滤收敛到唯一入口 → 多单位只改这一处 + 数据模型。

### 约束

- 阶段 0 完成前，不得开始阶段 1；阶段 1 验收不通过，不得开始阶段 2。
- 阶段 0 的测试必须经过「注入错误后测试确实失败」的验证，否则视为无效基线。
- 每阶段独立提交、可独立验收与停顿；不留 TODO 占位、不交半成品。

---

## ADR-M005：审批人解析从「写死具体人」改为「单位内角色」

### 背景

`WorkflowDataSeeder` 把接待流程 A/B/C 的审批人写死为具体用户（`USER chenguodong` / `limingyuan` / `wanghaitao`，`WorkflowDataSeeder:56-60`）。多单位后，同一套流程定义要被 3 个分公司共用（见 ADR-M002 与方案 §9.1 结论 2）：若沿用写死用户，B 单位必须「借用」A 单位的人才能提交接待申请——既是数据越权，也是业务错配。

### 备选方案

1. 每个单位复制一套流程定义，审批人各写各的（3 套定义，改一次审批层级要改 3 处）。
2. 流程定义不复制，审批人改为**单位内角色**（`OFFICE_DIRECTOR` / `OFFICE_DEPUTY` / `DEPT_LEADER`），各单位自行把角色指派到具体人。
3. 保留 `USER` 写死，靠各单位导入相同用户名。

### 决策与原因

**选方案 2。** 与方案 §9.1 结论 2「各单位不要独立流程定义与工资配置」一致：方案 1 违背该结论；方案 3 等于用同名的假人来掩盖越权，权限模型会失真。方案 2 把「谁审批」下放给各单位的成员管理，流程定义保持唯一。

同时明确：**已有角色口径不动**——请假（`PROJECT_LEADER` / `PROJECT_MANAGER`）与工资（`ROLE HR_ADMIN` / `FINANCE` / `PROJECT_MANAGER`）本来就是角色，本次只改接待流程 3 处写死的 `USER`。

### 约束

- 流程定义里不得再出现 `USER <username>` 形式的审批人写死；审批人一律按角色解析。
- 角色解析必须限定在**申请人所属单位内**，不得跨单位解析到人；解析不到人时明确报错，不得静默落到空审批人。
- `OFFICE_DIRECTOR` / `OFFICE_DEPUTY` / `DEPT_LEADER` 三个角色码是否已存在于现有 7 条 `sys_role` 中，须在阶段 2 前核实（见方案 §9.2）。

---

## ADR-M006：账号唯一性与 SSO（本期结论）

### 背景

`sys_user.username` 现状是**全局唯一**（`V1__init_identity.sql:15`）。三个分公司并存时，各单位独立维护人员，用户名重名是必然事件（如 `zhangsan`）。同时用户长期要接公司统一身份认证。

### 备选方案

1. 用户名保持全局唯一，靠命名规范避开重名（如加单位前缀）。
2. `username` 改为 `(company_id, username)` 复合唯一，本期不接 SSO。
3. 本期直接接入公司统一身份认证，`username` 退化为展示字段。

### 决策与原因

**选方案 2。** 方案 1 把技术约束转嫁给使用者，各单位管理员会持续踩坑；方案 3 依赖外部系统可用性与联调排期，会把多单位改造（本期目标）绑上不可控依赖。方案 2 的改动局限在一条约束 + 登录查询，并**预留 SSO 接入点**：新增 `sys_user.external_id`（可空、非唯一）占位列，将来接 SSO 只用它做映射，不必再改表结构。

### 约束

- `sys_user.username` 唯一约束改为 `(company_id, username)`；登录必须**先定单位**再按 `(company_id, username)` 查询，禁止只按 `username` 查。
- `external_id` 本期只建列、不写业务逻辑；将来接入 SSO 须另开 ADR 说明映射与冲突处理。
 - 迁移需处理既有 6 个用户（统一回填到既有公司节点，量级见方案 §9.3）。
 
 ---
 
 ## ADR-M007：平台内核抽取采用「端口 + 适配器」，例外只许一处
 
 ### 背景
 
 方案 §2.2 的 G1 与 G8 是同一个病根：业务模块既缺组织/角色查询端口（直连 `identity.repository`），又各自复制数据范围判定（`callerHasCompanyScope` 4 处、`assertCompanyScope` 3 处、`SalaryService` 内 14 处调用点）。收敛方式有多种，选错会带来新的长期成本：把 `identity` 的 JPA 实体或 Repository 直接暴露给业务模块，等于把身份模块的表结构耦进六个业务模块——而阶段 2 恰好要改 `sys_user` 的唯一约束与 `org_unit` 的层级，届时代价会成倍放大。
 
 ### 备选方案
 
 1. **维持现状**（业务模块直连 `identity.repository`）：改动最小，但契约隐式——任何模块都能读取全部用户与单位，多单位后无法保证"只查本单位"；且 ArchUnit 只能靠豁免 6 个模块来"通过"，边界形同虚设。
 2. **平台提供静态工具类**（如 `DataScopeUtils.hasCompanyScope()`）：消除重复最快，但把"当前用户 + 角色表"变成隐藏依赖，测试无法注入，也无法按单位切换策略。
 3. **端口 + 适配器（六边形）**：`platform/org` 只定义端口与视图记录，`platform/identity` 做适配，业务模块只依赖端口。
 
 ### 决策与原因
 
 选 **3**。
 
 - **端口只暴露视图记录**（`OrgUnitRef` / `UserRef` / `RoleRef`），不暴露 JPA 实体：阶段 2 给表加 `company_id`、改唯一约束时，变化被适配器挡住，不会穿透到六个业务模块。
 - **`platform.identity` 是全仓唯一允许直连 `identity.repository` 的例外**，登记在 `ModuleBoundaryTest.REPOSITORY_ACCESS_EXCEPTIONS`。新增例外必须同时改这张表 + 补本文档，防止边界缓慢腐化（阶段 0 的旧白名单 `IDENTITY_REPOSITORY_CONSUMERS` 已删除，就是这条纪律的第一次执行）。
 - **数据范围判定统一收口到 `platform.datascope`**，测试用**真实 `RoleBasedDataScopeResolver` + mock 端口**，让"拒绝 + 写 `ACCESS_DENIED` 审计"的语义仍被端到端锁住；明确否决 mock 掉 resolver 的写法（那样测试空转）。
 - **`platform` 不列入 `FOUNDATION_MODULES`**（底层模块仍只有 `audit` / `common` / `security`）：`platform` 依赖 `identity`，不能算"谁都能依赖的底层"；它自身受新规则「平台内核不得依赖业务模块」约束，防止平台反向依赖业务。
 
 ### 约束
 
 - 业务模块中不得再出现 `callerHasCompanyScope` / `assertCompanyScope` / `resolveProject` 之类的本地实现；需要判定就注入 `DataScopeResolver`。
 - 判定的**可观测契约**（拒绝时的审计 `message` 与 `targetType` 文案）在重构中逐字保持；要改语义必须单独提一个提交并在本文档记录。
 - `RoleBasedDataScopeResolver` 只认精确字符串 `"COMPANY"`，且要求角色 `enabled` 为真。这一"看起来不宽容"的行为是**刻意保留的现状语义**（由 `RoleBasedDataScopeResolverTest` 14 条锁定），不要顺手"优化"成忽略大小写或忽略启用状态。
 
 ---
 
## ADR-M008：用户名复合唯一带来的连带影响（登录定位 / 审批人解析 / 写路径归属）

### 背景

ADR-M006 把 `sys_user.username` 的唯一约束改为 `(company_id, username)`，即"用户名不再全局唯一"。这条看似只影响登录的改动，打断了代码里两处原本隐式成立的假设：

1. **"按用户名就能找到唯一的人"**。全仓的单值 `findByUsername` 都按这个假设写。其中唯一会造成业务错位的是 `WorkflowService` 的审批人解析：流程节点上写的是用户名（`assigneeType=USER`）或角色码（`ROLE`），重名之后，任务会被派给另一个单位的人。
2. **"这条记录属于哪个单位"**。登录态此前只有 `projectId`；写路径靠从 `projectId` 上溯单位，而没有 `projectId` 的表（公告、党费、工资月/明细）根本无法上溯，只能"落不进去"或"默认落一个"。

### 备选方案

1. **硬性保持用户名全局唯一**（拒绝重名账号）：改动最小，但等于放弃"服务多个二级单位"这个目标——各单位的同名账号（`admin`、`zhangwei`）会互相挤占。
2. **用户名复合唯一，但只改登录查询**：重名能建了，可审批人解析与写路径仍按旧假设行事 ⇒ 跨单位派单、跨单位落库，问题被推迟到更难排查的运行时。
3. **用户名复合唯一 + 三处连带修复**：登录定位、审批人解析、写路径归属一并改到位。

### 决策与原因

选 **3**。ADR-M005 已经把"审批人=单位内角色"定为原则（接待流程 A/B/C 由写死用户名改为角色码就是它的落地）；本 ADR 补上 ADR-M005 没覆盖到的三类连带影响，并把它们固定成约束：

- **登录定位**：`LoginRequest` 增加可空 `companyId`。带 `companyId` ⇒ 只在本单位内按 `(company_id, username)` 匹配；不带且命中唯一 ⇒ 沿用旧行为（保证前端本期不动）；不带且命中多个 ⇒ **拒绝登录**并写 `LOGIN_REJECTED` 审计，提示指定单位。**宁可拒绝，也不猜测用户要进哪个单位。**
- **审批人解析按实例所属单位**：`WorkflowService` 的 `ROLE` 分支改用 `findEnabledUsernamesByRoleCodeAndCompanyId(角色码, 实例.company_id)`；`PROJECT_LEADER` 分支仍按 `projectId`（项目 id 全局唯一，无需改）。角色审批人必须来自流程实例所属单位，否则 A 公司的流程会流转到 B 公司的同类角色手上。
- **写路径"判不出归属即拒绝"**：`companyId` 优先由 `projectId` 上溯，其次取登录态 `currentCompanyId()`；两者都没有 ⇒ 抛异常 + `ACCESS_DENIED` 审计（文案：无法确定数据归属单位，已拒绝写入）。**不接受"默认落到第一家单位"**——静默错归属比拒绝更难发现。
- **端口与仓储全面带单位维度**：`UserQueryPort` / `UserRepository` 的单值 `findByUsername` / `existsByUsername` 全部替换为 `findByCompanyIdAndUsername` / `existsByCompanyIdAndUsername`；`OrgQueryPort.findFirstByName` 同样加 `companyId`。**业务模块中的单值用户名查询清零**，避免下次改动再漏一处。

### 约束

- 业务模块禁止再调用不带 `companyId` 的用户/单位查询；新增端口方法必须带单位维度。
- 启动期没有登录态，种子数据锚定"`type='COMPANY'` 的最小 id"（与 `V10` 第 3 节回填口径一致），**禁止按公司名反查**：`org_unit.name` 会跨单位重名。
- **启动期 `CommandLineRunner` 的门控只许依赖"自己负责的数据"，不得用全表 `count()` 判断"是否已种"**。反例即本次实测缺陷：`WorkflowDataSeeder` 用 `process_definition` 全表计数，被 `V8__init_condolence.sql` 种的 condolence 定义满足后，本类被整段跳过，新装库没有任何演示流程实例（`process_instance = 0`）。现已改为按本类三条定义的 `code` 判断，并补 `WorkflowDataSeederTest` 锁定。
- **种子运行器路径没有集成测试覆盖**（`SchemaBaselineIT` 直连 Flyway，不启 Spring 上下文、不跑 `CommandLineRunner`），因此凡是改种子，必须补对应单元测试；并且数据类改动后必须用**真·空库**（重置 schema 再启动）冒烟，而不是只看 `mvn test`。
- **全局异常处理器不得静默吞异常**（已落实）：`common/GlobalExceptionHandler.handleOther` 必须 `log.error` 记全栈。2026-10-14 已补 `log.error("未预期异常，已返回 500：{}", ex.getMessage(), ex)` 并新建 `GlobalExceptionHandlerTest` 锁定；早先只 `return` 不 log，导致 500 在日志里无痕（实测记录见 `docs/中台化改造方案.md` 阶段 2 第 9 条 D2）。排查 500 时可临时加 `--logging.level.org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver=DEBUG` 读 `Resolved [异常类: 消息]`。

### 遗留（未决，需用户拍板）

 **遗留项①（`V8` 慰问金「二级单位工会审批」= `USER / fangjing` 跨单位派单）：已关闭（2026-10-14）。** 采纳原选项①：新增 `V11__condolence_union_role.sql`，把该节点就地改为 `ROLE` / `UNION_LEADER`（新增公司级角色码「工会主席」，`data_scope = COMPANY`），并按**账号自身所属单位**把原写死账号接到该角色上（不做公司名匹配）；`DataInitializer` 同步补种该角色并授予演示审批人。验证：真·空库 24/24 PASS（同名账号不指定单位登录被拒 + 指定单位可登录 + 他单位账号抢不到待办）+ 真实开发库已应用（`now at version v11`，节点 = `ROLE` / `UNION_LEADER`）。

 **遗留项②（`project_id` 为 NULL 的账号无法登录）：已关闭（2026-10-14）。** `sys_user.project_id IS NULL` 时登录返回 500，且不落任何审计行。受控实证（演练库 `project_null` 账号：`company_id = 1`、`project_id = NULL`）确认异常为 `org.springframework.dao.InvalidDataAccessApiUsageException: The given id must not be null` ⇒ 登录流程用 `project_id` 反查单位时不接受 `null`。**已修**：`AuthService.login` 改为 `projectId` 为 null 时跳过查库（`projectName` 取 null，与 `UserService#toResponse` 口径一致），`JwtUtil.generate` 同时改为 null 的 `companyId` / `projectId` 一律不写入 claim；补 `AuthServiceTest` 2 条（NULL 账号登录成功且不得触碰 `orgUnitRepository`）+ `JwtUtilTest` 2 条。**真实库实证**：插入 `project_id IS NULL` 且持 `UNION_LEADER` 的账号，登录 200、JWT 只带 `companyId`、`LOGIN_SUCCESS` 落库、COMPANY 档接口全部 200，既有账号零回归（验证数据已删除）。

## ADR-M009：统一文号生成采用「规则表 + 序号表」两表分工，取号走行锁

### 背景

阶段 3 的第一项能力是「统一字典与编码」（方案 §3.2）。当前代码里没有任何编号生成逻辑，各业务模块若要编号只能自己拼字符串，结果是：格式各自为政、并发下重号、失败后跳号，且无法统一调整格式或审计。

### 备选方案

1. **数据库序列（`sequence`）+ 应用层拼格式**：序号不重号由数据库保证，最简单。但"每年归 1"需要每年新建序列或手工重置，重置本身又是一次破坏性操作；且序号与规则分离，改格式要改代码。
2. **单表存「类别 + 周期 + 当前值 + 格式」**：一张表搞定，读写都简单。但规则与序号耦合在同一行——改格式会锁住正在取号的行，且每次取号都要把 `pattern` 一起读出来。
3. **规则表 + 序号表两表分工，取号走 `SELECT ... FOR UPDATE`**：规则低频读、序号高频写，各自演进；取号只碰一张小表，行锁粒度最细。

### 决策与原因

选 **3**：

- **`code_rule`（规则表）**：一个类别一条规则（`category` 全局唯一、`pattern`、`seq_width`、`reset_cycle` = YEAR/NONE、`enabled`）。人可读、可后台维护；类别码全局唯一，与 `sys_role.code` 同口径（公司统一制定，不允许各单位各造一套）。
- **`code_sequence`（序号表）**：一个 `(category, period)` 一行，只存 `current_value`，是唯一有并发争用的地方；`(category, period)` 唯一约束兜住并发建行。
- **取号在一个事务内加行锁再递增**：`@Lock(PESSIMISTIC_WRITE)` 即 PostgreSQL 的 `SELECT ... FOR UPDATE`，把同一 `(类别, 周期)` 的并发请求在数据库层串行化。**不重号完全依赖这一行加锁读取**，改成普通查询会让并发取号拿到同一个号（由 `CodeGenServiceTest#lookupAlwaysGoesThroughTheLockedQuery` 锁定）。
- **不跳号靠两件事**：① 序号递增与事务同生共死（调用方业务回滚 ⇒ 序号一起回滚）；② **先校验模板、再取号**——模板非法属配置错误，若放到取号之后判断，序号已被 `save` 消耗成空号。第 ② 条是第一版实现的真实缺陷，被 `CodeGenServiceTest#unknownPlaceholderIsRejectedInsteadOfLeakingLiteral` 抓出后前移，并补 `templateIsValidatedBeforeAnySequenceIsConsumed` 锁住顺序。
- **端口收口**：业务模块一律依赖 `platform/codegen/CodeGenPort`，不得直连 `codegen` 仓储、不得自己拼编号——否则"不重号不跳号"的承诺会散落各处（与 ADR-M007「端口 + 适配器」一致）。
- **序号表不设 `company_id`**：文号是公司级编号（类别 + 周期全局唯一），与 `salary_config` 保持全局单行的理由一致（方案 §9.1 结论 2）；单位维度由调用方在业务数据上落 `company_id`，不进入编号本身。
- **V12 刻意不种序号数据**：序号表第一行在首次真实取号时创建，首号从 1。若种了 demo 序号，首个业务编号会被顶高（违背"不跳号"）。

### 约束

- 新增编号需求必须走 `CodeGenPort`；禁止在业务模块里拼编号或直连 `codegen` 仓储。
- `CodeSequence.currentValue` 只允许服务端递增，任何手工 `UPDATE` 都会破坏"不重号"承诺（数据库侧有 `current_value >= 0` 的 CHECK 兜住负值）。
- 序号超出 `seq_width` 时**不截断**：唯一性优先于格式好看。
- 「不空号」与「不重号」冲突时选**不重号**：`POST /api/codes/{category}/next` 支持提交前预取，号一经取出即消耗；调用方应在真正落库前一刻取号。
- `SchemaBaselineIT.assertRejected(sql, state)` 的语义是「先成功插一次、再插一次断言被拒」，**只适用于"库里还没有该行"的场景**；单次插入即失败（CHECK 约束）或目标行已由迁移种下（唯一冲突）时，必须改用 `assertThrows(SQLException.class, ...)`。另：断言"某表必须为空"不能数全表——别的测试可能已写入，**测试会互相依赖**，应数一个本测试独占的键。

### 遗留（未决）

任务 1（消息中心，含前端铃铛/未读数/列表）、任务 2（附件服务，本地磁盘可配置）、任务 4（流程定义后台配置化，SpEL + 白名单根上下文）**本期未做**，均待各自单独过方案后实施。
---
## ADR-M010：流程定义后台配置化（第一步——CRUD + 版本化 + 审计 + 权限点）

### 背景

阶段 3 任务 4 计划把 `WorkflowDataSeeder` 写死的流程定义变成后台可管理的配置数据。第一步只做「定义/节点 CRUD + 版本化 + 审计 + 权限点」，把迁移/重构风险压到最小；第二步「按金额/敏感等级/角色路由」需真实业务规则样例再定，本期不做。

当前痛点：流程定义（含节点、审批人）全部写死在 `WorkflowDataSeeder` 的 Java 代码里，每次调整审批流都要改代码、部署；且没有任何版本与变更留痕，管理员无从知道「这条流程什么时候被谁改成什么样」。

### 备选方案

1. **只加后台 CRUD，不做版本化**：最简单，但「谁在什么时候改了什么」完全丢失，回退只能靠 DBA 手工比对备份——与阶段 2 已立「审计留痕」基调冲突。
2. **节点级版本表（每个节点一行版本）**：可追溯每一节点的演进，但「读当前定义」变成「读最新版本再拼装」，热路径变复杂，且节点级 diff 在管理界面几乎不会被单独查看。
3. **完整快照表（定义 + 全部节点一次性存 JSON），版本号单调递增**：历史版本只用于审计与回退，几乎不被单独查询；读当前定义仍走主表，热路径不变。本次选 **3**。
4. **版本数据与权限点都在迁移里灌**：看似一步到位，但 `sys_role` 由 `DataInitializer` 在启动时种，Flyway 阶段 `sys_role` 尚空，授权必然空转（已实证，见约束）。

### 决策与原因

选 **3**（并对 4 拆分）：

- **`process_definition` / `process_node` 仍是运行态主表**：流程实例按 `definitionId` 读节点，节点行就地更新。选它而不是「版本即主表」，是为了不让热路径变复杂——当前定义永远是「主表最新一行」，不用每次发起都去拼装最新版本。
- **`process_definition_version` 是历史快照表**：每次 create/update/rollback 都先存一份完整快照（定义字段 + 全部节点的 JSON），版本号单调递增，历史一行不删。回滚 = 把目标版本快照**写回**当前定义 + 产生一个 `ROLLBACK` 新版本（不是删掉中间版本），保证「回到 v1」之后版本号是 3 而不是 1，审计链完整。
- **节点整体替换（先删后插）**：后台提交的是该定义的**全部**节点，服务端先 `deleteByDefinitionId` 再批量 `save`，不做增量合并——增量会让「哪个节点被谁删了」无法从提交内容读出。顺序固定为「先删后插」，避免节点数变少时留残行；且删除与写入在同一事务，中途失败不留「定义还在、节点没了」的半成品（`WorkflowDefinitionServiceTest#update_reducesNodeCount_leavesNoResidualNodes` 锁定）。
- **权限点走既有 `sys_role`，新增 `sys_permission` / `sys_role_permission` 两张字典表**：权限点码（如 `workflow:definition:manage`）是平台级字典，角色与权限点的多对多由新表承载；沿用既有 `sys_role`，**不改动它**（与 ADR-M002 约束一致——角色表是公司统一制定的，不随流程定义而动）。权限点经 `AuthService` 登录查出 → 写入 JWT → `JwtAuthFilter` 原样放入 `authorities`（无 `ROLE_` 前缀）→ `@PreAuthorize("hasAuthority('workflow:definition:manage')")` 把关；改动权判断只在 Controller 一处，服务层不再自行判断角色。
- **授权不放在迁移里，改由 `DataInitializer.grantDefinitionManage` 在角色就绪后幂等补**：Flyway 阶段 `sys_role` 尚空，迁移里授权是空转；角色由 `DataInitializer` 启动种。权限点码在 `DataInitializer` 里用本地常量重复字面量，避免 `identity` 模块反向依赖 `workflow` 违反 `ModuleBoundaryTest`（模块边界是硬约束）。
- **定义不加 `company_id`**：三家单位共用一套定义（ADR-M002 / 方案 §9.1 结论 2）。权限与审计由代码侧负责，不靠数据行隔离。这点和「版本化」不冲突——版本快照里本身就不含单位维度。

### 约束

- 流程定义 `code` 全局唯一（公司统一制定，不允许各单位各造一套）；重复 `code` 直接拒绝，不做静默改名（改名会让按 code 发起时找不到定义）。`WorkflowDefinitionServiceTest#create_duplicateCode_isRejected` 锁定。
- 回滚到「当前版本」无意义，直接拒绝（避免产生空转版本号）；回滚必须产生**新**版本，版本号只增不减。`SchemaBaselineIT#definitionVersionSnapshotIsUniquePerDefinitionAndVersion` 固化「同一 (definition, version) 必须唯一（23505）」。
- 流程定义至少配置一个节点；空节点提交被拒（`WorkflowDefinitionServiceTest#create_withoutNodes_isRejected`）。
- 改动不影响运行中的实例：节点行就地更新，运行中实例下次流转读新配置。若管理员只想对「以后发起的流程」生效，需先停用定义、等存量跑完再改——该口径由前台提示承担，服务层不擅自冻结运行中实例。
- 演示种子（`WorkflowDataSeeder` 的 leave/payroll/reception）不经 `WorkflowDefinitionService`、无 Spring 安全上下文，因此**不产版本快照**；这是已知边界不是缺陷。正式定义一律走后台，必有版本。
- 授权必须等 `sys_role` 就绪后由 `DataInitializer` 补，不得塞进 Flyway 迁移（迁移阶段角色表为空）。

### 遗留（未决）

第二步「按金额/敏感等级/角色路由」（SpEL + 白名单根上下文）未做，需真实业务规则样例再定。任务 1（消息中心）未做。

---
## ADR-M011：统一文件与附件采用「元数据表 + 本地磁盘 + 端口收口」

### 背景

阶段 3 任务 2 要把分散在业务模块里的文件上传 / 下载逻辑收敛成中台「货架」能力：上传 / 下载鉴权 + 下载审计，文件落本地磁盘、存储根可配置。目标口径见方案 §3.2「统一文件与附件」与阶段 3 任务清单第 2 项。

当前痛点：代码里完全没有附件 / 文件服务，各业务模块若各自落盘，会重复实现「公司隔离 + 下载审计 + 路径穿越防护」，且存储根一旦分散就难以迁移与统一治理。

### 备选方案

1. **对象存储（S3 / MinIO）**：天然多租户、免运维磁盘，但本期无对象存储基础设施，且要把文件治理搬过去要先搭一套存储服务——超出本期范围。
2. **数据库 BLOB 存文件**：实现简单、隔离靠 `company_id` 天然成立，但大文件会把 PG 行撑爆、备份变慢，且与「存储根可配置 / 换盘」目标冲突。
3. **本地磁盘 + 元数据表（相对路径）**：文件本体落磁盘，`storage_path` 只存相对 storage-root 的路径，换盘 / 容器化只改 `WORKBENCH_FILE_ROOT`。本次选 **3**。
4. **把 `AttachmentMeta` 放进业务模块 `attachment.dto`**：看似归置方便，但会让平台内核 `AttachmentPort` 反向依赖业务模块（`ModuleBoundaryTest.平台内核不得依赖业务模块` 已实证抓出）。本次**不采用**，改为把跨层契约 DTO 上提到平台内核 `platform.attachment`。

### 决策与原因

选 **3**（并对 4 修正）：

- **`file_attachment` 元数据表 + 本地磁盘**：一个附件一行（原始名 / 存储名 / 相对路径 / 大小 / 类型 / 归属业务）；文件本体落磁盘，`storage_path` 只存相对路径，绝不存绝对路径。`stored_name` 用 `UUID + 原始扩展名`（不含用户输入），防重名覆盖与中文 / 特殊字符问题。
- **`company_id` NOT NULL（与 11 张公司维度表同口径）**：附件归属单位，跨公司访问由应用层 `DataScopeResolver.canAccessCompany` 拒绝，统一返回 404（不暴露「存在但无权限」）。落盘路径一律带 `company_id` 子目录，物理上也按单位分开。
- **路径穿越防护是硬约束**：`resolveSafe` / `assertInsideRoot` 在下载 / 删除时对拼出的绝对路径做「仍在 storage-root 之下」校验，逃逸直接抛 `IllegalArgumentException`，且校验在写盘 / 读盘之前（不依赖范围外才做）。
- **端口收口（修正架构方向）**：业务模块一律依赖 `platform/attachment/AttachmentPort`，不得自己拼磁盘路径、不得直连 `attachment` 仓储（与 ADR-M007「端口 + 适配器」一致）。跨层契约 `AttachmentMeta` 定义在平台内核（`com.workbench.platform.attachment`，纯数据 record、不引用任何业务实体），映射逻辑（`FileAttachment` → `AttachmentMeta`）留在 `AttachmentService` 内部——平台内核不得反向依赖业务模块（被 `ModuleBoundaryTest` 的硬约束实证）。
- **审计**：上传 `FILE_UPLOAD`、下载 `FILE_DOWNLOAD`、删除 `FILE_DELETE` 均落 `audit_log`。

### 约束

- `file_attachment.company_id` 必须 NOT NULL（V15 自检 + `SchemaBaselineIT#attachmentTableCarriesNotNullCompanyId` 双锁）；缺公司归属（token 无 `companyId`）的写入当场拒绝。
- `storage_path` 只存相对路径，绝不存绝对路径；下载 / 删除必须经由 `resolveSafe` 校验，禁止任何由外部可注入的路径直接 `Files` 操作。
- 本期不校验扩展名白名单 / 病毒（范围外）；文件大小上限交给 Spring 的 `multipart.max-file-size`。路径穿越防护不因范围外而省略。
- 跨层契约 `AttachmentMeta` 是平台内核的一部分，业务模块可依赖、但不得在其内定义同名类型造成平台反向依赖。

### 遗留（未决）

任务 1（消息中心）未做；任务 4 第二步「按金额/敏感等级/角色路由」（SpEL + 白名单根上下文）未做，需真实业务规则样例再定。

---
 ## 修订记录
 
 - 2026-10-13：新增 ADR-M005（审批人解析=单位内角色）、ADR-M006（账号唯一性与 SSO）；依据为用户对方案 §9 五项待确认事项的答复，结论同步写入 `docs/中台化改造方案.md` §9.1。
 - 2026-10-13：新增 ADR-M007（平台内核抽取采用端口 + 适配器，例外只许一处）；依据为阶段 1（平台内核抽取）的实施结果，落地与验证记账同步写入 `docs/中台化改造方案.md` 第 5 节"阶段 1"。
- 2026-10-13：新增 ADR-M008（用户名复合唯一带来的连带影响：登录定位、审批人解析按实例单位、写路径「判不出归属即拒绝」）；依据为阶段 2 的实施与实测结果，落地与验证记账同步写入 `docs/中台化改造方案.md` 第 5 节「阶段 2」。
- 2026-10-13：**更正 ADR-M002 的两条约束**（`salary_config` 不再要求按 `company_id` 分片、跨单位访问不再依赖 `COMPANY_TREE`）；依据为阶段 2 的实施与真实数据副本实证 —— 落地的复合唯一只有 `sys_user.username` 与 `salary_month.month` 两处，其余按方案 §9.1 结论 2 保持全局共用；同步在 `docs/中台化改造方案.md` §3.3 增加「更正 ②」。

- 2026-10-14：**关闭 ADR-M008「遗留项①」**（慰问金二级工会审批节点由 `USER / fangjing` 改为 `ROLE / UNION_LEADER`：新增 `V11__condolence_union_role.sql` + `DataInitializer` 角色种子 + 真·空库 24/24 冒烟 + 真实开发库应用）；**新增「遗留项②」**（`project_id IS NULL` 账号登录 500，已受控实证根因 `InvalidDataAccessApiUsageException: The given id must not be null`，待拍板是否修）；「约束」新增一条「全局异常处理器不得静默吞异常」。落地与验证记账同步写入 `docs/中台化改造方案.md` 第 5 节阶段 2 第 7–9 条。依据为步骤 ⑩（真实开发库应用 V10 + V11）及随后的接口回归实测。
- 2026-10-14：**关闭 ADR-M008「遗留项②」并落实「全局异常处理器不得静默吞异常」**（用户批准一并修复）。D1：`AuthService.login` 对 `projectId` 为 null 的账号跳过 `orgUnitRepository.findById`，`JwtUtil.generate` 对 null 的 `companyId` / `projectId` 一律不写入 claim；补 `AuthServiceTest` 2 条 + `JwtUtilTest` 2 条。D2：`GlobalExceptionHandler.handleOther` 补 `log.error` 记全栈，新建 `GlobalExceptionHandlerTest` 6 条锁定 400/403/404/500 契约与"未预期异常必须落 ERROR 日志"。验证：`mvn test` 119 单测全绿（109 → 119）、`mvn verify` 119 + 14 集成全绿；真实开发库插入 `project_id IS NULL` 且持 `UNION_LEADER` 的账号实证登录 200、JWT 只带 `companyId`、`LOGIN_SUCCESS` 落库、COMPANY 档接口全 200、既有账号零回归（验证数据已删除）。落地与验证记账同步写入 `docs/中台化改造方案.md` 阶段 2 第 10 条。
- 2026-10-14：新增 **ADR-M009**（统一文号生成采用「规则表 + 序号表」两表分工，取号走行锁）。依据为用户对阶段 3 范围的拍板（本期只做任务 3「文号生成」，其余三项各自单独过方案）与实施结果；落地与验证记账同步写入 `docs/中台化改造方案.md` 第 5 节阶段 3。含一条实测缺陷的根因与防再犯：模板校验必须前移到取号之前，否则序号被 `save` 消耗成空号。
- 2026-10-14：新增 **ADR-M010**（流程定义后台配置化第一步：CRUD + 版本化快照 + 审计 + 权限点）。依据为用户对阶段 3 任务 4「按推荐方案只做第一步」的拍板（金额/敏感等级/角色路由第二步待真实业务规则样例再定）与实施结果；落地与验证记账同步写入 `docs/中台化改造方案.md` 第 5 节阶段 3 第 9–19 条。含两条真实根因与防再犯：① 授权不能塞进 Flyway 迁移（`sys_role` 由 `DataInitializer` 启动种、迁移阶段为空，空库自检会误报，已改为「只验权限点已种、有角色行时才验授权」）；② PowerShell 大段重写 `AuthService` 漏掉 `LOGIN_SUCCESS` 审计调用，被 D1 回归测试抓出后补回。验证：`mvn verify` → 141 单测 + 20 集成全绿（134 → 141 新增 `WorkflowDefinitionServiceTest` 7 条；SchemaBaselineIT 迁移数 12 → 14 新增 3 条 V13/V14 契约断言）。
- 2026-10-15：新增 **ADR-M011**（统一文件与附件采用「元数据表 + 本地磁盘 + 端口收口」）。依据为用户对阶段 3 任务 2「本地磁盘可配置」的拍板与实施结果；落地与验证记账同步写入 `docs/中台化改造方案.md` 第 5 节阶段 3（任务 2 已交付块）。含一条架构违规与防再犯：第一版把跨层契约 `AttachmentMeta` 放进业务模块 `attachment.dto`，导致平台内核 `AttachmentPort` 反向依赖业务模块，被 `ModuleBoundaryTest.平台内核不得依赖业务模块` 抓出；已上提到平台内核 `platform.attachment`（纯数据 record），映射逻辑回退到 `AttachmentService` 内部。验证：`mvn verify` → 145 单测 + 21 集成全绿（141 → 145 新增 `AttachmentServiceTest` 4 条；SchemaBaselineIT 迁移数 14 → 15 新增 1 条 V15 契约断言）。
