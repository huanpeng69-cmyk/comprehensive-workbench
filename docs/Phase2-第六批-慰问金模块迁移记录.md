# Phase2 第六批：慰问金模块后端化（含两级工会审批流）

> 对应改造清单：慰问金模块迁移（Phase0 盘点表 `condolence /api/condolences`）。
> 记录的批次顺序：请假 → 首页聚合 → 党费 → **慰问金** → 公章/接待/福利/党员 → 通知。
> 本批是**第二个**接入 `BizCompletionRegistry` 工作流回调的业务模块（首个为请假 leave）。

## 一、改造范围

| 项 | 改造前 | 改造后 |
|---|---|---|
| 慰问金列表 (`union/CondolenceList.vue`) | `mock/data.condolences` 写死 + 无统计 | `GET /api/condolences`，4 张统计卡，金额人民币大写联动 |
| 新建申请 | 前端写死/无提交 | `POST /api/condolences`，申请人强制服务端取当前登录用户 |
| 工会审批 | 无 | 两级工会审批流（项目工会 → 二级单位工会 → 已发放），走真实工作流 |
| 审批意见回填 | 无 | 流程完结时由 `applyWfStatus` 从两级节点任务回填到记录 |

核心差异点：慰问金带**两级工会审批流**，且是第二个复用「流程完结回调同步业务状态」模式的模块，验证了该模式在「>2 节点、含 PROJECT_LEADER + USER 混合审批人」场景下的正确性。

## 二、改动清单

### 后端新增（`com.workbench.condolence`）
1. `db/migration/V8__init_condolence.sql` — `condolence` 表（applicant / applicant_username / project_id / project / category / reason / amount / amount_in_words / project_union_opinion / company_union_opinion / status / workflow_instance_id / created_at / updated_at）；并**直接注入**流程定义与节点（原因见设计决策）。
   - 流程 `condolence`：node1 项目工会审批（APPROVAL / PROJECT_LEADER / PROJECT_MANAGER / SINGLE）→ node2 二级单位工会审批（APPROVAL / USER / fangjing / SINGLE）→ node3 END。
2. `condolence/domain/Condolence.java` — JPA 实体。
3. `condolence/repository/CondolenceRepository.java` — `findByProjectIdOrderByCreatedAtDesc` / `findByApplicantOrderByCreatedAtDesc` / `findAllByOrderByCreatedAtDesc`。
4. `condolence/application/CondolenceVo.java` — 视图对象（含 `formatAppliedAt`）。
5. `condolence/application/CondolenceService.java` — 核心：
   - 构造函数 `completionRegistry.register("condolence", this::applyWfStatus)`；
   - `callerHasCompanyScope()` 复用 party/leave 同款数据范围闸门；
   - `list()`：COMPANY 全表 / PROJECT 按 `currentProjectId()` 过滤；
   - `create()`：申请人强制取当前登录用户，启动 `condolence` 流程回写 `workflow_instance_id`；
   - `approve/reject`：经 `workflowService.approveByInstance/rejectByInstance`；
   - `applyWfStatus(bizId, status)`：COMPLETED→`已发放` 并 `instanceTaskComments` 回填 seq=1→`project_union_opinion`、seq=2→`company_union_opinion`；REJECTED→`已驳回`。
6. `condolence/api/CondolenceController.java` — `GET /`、`GET /mine`、`POST /`、`POST /{id}/approve`、`POST /{id}/reject`。
7. `condolence/application/CondolenceSeeder.java` — 表为空时播种 3 条历史已发放（周晓琳/黄浩然 云岭 project_id=2、林涛 榕荫 project_id=3）。

### 后端修改
1. `workflow/application/dto/NodeCommentRecord.java`（新）— `record(seq, comment)`。
2. `workflow/application/WorkflowService.java` — 新增 `instanceTaskComments(instanceId)`（仅取 **APPROVED** 任务，按 seq 升序）；后于本批内修复为过滤非 APPROVED 任务（见遗留/修复）。

