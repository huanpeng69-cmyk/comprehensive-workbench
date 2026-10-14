# Phase 2 · 数据中心化 第一批：人员花名册（完成记录）

> 时间：2026-09-22 · 状态：**已完成并验证** · 前置：Phase 1 已交付

## 一、本次改动清单

### 后端（新增 hr 模块）

| 文件 | 内容 |
|---|---|
| `db/migration/V3__init_employee.sql` | employee 表（27 字段 + 项目外键 + 索引），Flyway 自动执行 |
| `hr/domain/Employee.java` | 实体，敏感字段 idCard/phone/monthlyPay 落库 |
| `hr/repository/EmployeeRepository.java` | 全量/按项目部查询 |
| `hr/application/EmployeeService.java` | 数据范围过滤 + 项目归属解析 + 审计 + 越权守卫 |
| `hr/api/EmployeeController.java` | GET/POST/PUT/DELETE `/api/employees`、POST `/api/employees/import` |
| `identity/repository/OrgUnitRepository.java` | 补 `findFirstByName`（项目名称→org_id 匹配） |
| `identity/repository/RoleRepository.java` | 补 `findByCodeIn`（数据范围判断） |
| `security/CurrentUserHolder.java` | 补 `currentRoleCodes()`（从 JWT 读角色码） |

### 前端

| 文件 | 内容 |
|---|---|
| `api/http.ts` | 补 `put`/`del` 方法 |
| `api/employee.ts` | 花名册 5 个 API 封装 |
| `stores/roster.ts` | **重写**：localStorage 持久化 → 全部走 API；页面初始化自动拉取 |
| `views/hr/StaffList.vue` | 新增/编辑/删除/导入改为异步带错误提示；「恢复种子」改为「从服务器重新加载」；项目下拉与默认值对齐真实组织（红茶项目部等） |
| `mock/rosterOptions.ts` | PROJECT_OPTIONS 对齐真实组织 |
| `mock/data.ts` | **删除花名册种子数据**（首批验证后清除，符合"逐模块删 mock"原则） |

## 二、关键设计决策

1. **空表起步 + Excel 导入建数**：数据库 `org_unit` 中是真实组织（天河/白云机场/钟村/车棠/红茶项目部），绝不把演示假数据写入真实库。真实花名册由用户通过页面 Excel 导入进入数据库（复用现有 `rosterExcel.ts` 解析）。
2. **数据范围在 SQL 层强制**：COMPANY 角色见全部；PROJECT 角色只查 `project_id = 本人 projectId`；无项目归属者见空列表。禁止前端过滤。
3. **写权限守卫**：
   - 新增/整体导入（全表级）→ 仅 COMPANY 数据范围
   - 修改/删除 → 本人项目部（未分配记录仅 COMPANY 可改）
   - 越权尝试 → 写 `ACCESS_DENIED` 审计 + 业务报错
4. **项目归属解析**：按名称匹配 `org_unit`；匹配不到 → `project_id` 置空（未分配，仅 COMPANY 可见）。
5. **旧 localStorage 数据处理**：`roster-list-v1` 不再读写（留作残骸无害）；真实历史数据需通过 Excel 导入重新进入数据库。
6. **审计不留敏感字段**：审计 detail 只含姓名/项目/条数，不写身份证/手机号/月薪。

## 三、验证记录（全部实测通过）

| 用例 | 结果 |
|---|---|
| gaojing（HR_ADMIN+FINANCE，COMPANY）导入 3 条 | ✅ 成功，返回条数 3 |
| 项目名称→org_id 匹配（天河→2、红茶→6、未分配→null） | ✅ 正确 |
| gaojing 列表 | ✅ 3 条全见 |
| guanxin（PROJECT，天河项目部）列表 | ✅ 仅见本项目部 1 条 |
| yuliang（PROJECT，白云机场）列表 | ✅ 0 条 |
| yuliang 尝试导入 | ✅ 拒绝："该操作需要全公司数据权限" |
| guanxin 越权改红茶项目部员工 | ✅ 拒绝："无权操作其他项目部的员工数据" |
| guanxin 修改本项目部员工 | ✅ 成功 |
| yuliang 删除未分配员工 | ✅ 拒绝 |
| audit_log | ✅ EMPLOYEE_IMPORT/UPDATE/ACCESS_DENIED 全部留痕，无敏感字段 |
| 前端构建 | ✅ 通过；dist 中花名册身份证/测试数据 0 残留 |
| 测试数据清理 | ✅ 3 条测试员工已删；3 个测试账号密码哈希已恢复原值 |

## 四、遗留与下一步

1. **浏览器多用户实测**（需真人操作）：同事 A（COMPANY 角色）与 B（PROJECT 角色）分别登录 `http://服务器IP/`，A 导入 Excel 花名册 → B 刷新只看到本项目部人员。
2. **工资制作（PayrollApproval）依赖花名册**：其薪档勾稽读 `rosterStore.list`，现已自动改为数据库来源；工资数据本身的 localStorage 迁移为**下一批**（敏感级最高）。
3. **MainLayout 头部的 PROJECTS 常量**仍来自 mock（组织迁移批次处理）。
4. **身份证/手机号脱敏展示**与字段级权限 → Phase 3（权限模型）统一实现。
5. 待迁移批次：组织/部门 → 工资批次 → 党费 → 慰问金 → 其余办公/工会模块（见 Phase 0 报告迁移表）。

## 五、踩坑记录（防再犯）

- **Python 在 Windows 文本模式写文件带 `\r\n`**：把 bcrypt 哈希写入文件再经 bash 读入变量时，末尾 `\r` 会混进 SQL 更新值，导致库中哈希 61 字节、登录永远失败。修复：`tr -d '\r'` 处理后写入，且用 `length(password_hash)=60` 校验。
- **jar 被运行中进程锁定**：Windows 下 Spring Boot 运行时重新打包会失败（Unable to rename）。必须先停后端再 `mvn package`。
- **本机无 docker compose 插件**：统一用独立 `docker-compose` 命令。
