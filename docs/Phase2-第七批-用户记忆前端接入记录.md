# 第七批：用户记忆前端接入记录（2026-10-15）

## 结论

前端接入已完成并通过真实联调。新增「我的记忆」页面（`web/src/views/ai/MemoryCenter.vue`）与接口层
`web/src/api/memory.ts`，挂在路由 `ai/memory` 与侧边栏「AI 智能体」之后；后端补了
`PUT /api/memories/{id}` 让记忆能被真正编辑。

验证状态：后端 `mvn verify` 全绿（201 单测 + 21 集成测试），前端 `npm run build` 通过
（`vue-tsc --noEmit` + vite），浏览器端新增/编辑/召回预览/删除/归属隔离逐项点过。

## 本批改动

| 文件 | 说明 |
| --- | --- |
| `server/src/main/java/com/workbench/memory/dto/MemoryUpdateRequest.java` | 新增。编辑请求体：正文、类型、重要度、有效期 |
| `server/src/main/java/com/workbench/memory/application/MemoryService.java` | 新增 `update(id, request)`；归属校验、哈希重算、撞车报错、审计 `MEMORY_UPDATE` |
| `server/src/main/java/com/workbench/memory/api/MemoryController.java` | 新增 `PUT /{id}` |
| `server/src/test/java/com/workbench/memory/application/MemoryServiceTest.java` | 新增 11 个编辑用例（30 个用例总量） |
| `docs/decisions/2026-10-15-user-memory.md` | 追加「修改契约」一节 |
| `web/src/api/memory.ts` | 新增。接口层 + 类型定义 |
| `web/src/views/ai/MemoryCenter.vue` | 新增。记忆管理页 + 召回预览 |
| `web/src/router/index.ts` | 新增路由 `ai/memory`（`meta.title: 我的记忆`） |
| `web/src/layouts/MainLayout.vue` | 侧边栏新增「我的记忆」入口 |

## 后端：PUT 契约要点

- 请求体 `MemoryUpdateRequest(content, type, importance, ttlDays)`，除正文外皆可空。
- PUT 而非 PATCH：请求体是四个字段的目标状态，前端总是回传当前值。
- `ttlDays` 与新增语义不同：**null = 保持原有效期，0 = 改为永不过期，N = 从现在起 N 天**
  （新增时 null = 永不过期、且不允许 0）。前端表单留空即对应 null。
- 编辑后总是重算 `content_hash` 并刷新 `updated_at`。
- 撞车报 400 而非静默合并（新增路径仍是幂等合并）——否则用户刚编辑的那条会凭空消失。
- id 不存在或不属于当前用户一律 404，与删除一致。

## 前端：页面结构

- 统计卡：记忆总数（含过期数）、语义/程序/情景三类计数。
- 工具栏：新增记忆、类型筛选、刷新。
- 表格：类型标签、内容、重要度、来源、更新时间、有效期（含「已过期」标签）、编辑/删除。
- 新增/编辑弹窗：正文（textarea，1000 字上限带计数）、类型下拉（带说明文案）、
  重要度滑块（0–100，默认 50）、有效期输入（新增/编辑两种提示文案）。
- 召回预览面板：话题输入 + topK + token 预算，展示记忆块原文、命中条数、
  估算 tokens、未展开条数，并列出命中明细。

召回预览只放在记忆页，没有嵌进 AI 写作台——避免和 747 行的写作台页面耦合。

## E2E 验证记录（本地真实环境）

后端 `java -jar`（8080）+ 前端 vite（5173），登录 `chenguodong`：

| # | 场景 | 结果 |
| --- | --- | --- |
| 1 | 新增 3 条（含 ttlDays=365） | 通过，expiresAt 为一年后 |
| 2 | 列表 | 通过，按类型排序 |
| 3 | 召回预览（话题=工资表格式） | 通过，命中 3、tokens=88 |
| 4 | 召回预览（空话题） | 通过，按重要度 80→60→40 排序 |
| 5 | 编辑（改正文+类型+重要度，ttlDays=null） | 通过，expiresAt 保持为空 |
| 6 | 编辑 ttlDays=0 | 通过，expiresAt 清空（永不过期） |
| 7 | 编辑撞上别人哈希 | 400 |
| 8 | 重复新增同内容 | 幂等，返回同一条、重要度取大（90） |
| 9 | 编辑不存在的 id | 404 |
| 10 | 空正文 / 未知类型 | 400 |
| 11–14 | 换 `wanghaitao`：列表为空、召回空块、改/删他人记忆 404 | 通过，无泄漏 |
| 15 | `wanghaitao` 自写一条，`chenguodong` 列表不受影响 | 通过 |
| 16 | 删除自己的记忆，重复删除 | 通过，第二次 404 |

浏览器端另验证：页面渲染、统计卡数字、召回预览交互、新增弹窗、编辑回填与
有效期两种提示文案、删除确认框。测试数据已全部删除。

## 遗留

- 登录页用浏览器自动化填写未成功（可能验证码/风控），改用接口直接取 token 后验证页面，
  不影响功能结论。
- 召回预览的 `omittedCount` 未在前端构造出触发场景（需要 >token 预算的多条长记忆），
  文案分支已实现但未实跑。

## 提交拆分建议

1. 后端：`MemoryUpdateRequest` + `MemoryService.update` + `MemoryController` PUT + 11 个测试 + ADR 追加。
2. 前端：`api/memory.ts` + `MemoryCenter.vue` + 路由 + 侧边栏。
3. 本文档。