### 前端
1. `web/src/mock/types.ts` — 新增 `CondolenceRecord` 接口（含 `workflowInstanceId?`）。
2. `web/src/api/condolence.ts` — `CreateCondolenceInput` / `listCondolences` / `myCondolences` / `createCondolence` / `approveCondolence` / `rejectCondolence`。
3. `web/src/stores/condolence.ts` — `useCondolenceStore`（load/add/approve/reject），启动即 `void load()`。
4. `web/src/views/union/CondolenceList.vue`（全量重写）— 真实列表 + 4 张统计卡；新建弹窗含 `amountToChinese` 人民币大写算法与 `el-input-number` 联动；行内「工会审批通过/驳回」走真实工作流；审批详情弹窗拉取 `getInstance(workflowInstanceId)` 用 `el-timeline` 展示流转进度与两级工会意见。
5. `web/src/views/workflow/TodoList.vue` — `BIZ_NAMES` 增补 `condolence: '慰问金'`。

## 三、设计决策
- **第二个接入 BizCompletionRegistry 的业务**：请假已验证单人审批闭环；慰问金验证「多节点 + 混合审批人（PROJECT_LEADER 解析项目部经理、USER 指定二级工会 fangjing）」闭环，确认回调模式可稳定复用。
- **流程定义落 SQL 而非 Java Seeder**：`WorkflowDataSeeder` 仅在 `process_definition` 表为空时运行，不会覆盖既有定义；故 condolence 流程定义与节点直接写进 V8 迁移以保证幂等且可重放。
- **数据范围闸门对齐 party/leave**：COMPANY 数据范围角色看全表，PROJECT 仅看本项目部；列表过滤在服务端完成，禁止前端过滤。
- **申请人服务端强制**：`create()` 取 `CurrentUserHolder.currentUsername()`，杜绝客户端伪造申请人。
- **两级工会意见回填**：流程完结时由 `instanceTaskComments` 取各节点意见写回记录，避免审批结论丢失。
- **审批意见回填的占位污染问题（已修复，见下）**：SINGLE 节点多人（如云岭有 chenguodong + limingyuan 两名 PROJECT_MANAGER）时，一人通过后另一人任务被置为 CANCELLED（comment='其他审批人已处理'）；原 `instanceTaskComments` 不过滤会把该占位 comment 覆盖真实意见。修复为仅取 APPROVED 任务。

## 四、验证记录
- 构建：后端 `mvn -DskipTests package` BUILD SUCCESS（Flyway 升至 v8）；前端 `npm run build` 通过（零 TS 错误）。
- 端到端脚本（`verify_condolence_fix.py`）走通并断言：
  - `fangjing`(COMPANY) 见全部记录；`wanghaitao`(PROJECT 云岭) **仅见本项目部记录，正确排除** 林涛/榕荫 → 数据范围隔离生效。
  - `wanghaitao` 提交新申请 → `status=待审批`，回写 `workflow_instance_id`。
  - `chenguodong`(云岭 PM) 在待办收到 node1「项目工会审批」并审批通过（status 仍 `待审批`，等待 node2）。
  - `fangjing`(二级工会) 收到 node2「二级单位工会审批」并审批通过 → `status` 翻为 `已发放`。
  - FINAL：`projectUnionOpinion='项目工会同意'`、`companyUnionOpinion='二级工会同意发放'`（**真实意见，非占位**）→ 修复生效。

## 五、遗留
- `projectUnionOpinion` 被 CANCELLED 任务占位 comment 覆盖的次要问题 **已于本批内修复**：`WorkflowService.instanceTaskComments` 改为仅返回 `APPROVED` 任务。修复后经端到端脚本复测确认两级意见均回填真实文本。
- 后续批次（按记录顺序）：公章使用登记(`seal_usage`) → 公务接待(`reception`) → 福利签收(`welfare_item`) → 党员名册(`party_member`) → 通知公告完整 CRUD → Phase 3 权限模型。
