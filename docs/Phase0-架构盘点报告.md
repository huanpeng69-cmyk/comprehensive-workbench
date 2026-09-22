# Phase 0 · 架构盘点报告（单机版 → 部门联机版）

> 生成时间：2026-09-22 · 范围：只检查、只规划，未修改任何代码
> 结论先行：**底子比预期好**——认证、审计、Docker 隔离、密钥治理已有正确骨架；真正的差距集中在「业务数据仍在前端（mock + localStorage）」和「后端没有任何 API 级/数据级权限校验」两点上。

---

## 1. 当前系统架构

```text
浏览器（localhost:5173，Vite dev server，host:true 已对局域网开放）
   ↓  同源 /api（vite proxy → localhost:8080）
Vue 3 + TS + Pinia + Element Plus（hash 路由，仅校验 localStorage 是否有 token）
   ↓
Spring Boot 3.3（:8080，监听所有网卡）
   ├─ identity：AuthController / AuthService / User / Role / OrgUnit / DataInitializer
   ├─ security：SecurityConfig / JwtAuthFilter / JwtUtil / CurrentUserHolder
   ├─ audit：AuditService / AuditLog（REQUIRES_NEW 独立事务）
   ├─ kb：RegulationsController（制度正文，登录后读取）
   └─ workflow：WorkflowController / TodoController + WorkflowService
   ↓
PostgreSQL 16（Docker，Flyway V1/V2 迁移）＋ Redis 7（登录限流）
```

部署方式：`start-workbench.bat` 一键启动 = Docker(PG+Redis) → 后端 jar(:8080) → 前端 Vite dev(:5173)。**无 Nginx，无生产部署形态。**

## 2. 当前数据流（关键问题所在）

| 数据流 | 现状 |
|---|---|
| 登录/待办/流程 | ✅ 浏览器 → API → PostgreSQL（真实链路） |
| 制度正文 | ✅ 后端读 `server/regulations/`，登录后返回（正文已收进后端） |
| 人员花名册、工资制作 | ❌ 前端 mock 种子 + **localStorage 持久化**（`salary-months-v1`、花名册 key 等） |
| 通知/公章/党费/慰问/福利/接待/请假/党员名册/工资批次 | ❌ 直接 import `mock/data.ts`，纯前端内存数据 |

**多用户联机的最大障碍**：同一份工资/花名册数据存在每个人自己的浏览器里，A 录入 B 永远看不到。

## 3. 当前数据库表（Flyway）

- V1：`org_unit`（公司/项目部/部门三级树）、`sys_user`、`sys_role`（含 `data_scope`: PROJECT/COMPANY）、`sys_user_role`、`audit_log`
- V2：流程相关表（process_definition / process_instance / process_node / task）
- ❌ **无任何业务表**：employee、salary、notice、seal、party_fee 等均不存在，需新建

## 4. 当前 API 清单

| 模块 | API | 鉴权 |
|---|---|---|
| 认证 | POST `/api/auth/login` | 公开 |
| 制度 | GET `/api/kb/regulations/{docNo}` | 仅登录 |
| 流程 | POST `/api/workflow/instances`、GET `/instances/{id}`、GET `/instances/mine`、GET `/definitions` | 仅登录 |
| 待办 | GET `/api/todos/mine`、POST `/todos/{id}/approve|reject|transmit` | 仅登录 |
| 健康 | GET `/actuator/health` | 公开 |
| **用户管理 / 角色管理 / 审计查询 / 全部业务模块** | **均不存在，待建** | — |

## 5. 登录认证现状（✅ 基础扎实，需小加固）

- BCrypt 密码哈希；JWT 12 小时有效，携带 username/userId/projectId/roles
- Redis 登录限流：连续 5 次失败锁 15 分钟，用户名不存在也计数（防探测）✅
- 登录成功/失败/锁定均写审计 ✅
- JWT secret 支持 `WORKBENCH_JWT_SECRET` 环境变量，生产 secret 在 `server/config/application.yml`（已确认被 .gitignore 忽略）✅
- ⚠️ 未做：Token 黑名单/版本号（登出仅前端删 token）；密码修改接口不存在（用户无法改密码）

