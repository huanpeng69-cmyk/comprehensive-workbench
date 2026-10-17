# 岗位工资假别处理：年假全额 / 事假全额扣 / 病假等人工填写

日期：2026-10-17

## 1. 本次口径（业务确认）

| 假别 | 处理方式 | 谁算 |
|---|---|---|
| **年假** | 全额发放，不扣岗位工资 | 系统自动 |
| **事假** | 全额扣除，扣款 = 日工资标准 × 事假天数 | 系统自动 |
| **病假及其他假别** | **不对应固定扣款规则**，界面预留可填写项，由用户自行输入扣款金额 | 人工填写 |

辅以默认带薪假（同样全额发放、不扣）：调休、婚假、产假、护理假、丧假、工伤假。
未在配置中列出的假别（如探亲假）**一律按人工处理**，不自动扣、不猜规则。

> 与上一版的差异：上一版给病假设了「计发比例 0.6」自动折算。那是系统替业务做假设，
> 本次按要求取消，改为人工填写。

## 2. 计算规则

```
满勤岗位工资   = 日工资标准 × 应出工日
事假扣款       = 日工资标准 × 事假天数          ← 系统自动
其他假别扣款   = 用户填写值                     ← 人工，系统原样计入
岗位工资       = 满勤岗位工资 − 事假扣款 − 其他假别扣款
```

岗位工日 = 应出工日 − 事假天数（年假不减；病假不自动折算天数）。

**默认参数**：应出工日 21.75（沿用既有配置 `payrollConfig.standardWorkdays`）。

## 3. 界面输入项

工资制作页「编辑工资记录」弹窗 → 「其他扣除与专项附加」分组内新增字段：

**字段名**：`其他假别扣款`（列 `leave_deduct`，varchar，兼容 `=公式`）

配套提示（同分组下方常驻）：

> 「其他假别扣款」用于病假等不对应固定扣款规则的假别：系统不自动计算，由你按制度填写扣款金额。
> 年假全额发放、事假按日工资自动全额扣除，均无需在此填写。
>
> **本期该员工需人工填写的假别：病假 3 天**（醒目色，仅当存在此类假别时出现）

第三行是动态提示：从请假记录里取出该员工当月已批准、且口径为「人工」的假别，提醒不要漏填。
无此类假别时该行不显示，避免误导。

## 4. 计算结果的三种情形（日工资 200，应出工日 21.75 → 满勤 4350）

| 情形 | 事假扣款 | 人工扣款 | 岗位工资 |
|---|---|---|---|
| 年假 5 天 | 0 | 0 | **4350**（全额） |
| 事假 2 天 | 400 | 0 | **3950** |
| 病假 5 天，未填 | 0 | 0 | **4350**（+ 告警提示待填） |
| 病假 5 天，填 500 | 0 | 500 | **3850** |
| 年假 5 + 事假 2 + 病假 3，填 300 | 400 | 300 | **3650** |

## 5. 如何验证

### 5.1 可执行断言（69 项，全部通过）

```bash
cd web && node tools/verify-salary-basepay.mjs
```

覆盖：
- 年假全额（单独、与调休叠加）
- 事假全额扣（2 天、0.5 天、多条累加）
- 病假人工填写（未填、填 500、填 0.01、超额到 0）
- 其他假别（探亲假→人工、婚假/产假/护理假→带薪）
- 混合场景与边界（无请假、无人工假别却填钱、日工资为 0）
- **端到端公式求值**：用真实公式串 `=[薪档标准.日工资标准]*[应出工日]-[薪档标准.日工资标准]*[请假.事假]-[其他假别扣款]` 求值，并校验**函数路径与公式路径结果完全一致**（5 组）

期望值全部手算，每条断言写明依据。

### 5.2 后端

```
mvn -f server\pom.xml verify
→ Tests run: 201, Failures: 0, Errors: 0   （单元）
→ Tests run: 21,  Failures: 0, Errors: 0   （集成，含 V17 迁移）
→ BUILD SUCCESS
```

### 5.3 前端

```
cd web && npm run build     # vue-tsc --noEmit + vite → EXIT=0
```

### 5.4 持久化实测（真实后端 + 真实 PostgreSQL）

用 `chenguodong` 登录，`PUT /api/salaries/months/2026-09` 写入 `leaveDeduct=500`、`sign=SIGNED`，
再 `GET /api/salaries/months` 读回：

```
leaveDeduct=[500]  sign=[SIGNED]  postSalary=[4350]
```

确认新列可写可读、且未影响既有字段。测试数据已删除（`salary_record` / `salary_month` 现均为 0 行）。

数据库列已确认存在：

```
leave_deduct | character varying
```

## 6. 改动清单

| 文件 | 改动 |
|---|---|
| `server/src/main/resources/db/migration/V17__salary_leave_deduct.sql` | **新增**。为 `salary_record` 加 `leave_deduct` 列 |
| `server/src/main/resources/db/rollback/V17__rollback.sql` | **新增**。幂等回滚，如实声明会丢该列数据 |
| `server/src/main/java/com/workbench/salary/domain/SalaryRecord.java` | 新增 `leaveDeduct` 字段 |
| `server/src/main/java/com/workbench/salary/application/SalaryService.java` | `copyEditable` 覆盖新字段（否则编辑会丢值） |
| `server/src/test/java/com/workbench/schema/SchemaBaselineIT.java` | 迁移计数 16→17，版本列表补 `"17"` |
| `web/src/utils/salaryBasePay.ts` | 重写为三档口径：`PAID` / `DEDUCT_FULL` / `MANUAL`；`calcBasePay` 接收人工扣款 |
| `web/src/mock/salaryOptions.ts` | 字段标签加 `其他假别扣款`；岗位工日与岗位工资公式改为新口径 |
| `web/src/mock/types.ts` | `SalaryRecord` 加 `leaveDeduct` |
| `web/src/utils/salaryExcel.ts` | Excel 导入补 `leaveDeduct: 0`（表里无此列，留给人工填） |
| `web/src/views/hr/PayrollApproval.vue` | 弹窗新增输入项 + 常驻说明 + 动态「需人工填写」提示；扣款合计纳入该项；重算文案更新 |
| `web/tools/verify-salary-basepay.mjs` | 按新口径重写，69 项断言 |

**向后兼容**：`[请假.当月天数]` 与 `leaveDaysFor()` 保留未删，历史工资记录里的旧公式仍可求值。

## 7. 已知限制 / 需要确认

1. **人工扣款填 0 与「未填写」不可区分。** 数据库里空值表示未填（按 0 处理并告警），
   但用户显式填 0 与留空在库里都是 `0`。若需要严格区分，得再加一个「已确认」标记位。
2. **告警只在前端。** 后端仍不参与计算（沿用既有架构），服务端无法校验扣款是否合理。
   要真正把关，需按评审文档里「后端接管勾稽」那一步推进。
3. **病假天数不影响工日。** 按新口径病假只影响金额（人工填），岗位工日只扣事假。
   若业务上希望病假也体现在工日列，需要另行确认。
4. **病假是否带薪、带多少完全由人工判断**，系统不做任何合规性校验（如最低工资下限）。
   上一版的 `minMonthlyWage` 下限提示已随比例逻辑一并移除。
5. 浏览器 GUI 自动化本轮不可用（`browser guest is not available`），
   界面验证改用「源码 + 构建产物关键字 + 真实接口持久化」三路确认，
   未做像素级交互点击。
