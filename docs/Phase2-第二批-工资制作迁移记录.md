# Phase 2 · 数据中心化 第二批：工资制作（完成记录）

> 时间：2026-09-22 · 状态：**已完成并验证** · 前置：第一批（人员花名册）已交付

## 一、改动清单

### 后端（新增 salary 模块）

| 文件 | 内容 |
|---|---|
| `db/migration/V4__init_salary.sql` | salary_month / salary_record / perf_record / salary_config 四张表 |
| `salary/domain/` | SalaryRecord（31 列工资明细）、PerfRecord、SalaryMonth、SalaryConfig |
| `salary/repository/` | 四个仓储 |
| `salary/application/SalaryService.java` | **模块总闸门**：所有接口（含查询）仅 COMPANY 数据范围；越权一律 ACCESS_DENIED 审计 |
| `salary/api/SalaryController.java` | 月度列表/整月覆盖保存/删除/单条工资与绩效更新/配置读写 |

API 一览：

```text
GET    /api/salaries/months                          月度列表（含明细）
PUT    /api/salaries/months/{month}                  整月覆盖保存（Excel 导入）
DELETE /api/salaries/months/{month}                  删除整月
PUT    /api/salaries/months/{month}/records/{id}     改单条工资明细
PUT    /api/salaries/months/{month}/perf/{id}        改单条绩效
GET    /api/salaries/config                          出工日基准 + 薪档标准表
PUT    /api/salaries/config                          保存配置
```

### 前端

| 文件 | 内容 |
|---|---|
| `api/salary.ts` | 工资 API 封装 |
| `stores/salary.ts` | **重写**：localStorage（salary-months-v1）→ 全部走 API，保持原 store 接口（months/monthList/importMonth/removeMonth/updateRecord/updatePerf） |
| `utils/salaryGradeStandard.ts` | **重写**：薪档标准 + 出工日基准改为服务端配置（/api/salaries/config），persist* 全部走接口 |
| `views/hr/PayrollApproval.vue` | 导入/删月/改明细/标准重算/标准保存 5 处改为异步并带错误提示 |

## 二、关键设计决策

1. **工资模块为"总闸门"式权限**：与花名册不同，工资数据不做项目部过滤——PROJECT 数据范围用户（含项目经理）连查询都被拒绝。直接满足验收项"普通员工不能查看他人工资"，且无需等 Phase 3。
2. **NumLike 保留公式能力**：前端 `"=[岗位工资]+[奖金]"` 公式字符串原样存 varchar，重算逻辑仍在前端（工资公式是业务规则，不属于数据）。
3. **薪档标准/出工日基准 = 服务端配置**：此前存在每个浏览器 localStorage 里会导致各人算出的工资不一致，现在全部门统一一份。
4. **整月覆盖语义**：与原 importMonth 行为一致，后端先删后插、单事务。
5. 审计不含任何工资数额/身份证，仅记月份、条数、员工姓名。

## 三、验证记录（实测）

| 用例 | 结果 |
|---|---|
| gaojing（COMPANY）整月保存 1 工资 + 1 绩效 | ✅ |
| gaojing 月度列表回读 | ✅ 数据一致 |
| yuliang（PROJECT）查月度列表 / 查配置 / 试图保存 | ✅ 三次全部拒绝并写 ACCESS_DENIED |
| gaojing 改单条明细（URL 编码中文 id） | ✅ |
| gaojing 保存配置 | ✅ |
| audit_log | ✅ SALARY_IMPORT / SALARY_UPDATE / SALARY_CONFIG_UPDATE / ACCESS_DENIED ×3 全留痕，无敏感值 |
| 测试数据清理 | ✅ 三表 0 行；3 账号密码哈希已恢复（测试密码实测失效） |
| dist 复查 | ✅ 无 salary-months-v1 key、无测试数据、无身份证号 |

## 四、踩坑记录

1. **Flyway 占位符**：迁移 SQL 注释里写 `${month}-${name}` 会被 Flyway 当占位符解析导致启动失败。迁移文件注释避免 `${`。
2. **`while read` 漏最后一行**：文件末尾无换行符时循环不执行最后一行（yuliang 哈希未写入）。写临时文件必须以换行结尾。
3. 前端 Promise 化改造时破坏了 `.then()` 括号结构——TS 编译已拦截，注意链式调用块结构。

## 五、遗留

- `Home.vue` 首页的 payrollBatches/notice 仍来自 mock（首页聚合批次处理）
- 请假（leave store）仍为 localStorage（下一批）
- 党费/慰问金/公章/接待/福利/党员名册/通知（后续批次）
- 工资字段级脱敏与个人"查本人工资"接口 → Phase 3