## 6. 当前角色权限现状（❌ 最大短板）

- 已有 7 个角色：EMPLOYEE / PROJECT_MANAGER / HR_ADMIN / FINANCE / PARTY_ADMIN / UNION_ADMIN / SYSTEM_ADMIN，带 `data_scope`（PROJECT/COMPANY）
- JWT 中携带角色码 + projectId，`CurrentUserHolder.currentProjectId()` 已预留数据权限入口
- **但**：
  - 无 `sys_permission` 表，无「权限点」概念
  - **全后端 0 处 `@PreAuthorize` / `hasRole`**——任何登录用户可用 Postman 调用全部接口
  - 前端菜单对所有角色全量展示（MainLayout 无任何角色判断），连"前端隐藏"都未做
  - 数据范围过滤目前仅在 workflow（todo/instance 按发起人过滤）中生效，业务表尚未建立

## 7. 审计模块现状（✅ 骨架正确，需扩展）

- `AuditService` 用 `REQUIRES_NEW` 独立事务 ✅（业务回滚不影响审计）
- 已记录：登录成功/失败/锁定、流程发起/通过/驳回/转办/抄送、制度查阅
- ⚠️ 字段缺失：无 userId、ip、userAgent、result、riskLevel、before/afterData、reason
- ⚠️ 无审计查询 API 与后台页面；权限拒绝事件目前不会产生（因为没有权限校验）

## 8. Mock 数据位置（含敏感字段）

| 文件 | 内容 | 敏感字段 |
|---|---|---|
| `web/src/mock/data.ts` | 花名册10人、工资批次5条、通知5、公章6、党费6、慰问3、待办6、制度8、请假5、党员9、接待3、福利8 | 身份证号、手机号、月薪、党费基数、工资总额 |
| `web/src/mock/rosterOptions.ts` | 花名册下拉选项/分类推导 | 无（选项定义，保留） |
| `web/src/mock/salaryOptions.ts` | 工资表字段映射/公式 | 无（公式定义，保留） |
| `web/src/mock/types.ts` | 全部 TS 类型定义 | 无（类型定义，保留） |

> 迁移策略修正：`mock/` 目录实际承担了「数据 + 类型 + 业务规则（公式/选项）」三重职责。**只迁数据，types/options/公式应迁到 `web/src/domain/` 之类的位置保留**，不要整目录删除。

### localStorage 落地数据（多人联机的隐形炸弹）

- `salary-months-v1`：整套工资月度数据
- 花名册 store：含身份证、手机号、月薪的花名册全量
- 请假记录、工资等级标准配置
- 另有 `workbench-token` 等 5 个登录态 key（正常）

## 9. 敏感数据位置汇总

1. 前端 mock/data.ts + localStorage（上表）——数据均为**合成假数据**（README 已声明），但结构即真实业务结构
2. `web/public/regulations/` 8 份制度全文 → **会被 Vite 原样拷贝进 `dist/regulations/`**，生产部署后**无需登录即可直接下载**（已验证 dist 中存在）。后端鉴权读取的设计被 public 目录架空了
3. `server/config/application.yml`：JWT secret + Redis 密码（已 gitignore，✅ 安全）
4. `server/.env`：DB/Redis 密码（已 gitignore，✅ 安全）
5. `DataInitializer`：演示密码 `Workbench@2026` 硬编码（README 已注明仅演示环境）

## 10. 当前 Docker 网络

- `server/docker-compose.yml`：PostgreSQL 与 Redis 均绑定 `127.0.0.1:port:port` ✅ **不暴露局域网**（符合要求）
- 密码通过 `server/.env` 注入，缺失时启动报错 ✅
- 数据卷落在 `server/.docker-data/`（已 gitignore）✅
- Spring Boot 后端**未容器化**，以 jar 直跑宿主机

