# Phase2 第五批：党费模块后端化

> 对应改造清单：党费模块迁移（Phase0 盘点表「party_fee /api/party-fees · 敏感」）。
> 记录的批次顺序：请假 → 首页聚合 → **党费/慰问金** → 公章/接待/福利/党员/通知。

## 一、改造范围

| 项 | 改造前 | 改造后 |
|---|---|---|
| 党费季度明细页 (`party/PartyFees.vue`) | `mock/data.partyFees` 写死 6 条 + 季度写死 `['2026-Q3']` | `GET /api/party-fees`，季度选项从数据派生 |

只读模块：现有 UI 无编辑/审批功能，故本批仅提供查询接口；写入（收缴流程）留待有真实编辑 UI 的批次。

## 二、改动清单

### 后端新增（`com.workbench.party`）
1. `db/migration/V7__init_party_fee.sql` — `party_fee` 表：name / quarter / base1-fee3（三个月基数与党费）/ total，`numeric(12,2)`，quarter 索引。
2. `party/domain/PartyFee.java` — JPA 实体（BigDecimal 金额字段）。
3. `party/repository/PartyFeeRepository.java` — `findByQuarterOrderByIdAsc` / `findAllByOrderByIdAsc`。
4. `party/application/PartyFeeService.java` — **敏感数据闸门**：所有查询仅限 COMPANY 数据范围角色（与工资模块同策略），拒绝时写 `ACCESS_DENIED` 审计（`targetType=PARTY_FEE`）。
5. `party/api/PartyFeeController.java` — `GET /api/party-fees?quarter=`（quarter 可选）。
6. `party/application/PartyFeeSeeder.java` — 表为空时播种 2026-Q3 六名党员明细（与 mock 一致）。

### 前端
1. `web/src/api/partyFee.ts`（新）— `listPartyFees()`。
2. `web/src/views/party/PartyFees.vue`（重写 script）— 移除 mock 导入；`onMounted` 拉取；季度选项由数据 `Set` 派生；加载态 `v-loading`；接口失败（含非 COMPANY 角色被拒）显示「暂无查看权限 + 后端原因」的友好空态，不再白屏。

## 三、设计决策
- **闸门策略对齐工资模块**：党费缴费基数直接关联工资，Phase0 已标「敏感」，故读也限 COMPANY 数据范围；PROJECT 角色拒绝并审计。按项目部分范围的查询留待 Phase 3 权限模型。
- **合计由后端勾稽**：播种时 `total = fee1 + fee2 + fee3`。**发现 mock 合计列 6 条中 5 条与分月之和不符**（如陈国栋 14.59+13.93+14.21=42.73，mock 写 56.94；仅曹磊吻合），入库后以勾稽一致的值为准。
- **只读接口**：现有 UI 无编辑功能，不预造写接口（拒绝分期交付的反向应用——不做 UI 不需要的东西）。

## 四、验证记录
- 构建：后端 `mvn -DskipTests package` BUILD SUCCESS；前端 `vue-tsc && vite build` 通过。
- 重启后 V7 自动应用，Seeder 播种 6 条。
- fangjing（COMPANY）`GET /api/party-fees` → `code 0`，6 条，季度合计 402.88（分月勾稽一致）。
- `GET /api/party-fees?quarter=2026-Q3` → `code 0`，6 条（季度过滤生效）。
- wanghaitao（PROJECT）→ `code 400`，msg「党费数据仅限全公司数据权限用户访问」，前端显示友好空态。

## 五、遗留
- 党费收缴的写入流程（录入/导入/审批）待有真实业务 UI 时再建。
- 导出按钮仍为前端提示桩（改造前即如此），未在本批范围。
- 数据范围目前仅 COMPANY 一档；按项目部的党费统计待 Phase 3。
- 剩余批次：慰问金（含工会审批流，接入 BizCompletionRegistry）→ 公章/接待/福利/党员 → 通知完整 CRUD。
