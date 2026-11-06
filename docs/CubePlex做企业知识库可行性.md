# CubePlex 做企业知识库合适吗

> 依据：CubePlex 后端源码实读（`pyproject.toml`、`models/`、`services/`、`conversation_search/`、README、官方 Core Concepts 文档）。
> 分档：**【实测】**= 本次读源码确认；**【文档】**= 官方文档；**【判断】**= 推论。

---

## 1. 结论

**不合适——至少不是「开箱即用的企业知识库」。**

但要说清楚为什么，因为 CubePlex **确实有 RAG 的完整技术栈**，只是它的检索对象是**对话历史**，不是**企业文档**。这是一个非常容易误判的地方。

一句话概括：

> **CubePlex 有「会搜东西的大脑」，但它搜的是「自己聊过什么」，不是你公司的制度文件、合同、图纸。要做企业知识库，得你自己把这些文档接进去。**

---

## 2. 关键证据（源码实读）

### 2.1 它有完整的 RAG 工程，但是面向会话的

`services/conversation_search/` 目录下是一套**完整的检索实现**：**【实测】**

```
chunker.py      分块
embedding.py    向量化
indexer.py      建索引
rrf.py          RRF 混合排序（向量 + 关键词）
worker.py       异步索引任务
snippet.py      片段生成
history.py      历史检索
startup.py      启动自检（向量维度漂移检测）
```

这套东西质量不低——有 RRF 融合、有维度漂移三向检查、有异步 worker。**但看它的数据模型就明白服务对象是谁。**

### 2.2 决定性证据：向量索引只建在「对话消息」上

`models/conversation_chunk.py` 第 1 行开宗明义：**【实测】**

```python
"""Search index chunk — sliding window over a conversation's messages."""
```

表名 `conversation_chunks`，字段里有 `conversation_id`、`chunk_seq`、`seq_lo`、`seq_hi`——**这是对一段对话做滑动窗口切块**，然后存 `Vector(1024)` 嵌入。

**含义**：这套向量检索的用途是「让 Agent 能搜到自己和团队过去聊过什么」，也就是**会话记忆的历史回溯**，不是「检索公司文档库」。

### 2.3 没有知识库模块

`backend/cubeplex/` 全部模块清单：**【实测】**

```
agents api audit auth cache cli credentials db errors i18n im lifecycle
llm mcp memory middleware models objectstore parsers plugins prompts
repositories sandbox sandbox_env sandbox_policy schedules scripts
seeders services skills sso streams templates templates/email tools
triggers utils
```

**没有 `knowledge`、`knowledge_base`、`rag`、`retrieval`、`documents`、`corpus` 任何一项。**

`services/` 目录 50 个文件里，也没有任何知识库相关服务。

### 2.4 文档解析能力存在，但止步于「解析」

`pyproject.toml` 里有文档解析插件注册：**【实测】**

```
cubeplex.parsers:
  text       → TextParser
  notebook   → NotebookParser
  docling    → DoclingParser
```

能从 PDF/Word 里抽出**文本**。但 `parsers/` 模块只有 7 个文件（`dedup / mime / protocols / registry / schema`），**是一个解析框架，不是知识库管线**。

类比：**它有「把文件读成文字」的能力，但没有「把文字变成可检索知识库」的产品层。**

### 2.5 README 完全没有提知识库

全文检索 README：「知识库」0 次、「向量」0 次、「RAG」0 次、「embedding」0 次、「pgvector」0 次。**【实测】**

对照它的能力表——列了 13 项能力（沙箱、记忆、MCP、技能、产物、自动化、IM 桥接……），**唯独没有「知识库」这一项**。

> 这不是疏漏，是它不在产品范围内。

---

## 3. 它实际能提供的「近似知识库」能力

公平地说，CubePlex 有三条路可以**拼出**一个知识库，但都需要你自己动工：

| 路径 | 做法 | 能达到什么 | 缺什么 |
|---|---|---|---|
| **A. MCP 工具接外部知识库** | 写一个 MCP connector，指向你已有的向量库/RAG 服务 | 最简单，官方文档明确支持 | 知识库本身要你自己有 |
| **B. 文件放沙箱里，让 Agent 读** | 上传到工作区沙箱，Agent 用代码检索 | 小规模可行 | 无索引、无权限、每次全量读 |
| **C. 靠 Memory 三层** | 把知识写进 Workspace / Org 级记忆 | 适合少量事实、规范 | **不是文档检索**——条目式，不是全文/向量检索 |

**推荐路径 A**：CubePlex 官方文档明确支持 MCP 三种鉴权（静态凭据 / OAuth / 无），接一个外部 RAG 服务是它的标准用法。**【文档：core-concepts.md】**

**但要看清**：这条路等于「**CubePlex 只做前端和 Agent，知识库是你的另一套系统**」。这样它就是个入口，不是知识库。

---

## 4. 对比：如果目标只是企业知识库

这里必须说清楚一件事——**如果你的核心需求是「企业知识库」，CubePlex 是本末倒置的选择。**