## 11. 端口暴露情况

| 端口 | 服务 | 绑定 | 局域网可访问 | 联机版要求 |
|---|---|---|---|---|
| 5432 | PostgreSQL | 127.0.0.1 | ❌ 不可 | ✅ 达标 |
| 6379 | Redis | 127.0.0.1 | ❌ 不可 | ✅ 达标 |
| 8080 | Spring Boot | 0.0.0.0 | ⚠️ **可直连**（绕过前端，靠 JWT 挡） | 应收进 Nginx 后面 |
| 5173 | Vite dev | 0.0.0.0（host:true） | ⚠️ **可访问，且是当前唯一入口** | 必须替换为 Nginx 静态部署 |

## 12. 安全风险清单（按等级）

| # | 风险 | 等级 | 说明 |
|---|---|---|---|
| R1 | 后端无 API 权限校验（0 处 @PreAuthorize），无数据范围过滤 | 🔴 高 | 任何登录用户可调全部接口；Phase 3 核心工作 |
| R2 | 业务数据存前端 mock + localStorage | 🔴 高 | 数据不共享、不同步、随浏览器丢失；Phase 2 核心 |
| R3 | `public/regulations/` 打进 dist，部署后匿名可下载制度全文 | 🔴 高 | 从 public 移除即可修复，改动极小 |
| R4 | 8080 直接监听 0.0.0.0，5173 以 dev server 作正式入口 | 🟠 中 | Phase 1/6：Nginx 统一入口 |
| R5 | 全员共享演示密码 `Workbench@2026`，无修改密码功能、无强制改密 | 🟠 中 | Phase 3/6 |
| R6 | 登出无 Token 失效机制（黑名单/版本号） | 🟠 中 | 复用 Redis 即可实现 |
| R7 | 审计缺 IP/UA/风险等级/前后镜像字段，无审计后台 | 🟡 低 | Phase 4 |
| R8 | CORS 白名单仅 localhost:5173，生产同源后需同步调整 | 🟡 低 | Phase 6 |
| R9 | `web/dist/` 存在旧构建产物（含制度文本），存在误部署风险 | 🟡 低 | 部署前清理并纳入流程 |
| R10 | 无数据库备份机制（PG 已成为未来核心资产） | 🟡 低 | Phase 6：pg_dump 定时任务 |

## 13. 单机 → 联机改造清单

### 模块迁移表（Phase 2 执行）

| 模块 | 当前数据来源 | 目标数据库表 | 目标 API | 当前页面 | 迁移状态 |
|---|---|---|---|---|---|
| 人员花名册 | mock/data.ts + localStorage | employee | /api/employees | hr/StaffList.vue | 待迁移（**第一批**） |
| 工资制作/批次 | mock/data.ts + localStorage | salary_batch / salary_record | /api/salaries | hr/PayrollApproval.vue | 待迁移（**第二批·敏感**） |
| 组织/部门/项目 | DataInitializer（已有 org_unit） | org_unit（复用） | /api/orgs | 全局 | 前端 PROJECTS/DEPARTMENTS 常量改调 API |
| 请假 | mock + localStorage | leave_record（或并入 workflow） | /api/leaves | hr/LeaveList.vue | 待迁移 |
| 通知公告 | mock/data.ts | notice | /api/notices | office/NoticeList.vue、Home.vue | 待迁移 |
| 公章登记 | mock/data.ts | seal_usage | /api/seals | office/SealUsage.vue | 待迁移 |
| 公务接待 | mock/data.ts | reception | /api/receptions | office/ReceptionList.vue | 待迁移 |
| 党费 | mock/data.ts | party_fee | /api/party-fees | party/PartyFees.vue | 待迁移（敏感） |
| 党员名册 | mock/data.ts | party_member | /api/party-members | party/PartyMembers.vue | 待迁移 |
| 慰问金 | mock/data.ts | condolence | /api/condolences | union/CondolenceList.vue | 待迁移（敏感） |
| 福利签收 | mock/data.ts | welfare_item | /api/welfare | union/WelfareSign.vue | 待迁移 |
| 制度库 | 列表在前端 mock，正文已在后端 | regulation（元数据入表） | /api/kb/regulations | kb/Regulations.vue | **半迁移**：列表数据补入表 |
| 用户/角色管理 | — | sys_user / sys_role（已有） | /api/users /api/roles | 新增管理页 | 待新建 |
| 审计后台 | — | audit_log（已有，需扩字段） | /api/audit-logs | 新增安全审计中心 | 待新建 |

