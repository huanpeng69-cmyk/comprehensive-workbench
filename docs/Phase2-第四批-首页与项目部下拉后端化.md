# Phase2 第四批：首页指标与项目部下拉后端化

> 对应改造清单：**P0-2 首页 mock 指标 → 真实 API**、**P0-3 MainLayout PROJECTS 下拉 → 真实组织接口**
> 配套补齐了首页「最新公告 / 公告阅读率」所需的通知公告只读后端模块（含 Flyway V6 播种）。

## 一、改造范围

| 项 | 改造前 | 改造后 |
|---|---|---|
| 项目部下拉 (P0-3) | 前端 `mock/data.PROJECTS` 写死 5 个字符串 | `GET /api/org/units?type=PROJECT` |
| 最新公告 / 公告阅读率 (P0-2) | 前端 `mock/data.notices` 写死 5 条 | `GET /api/notices` |
| 工资指标卡 / 图表 (P0-2) | 前端 `mock/data.payrollBatches`（含 status/per-project total） | `GET /api/salaries/months`，按部门聚合 `salary_record.due_total` |

每个首页 widget **独立加载**（各自 try/catch），工资为 COMPANY 敏感数据，项目部角色被拒时仅该卡片/图表降级，不影响公告、待办、在途流程。

## 二、改动清单

### 后端新增
1. `server/.../identity/api/OrgUnitController.java`
   - `GET /api/org/units?type=PROJECT`，复用既有 `OrgUnitRepository.findByTypeOrderBySortNo`，并过滤 `enabled=true`。
   - 不做数据范围过滤（组织树为公开配置）。`type` 可选，缺省返回全部启用单元。
2. 通知公告只读模块（支撑首页）：
   - `db/migration/V6__init_notice.sql`：建 `notice` 表（id/title/category/scope/published_at/read_count/total_count/urgent/content）+ published_at 索引。
   - `notice/domain/Notice.java`、`notice/repository/NoticeRepository.java`（`findAllByOrderByPublishedAtDesc`）。
   - `notice/api/NoticeController.java`：`GET /api/notices`，全员可见，无数据范围过滤。
   - `notice/application/NoticeDataSeeder.java`：`notice` 表为空时播种 5 条演示公告（标题/阅读数与旧 mock 一致）。

### 前端新增
1. `web/src/api/org.ts` — `listOrgUnits(type?)`。
2. `web/src/api/notice.ts` — `listNotices()`。

### 前端修改
1. `web/src/layouts/MainLayout.vue`
   - 移除 `import { PROJECTS } from '@/mock/data'`，改 import `listOrgUnits`。
   - `onMounted` 调 `listOrgUnits('PROJECT')` 填充 `projectOptions`；`<el-select>` 选项绑定 `u.id / u.name / u.name`（value 仍为项目部名，与 `user.project` 及请假/工资按名联动逻辑一致）。
2. `web/src/views/home/Home.vue`
   - 移除 `notices / payrollBatches` mock 导入；新增 `noticeList / salaryMonths` 状态与 `listNotices / listSalaryMonths` 调用。
   - `readRate` 改用 `noticeList` 聚合。
   - 指标卡「待审批工资批次」重构为「工资账期」（`salaryMonths.length`）——原 mock 的 `status` 在真实 `salary_month` 表无对应字段，如实改写。
   - 图表由「各项目部应发合计」改为「各部门应发合计」：取最新账期 `records`，按 `department` 聚合 `dueTotal`（公式串回退 `importedDue`），x 轴超过 6 个部门时标签旋转。

## 三、设计决策
- **工资图表按部门而非项目部**：真实 `MonthVo` 仅有 `records`（按部门），无 per-project 合计；强行按项目部聚合会失真，故改为按部门，标题同步改为「各部门」。
- **「待审批工资批次」无真实对应**：`salary_month` 无 status 列，诚实重构为「工资账期」计数（导入了几个月份就是几）。
- **公告**：`readCount/totalCount` 为演示值，真实场景应由阅读行为回写；当前仅只读接口，不做阅读回写。
- **组织接口公开**：项目部下拉属于公开配置，不在数据范围校验内。

## 四、验证记录
- 构建：后端 `mvn -DskipTests package` → BUILD SUCCESS（repackage 成功）；前端 `npm run build`（`vue-tsc --noEmit && vite build`）通过，无 TS 错误。
- 重启后端后健康检查 `/actuator/health` → `{"status":"UP"}`。
- `GET /api/org/units?type=PROJECT`（wanghaitao/PROJECT）→ `code 0`，返回 5 条（云岭/江湾/榕荫/南岸/荔园），与原 mock 完全一致。
- `GET /api/notices`（任意登录）→ `code 0`，5 条，标题与阅读数与原 mock 一致（V6 已播种）。
- `GET /api/salaries/months`：
  - fangjing（COMPANY）→ `code 0`，`0` 条（演示库尚未导入工资数据，**真实呈现为空**）。
  - wanghaitao（PROJECT）→ `code 400`，msg「工资数据仅限全公司数据权限用户访问」，首页正确捕获降级。

## 五、踩坑
- `mvn.cmd` 在 Git Bash 下**不识别 forward-slash 的 `JAVA_HOME`**（报 “JAVA_HOME … not defined correctly”）。修复：用 Windows 风格 `JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot'` 同命令行前缀，或改用 `bin/mvn` sh 脚本。
- 旧后端进程占用 jar 会导致 `repackage` 无法重命名 → 先 `taskkill /F /PID <pid>` 再打包。

## 六、遗留 / 后续
- 通知公告仅只读；完整 CRUD（发布/编辑/阅读回写 `read_count`）见后续批次。
- 项目部下拉目前只读组织树；与请假/工资等模块按 `project_name` 联动的筛选尚未统一接入。
- 工资图表在演示库无数据时为空，属真实状态；导入工资数据后自动呈现。
- 改造清单剩余：P1（4-8）、P2（9-13）未启动。