| 需求 | 专业知识库方案（Dify/RAGFlow/FastGPT 等） | CubePlex |
|---|---|---|
| 文档上传 → 解析 → 分块 → 向量化 | ✅ 开箱即用 | ❌ 要自己写 |
| 知识库管理界面（增删改、重建索引） | ✅ 内置 | ❌ 无 |
| 多知识库/多租户隔离 | ✅ 内置 | ❌ 只有 workspace 级 |
| 检索效果调优（rerank、召回策略） | ✅ 有配置项 | ⚠️ 只有 RRF，且面向会话 |
| 文档级权限 | ✅ 通常有 | ❌ 无 |
| **持久沙箱、跑代码、出产物** | ❌ 一般没有 | ✅ **这是它的强项** |
| **跨 6 个 IM 渠道** | ⚠️ 部分支持 | ✅ **强项** |
| 长期 Agent + 团队资产 | ❌ | ✅ **强项** |

**结论很清晰**：
- **只想做知识库** → 用专业 RAG 产品，CubePlex 是杀鸡用牛刀且缺零件。
- **想要「能动手干活的 AI 同事」，顺便让它查知识库** → CubePlex + MCP 接外部 RAG，合理。

---

## 5. 如果你打算用 CubePlex 做知识库，会遇到什么

按难度从高到低：

### 难点一：没有文档级权限（最致命）

CubePlex 的权限只有 **Organization / Workspace** 两级。**【文档】**

如果你企业知识库有分级——比如：
- 制度文件：全员可见
- 工资相关：仅 HR
- 合同：仅法务
- 你对齐的「按项目部隔离」

**它做不了。** 这需要你在 MCP 工具层自己实现，等于知识库的权限系统完全自建。

> 这一点和你现有工作台的 `DataScopeResolver`（按 company/project 两级裁剪）是同一个问题——CubePlex 的权限模型比你的还粗。

### 难点二：向量维度绑定在配置文件

`VECTOR_DIM = int(config.get("search.embedding.vector_dim", 1024))`，且注释写明**换维度要在全新 schema 上跑迁移**。**【实测】**

这意味着换 embedding 模型 = 重建整个索引。做知识库时这是常态操作（换更好的模型），会很痛。

### 难点三：没有索引管理界面

没有「知识库列表」「重建索引」「查看解析结果」「调整分块大小」这些运维入口。全部要靠 API 或改代码。**【实测：services 无相关服务】**

### 难点四：现有 RAG 是为会话优化的

`conversation_chunks` 用滑动窗口切对话（`seq_lo/seq_hi`），这套切分逻辑**不适合长文档**——文档需要按标题层级、段落语义切，不是按消息序列。复用会失真。

---

## 6. 最终建议（针对你的情况）

结合前三轮（你的项目是 Spring Boot 工作台，已有 `kbproxy` + 外部知识库）：

### 你其实已经有知识库了

你的项目里：**【前几轮实测】**

- `kbproxy` 模块（`KbProxyController`，78 行）
- `KnowledgeBaseTool`（148 行）走 `X-Kb-Service-Token` 调外部知识库 `/api/query`
- 配置项 `workbench.kb.base-url`（默认 `:8765`）

**架构上你已经做对了**：Agent 通过工具调外部知识库，而不是把知识库塞进 Agent。

### 所以三个场景的答案

| 你的目标 | 建议 |
|---|---|
| 给工作台配企业知识库 | ✅ **继续用现有 `kbproxy` + 外部知识库**。不要引入 CubePlex——你已经有更好的（数据权限按项目部裁剪，CubePlex 做不到） |
| 想升级知识库的检索质量 | ✅ 优化外部知识库（换 embedding、加 rerank、调分块），**与 CubePlex 无关** |
| 想给团队配能做技术活的 AI 同事 | ✅ 用 CubePlex，但**通过 MCP 接你现有的知识库**，别指望它自带知识库 |

### 一句话

**CubePlex 适合「带知识库的 AI 同事」，不适合「做知识库」。** 它的知识库能力是「通过 MCP 连别人家的」，不是「自己是一个」。

---

## 附：证据索引

| 结论 | 证据 | 类型 |
|---|---|---|
| 无知识库模块 | `backend/cubeplex/` 34 个模块无 knowledge/rag/retrieval | 【实测】 |
| 无知识库服务 | `services/` 50 个文件无相关服务 | 【实测】 |
| 向量索引面向会话 | `conversation_chunk.py` L1 docstring | 【实测】 |
| RAG 工程完整但对象是对话 | `conversation_search/` 11 个文件 | 【实测】 |
| 有文档解析但仅止于解析 | `pyproject.toml` parsers 三项 + `parsers/` 7 文件 | 【实测】 |
| README 零提知识库 | 全文检索 0 次命中 | 【实测】 |
| 权限仅 org/workspace 两级 | `core-concepts.md` Roles 表 | 【文档】 |
| MCP 支持接外部服务 | `core-concepts.md` MCP 三种鉴权 | 【文档】 |
| 向量维度需重建索引 | `conversation_chunk.py` L13-17 注释 | 【实测】 |
