# Phase 2 · 数据中心化 第三批：请假模块后端化（完成记录）

> 时间：2026-09-23 · 状态：**已完成并验证** · 前置：第一批（人员花名册）、第二批（工资制作）已交付
> 本批同时验证「CRUD 后端化」与「接流程引擎」两条路径，为其余 6 个 mock 页面提供可复制模板。

## 一、改动清单

### 新增：请假模块（后端）

| 文件 | 内容 |
|---|---|
| `db/migration/V5__init_leave.sql` | `leave_record` 表（申请人/项目部/类型/起止/天数/事由/状态/流程实例 id），索引；注释避开 `${` |
| `leave/domain/Leave.java` | JPA 实体 `@Table("leave_record")`；`leaveType/startDate/endDate` 加 `@JsonProperty` 别名使请求契约与响应一致 |
| `leave/repository/LeaveRepository.java` | `findByProjectId` / `findByApplicant` / `findAllBy` / `findByWorkflowInstanceId` |
| `leave/application/dto/LeaveVo.java` | 视图对象 `(id, applicant[显示名], applicantUsername, project, type, start, end, days, reason, status, appliedAt)` |
| `leave/application/LeaveService.java` | 总闸门：服务端强制申请人、启动 leave 流程、数据范围过滤、审批走工作流、流程完结回调同步状态 |
| `leave/api/LeaveController.java` | `GET /`、`GET /mine`、`POST /`、`PUT /{id}`、`DELETE /{id}`、`POST /{id}/approve`、`POST /{id}/reject` |

请假 API 一览：

```text
GET    /api/leaves                  列表（按角色数据范围服务端过滤）
GET    /api/leaves/mine             我发起的
POST   /api/leaves                  提交（启动 leave 流程，申请人取当前登录用户）
PUT    /api/leaves/{id}             修改（仅待审批，本人/全公司范围）
DELETE /api/leaves/{id}             删除（仅待审批）
POST   /api/leaves/{id}/approve     批准（经 WorkflowService，仅被指派经理可批）
POST   /api/leaves/{id}/reject      驳回
```

### 新增：通用流程回调组件

| 文件 | 内容 |
|---|---|
| `workflow/application/BizCompletionRegistry.java` | `bizType → handler` 映射；`complete(bizType, bizId, status)` 在工作流 END/REJECT 时回调业务模块，使引擎不感知业务 |

### 修改：流程引擎（workflow）

| 文件 | 内容 |
|---|---|
| `workflow/repository/TaskRepository.java` | 新增 `findFirstByInstanceIdAndStatusOrderByNodeSeqAscCreatedAtAsc`（按实例取首个待办） |
| `workflow/application/WorkflowService.java` | 注入 `BizCompletionRegistry`；`advance()` END 分支、`reject()` 触发 `complete(...,"COMPLETED"/"REJECTED")`；新增 `approveByInstance` / `rejectByInstance` |

> 注：`WorkflowDataSeeder` 早已定义 leave 流程（节点：项目部负责人审批 → END），本次仅让真实业务记录接入该引擎；实际审批端点为 `POST /api/leaves/{id}/approve`，非清单草稿里写的 `/api/workflow/start`。

### 前端

| 文件 | 内容 |
|---|---|
| `api/leave.ts` | **新增**：7 个请假 API 封装（`listLeaves`/`myLeaves`/`createLeave`/`updateLeave`/`deleteLeave`/`approveLeave`/`rejectLeave`），comment 走 `?comment=` 并 `encodeURIComponent` |
| `stores/leave.ts` | **重写**：localStorage → 全部走 API；保留 `leaveDaysFor(name, month)` 读内存 `leaveList`，`salary.ts` 免改即联动 |
| `views/hr/LeaveList.vue` | **重写**：申请人只读（取自登录态）、提交/批准/驳回/删除全部异步化并带 `ElMessage` 错误提示 |

> `mock/types.ts` 的 `LeaveRecord` 类型本批未改（字段 `type/start/end/applicant/status/days/reason` 已对齐响应）。

## 二、关键设计决策