### 基础设施改造清单

| 项 | 内容 | 关联风险 |
|---|---|---|
| Nginx 引入 | 静态托管 dist + /api 反代 8080，替代 5173 dev server | R4 |
| 制度正文下架 | 从 `web/public/` 移除 regulations 目录 | R3 |
| 后端容器化（可选） | 或保持 jar + 服务化启动脚本 | R4 |
| 权限模型 | sys_permission 表 + 权限点注解 + 数据范围拦截器 | R1 |
| 密码管理 | 修改密码接口 + 首登强制改密 + 生产去除演示账号 | R5 |
| Token 失效 | Redis 黑名单/版本号 | R6 |
| 审计升级 | 字段扩展 + 查询 API + 审计中心页面 | R7 |
| 备份 | pg_dump 每日任务 + 恢复演练 | R10 |

## 14. 每项改造的风险等级

- 🔴 高：R1（权限）、R2（数据center化）——不做则"联机"只是形式
- 🔴 高：R3（制度匿名下载）——但修复成本极低（删 public 目录 + dist）
- 🟠 中：R4、R5、R6
- 🟡 低：R7、R8、R9、R10
- 迁移操作本身风险低（新表新增，不动旧表；mock 数据等模块验证通过后再删）

## 15. 建议实施顺序

| Phase | 内容 | 前置依赖 | 验证标准 |
|---|---|---|---|
| **1** | Nginx 统一入口（80 → dist + /api 反代）＋ 移除 public/regulations ＋ 清理 dist ＋ 局域网访问打通 | 无 | 同事电脑浏览器打开服务器 IP 能登录；未登录直接访问 dist/regulations/*.txt 返回 403/404 |
| **2** | 数据中心化：先 花名册/组织 → 再 工资/党费/慰问，逐模块 建表→API→前端改造→验证→删 mock 数据 | Phase 1 | A 机录入、B 机刷新可见；dist 构建产物中 grep 不到任何业务数据 |
| **3** | 权限：sys_permission ＋ @PreAuthorize ＋ 数据范围查询拦截 ＋ 用户管理页 ＋ 修改密码 | Phase 2 | 普通员工调 /api/salaries 返回 403；越权查他人工资被拒并写审计 |
| **4** | 审计升级：扩字段（ip/ua/riskLevel/before/after）＋ 审计中心页面 | Phase 3 | 权限拒绝、敏感查看、角色变更均有记录且可检索 |
| **5** | 安全加固复查：Token 黑名单、登录策略、SQL/XSS/上传、CORS 收紧 | Phase 4 | 按第 26 条安全验收清单逐项打勾 |
| **6** | 部署定型：备份/恢复演练、启停脚本、健康检查、安全验收清单归档 | Phase 5 | 恢复演练实测通过；5432/6379 局域网不可达复核 |

> 与指令中的 Phase 0-6 完全对齐；Phase 1 特意把「R3 制度正文下架」提前，因为它修复成本最低、泄露后果最直接。

---

## 附：盘点中发现的两处代码级小问题（仅记录，未改动）

1. `User.java` 存在重复 import（第 1–22 行多次重复 jakarta 注解导入），能编译但应清理
2. `start-workbench.bat` 提示"登录凭据显示在登录页"，但 `Login.vue` 实际只提示"使用管理员分配的账号"，两处文案不一致

**Phase 0 结束。等待确认后再进入 Phase 1。**