1. **申请人服务端强制（不可伪造）**：`create` 一律取 `CurrentUserHolder.currentUsername()`，覆盖客户端传入值后再查姓名/项目部——杜绝越权代他人提交。
2. **流程引擎与业务解耦**：新增 `BizCompletionRegistry` 通用组件（`bizType → handler`）。工作流在 END / REJECT 时只调用 `complete(bizType, bizId, status)`，由业务模块自身实现回调。后续 payroll / reception 审批完结同步可复用同一模式，引擎零改动。
3. **审批走真实工作流**：`approve`/`reject` 经 `WorkflowService.approveByInstance`，底层 `ensureAssignee` 守卫保证**只有被指派的项目部经理（PROJECT_MANAGER / PROJECT_LEADER）能批**；普通员工即便猜到接口也会被拒。
4. **数据范围过滤在服务端 SQL 层**：COMPANY 范围角色看全表，PROJECT 范围角色仅看本人项目部（`leave_record.project_id = 当前用户 projectId`）。前端只渲染、不负责过滤。
5. **与工资工日勾稽保留**：`stores/leave.ts` 保留 `leaveDaysFor(name, month)` 读内存 `leaveList`，审批通过（已批准 + 事/病/其他）后 `salary.ts` 工资工日实时联动，无需改动工资模块。
6. **契约对称**：请求与响应统一使用 `type/start/end`（`Leave` 实体字段加 `@JsonProperty("type"/"start"/"end")`），与前端 `LeaveRecord` 类型、`salary.ts` 字段命名完全一致。

## 三、验证记录（实测）

| 用例 | 结果 |
|---|---|
| wanghaitao（EMPLOYEE，云岭）登录 | ✅ |
| wanghaitao 提交请假（type/start/end 前端式载荷） | ✅ 落库 id，status=待审批，applicant 服务端强制为「王海涛」 |
| chenguodong（PROJECT_MANAGER，云岭）待办中心 | ✅ 出现任务 `王海涛的事假申请（2天）`，assignee=chenguodong，node=项目部负责人审批，status=PENDING |
| chenguodong 批准 | ✅ 工作流 END → BizCompletionRegistry 回调 → status 翻转为 **已批准** |
| 列表复查目标记录 | ✅ status=已批准 |
| fangjing（HR+FINANCE，COMPANY 范围）查列表 | ✅ 可见全部 |
| wujunjie（EMPLOYEE，江湾 project=3）查列表 | ✅ 0 条，无跨项目部越权 |
| 测试数据清理 | ✅ leave_record 0 行；process_instance 仅剩 3 条 seed demo |
| 前端编译 | ✅ `vue-tsc --noEmit && vite build` 通过，dist 无 localStorage leave 残留 |

## 四、踩坑记录

1. **演示库旧种子导致登录失败（最隐蔽）**：`DataInitializer` 仅在 `sys_user` 为空时播种。演示 Postgres 卷残留早期 6 人旧种子（含乱码 `劢杗`），`wanghaitao` 等规范账号根本不存在 → 登录返回 400。处置：**先 `pg_dump` 全量备份**，再 `TRUNCATE` 15 张数据表（保留 `flyway_schema_history`），重启后端重新播种规范账号。已建立回滚点 `server/backup-pre-reset-*.sql`。
2. **请求/响应契约不对称**：前端发 `type/start/end`，后端 `Leave` 实体按 Java 属性名绑定 `leaveType/startDate/endDate` → 真实 UI 提交时三个字段为 null、`nullable=false` 致 500。处置：给实体字段加 `@JsonProperty` 别名，API 契约统一为 `type/start/end`。
3. **`post` 助手必填 body**：`approveLeave`/`rejectLeave` 漏传 body 触发 `vue-tsc` 报错 `Expected 2 arguments, but got 1`。处置：补 `{}`（后端 approve/reject 用 `@RequestParam comment`，忽略 body）。
4. **Spring Boot repackage 改名失败**：后端进程占用 jar 时 `mvn package` 无法 rename 为 `.original`。处置：构建前先停后端（`taskkill /F /IM java.exe`）。
5. **Python `urllib` 中文 URL 未编码**：`comment` 中文写入 URL 触发 `UnicodeEncodeError`。处置：用 `urllib.parse.urlencode` 编码查询参数。

## 五、遗留

- seed demo 的「赵启明-事假3天」仅有 workflow instance、**无对应 leave_record**（`WorkflowDataSeeder` 早于 leave 模块）。批准其待办会正常完成工作流，但 `applyWfStatus` 因找不到记录而 no-op（已做空值保护，不 500）。后续可让 seeder 一并建 `leave_record` 使演示连贯。
- 首页 mock 指标、MainLayout PROJECTS 下拉 → 后续 P0 批次。
- 其余 6 个纯 mock 页面后端化（党费/慰问金/公章/接待/福利/党员名册/通知） → 后续批次，可套用本批模板。
