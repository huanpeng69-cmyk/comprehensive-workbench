# ADR-M017：Agent 决策轮改为流式（省掉二次模型调用）+ tool_calls 分片累积契约

- 状态：已采纳
- 日期：2026-11-06
- 关联：`server/src/main/java/com/workbench/ai/application/AgentService.java`
  （`streamDecision` / `parseDelta` / `accumulateToolCall` / `buildToolCalls` / `record DecisionStream`）
- 关联测试：`AgentServiceToolCallAccumulationTest`

## 背景

旧实现把「决策」和「作答」拆成两次 HTTP 调用：

1. `chatNonStream(...)`：带 `tools` 发一次**非流式**请求，拿到完整 JSON 判断是 `tool_calls`
   还是纯文本；
2. 若判定为纯文本，再发一次 `stream: true` 请求，把**同一个问题重新生成一遍**并流式转发。

由此产生的实际缺陷：

- **模型被调用两次**。第一次的完整输出被直接丢弃，只用来「判断意图」——
  纯文本场景下这是整轮对话里最贵的一次浪费（token 与首字延迟都翻倍）。
- **首字延迟翻倍**：用户看到的「思考中」要等两趟往返，第一趟还完全不产出可见内容。
- 两趟的生成结果可能不一致（temperature 0.7，非确定性），最终答案与模型第一次的判断未必对应。

工具调用路径只走第一趟，反而没有这个问题——缺陷只在最常用的纯文本路径上。

## 备选

- **S1 决策也流式，文本 delta 直接转发**（采纳）：一次请求同时拿到文本增量与
  `tool_calls` 增量，纯文本场景零额外调用，工具场景照旧执行工具后进入下一轮。
- **S2 保留两次调用，但让第二次复用第一次的结果**：非流式响应里本来就没有增量，
  复用等于自己把固定文本切片后「假装流式」推给前端，首字延迟仍是两趟。**不采用。**
- **S3 决策仍非流式，直接不要流式作答**：牺牲体感，且纯文本大答案要一次性憋完。
  **不采用。**
- **S4 用 `stream: true` 但只在 `tool_calls` 上生效（收到即停）**：模型先吐一段文本再决定调用工具时，
  已经吐出的文本无法退回给模型，语义不清。**不采用。**

## 决策

`runAgent` 的决策循环改为调用 `streamDecision(...)`（一次 `stream: true` + `tools` 请求），
其内部边读边处理三类 delta：

1. **文本增量** `choices[0].delta.content` → 立即以既有 `message` 帧推给前端，并累积进
   `StringBuilder text`；`safeSend` 失败即判定客户端已断开（`clientGone`），停止读流。
2. **工具增量** `choices[0].delta.tool_calls` → 交给 `accumulateToolCall` 累积（见下方契约）。
3. 其余帧（心跳、非 JSON）忽略。

读完后的分支：

- `toolCalls` 非空 → 把累积出的 `assistant` 消息（含 `tool_calls`）留在上下文，逐个执行工具并回灌，
  进入下一轮决策；**已经流出的文本不回收**。
- `toolCalls` 为空 → `answered = assistantMessage().containsKey("content")` 后 break；
  一个字都没吐时（罕见）留 `answered = false`，交给循环外既有的兜底 `streamAnswer` 再要一次。
- `clientGone` → 补一条 `AI_AGENT` 总账后 return（口径与循环前的取消检查一致）。

### tool_calls 分片累积契约

OpenAI 流式的 `tool_calls` 是**分片**下发的，这是本次改动最容易踩错的地方：

- 同一响应里的多个工具用 `index`（0、1、2…）区分，分片会**交错**到达；
- `id` 与 `function.name` **只在首帧**出现，后续帧只带 `function.arguments` 的**字符串片段**；
- 因此绝不能「每帧当成一次独立调用」——那样会拿到只含半截 JSON 的调用，参数解析必然失败
  （或更糟：静默丢参数，工具按空参执行）。

`accumulateToolCall(acc, deltaToolCalls)` 的规则：

- 槽位按 `index` 归并；`index` 缺失退化为 `0`（兼容极少数单工具不发下标的实现）；
- `id` / `name` 只在值非 null 且非空串时写入，**后续帧不会把首帧的值覆盖成 null 或空串**；
- `arguments` 用 `StringBuilder` 逐帧 append；
- 帧里不是 List、或数组为空 → 不建槽（空 `delta` 不该凭空造出一个调用）。

`buildToolCalls(acc)` 还原为可直接回灌上下文的形状：
`{id, type:"function", function:{name, arguments}}`，`arguments` 缺省为空串 `""` 而非字符串 `"null"`。
累积容器用 `TreeMap`，保证多个工具的还原顺序按 `index` 升序、与到达顺序无关。

## 契约与可见性

- **对外接口零改动**：`/ai/agent` 的请求/响应、SSE 帧类型（message / tool_call / tool_result /
  done / error）与 `event("message", Map.of("content", s))` 的帧结构全部不变，前端零改动。
- `streamAnswer(...)`（无工具直连路径 + 文本兜底）**保持原样**，未参与本次重写。
- `parseContentDelta` 改为 `parseDelta` 的薄封装：单一解析出口，避免两套 JSON 解析逻辑，
  同时让 `streamAnswer` 零改动、零回归风险。
- `accumulateToolCall` / `buildToolCalls` 为**包级可见静态方法**，便于测试直接断言
  （与 `trimHistory`、`resolveCaller` 的既有做法一致）。
- **一并删除**：`chatNonStream`、`firstMessage`、`asToolCalls`，以及只为它们存在的
  `HttpEntity` / `HttpStatusCodeException` / `ResourceAccessException` import。
  这三个方法在决策路径全改流式后已无任何调用点。
  **说明**：此前向用户表述的是「除 ③ 外不动 `chatNonStream`」，实际因它失去全部调用点而一并删除，
  属于本次重写范围内的死代码清理，此处如实记录这一偏离。
- `RestTemplateProxySupport.describeProxy()` 仍保留，用于 `streamDecision` 失败时的出网代理描述
  （排障需要「当前走哪个代理」，不能因为换路径丢掉这条信息）。

## 验证

- `mvn -o test`：**265 tests / 0 failures / 0 errors，BUILD SUCCESS**（255 + 新增 10 例）。
- 新增 `AgentServiceToolCallAccumulationTest`（10 例）覆盖：`arguments` 三帧拼成完整 JSON /
  两个工具按 index 1、0 交错到达后仍归位正确且顺序按 index 升序 / `id`+`name` 仅首帧且后续帧
  的空串不覆盖 / `arguments` 整帧缺失 → `""` / 还原结果含 `type:"function"` / 空累积器 → 空列表 /
  `delta.tool_calls` 为 null、字符串、Map、空数组 → 不建槽 / `index` 缺失退化 0 /
  `name` 缺失保持 null 而非 `"null"`。
- **未执行**：`mvn -o verify`（`target/workbench-server-0.1.0.jar` 被运行中的 java 进程占用，
  Windows 文件锁；本改动不涉及 schema 与接口）。
- **未执行**：对真实模型端点的端到端冒烟（需要停掉占用 8081 的运行实例重新打包起 jar，
  待环境收尾时一并验证）。

## 回滚

恢复旧的两趟调用：新增回 `chatNonStream` / `firstMessage` / `asToolCalls` 与三个 import，
把 `runAgent` 里 `streamDecision(...)` 换回非流式决策 + 纯文本时二次 `streamAnswer`，
删除 `streamDecision` / `record DecisionStream` / `accumulateToolCall` / `buildToolCalls`
与 `TreeMap` import（`parseDelta` 可保留，`parseContentDelta` 已是它的薄封装）。
无数据结构变更、无迁移、无配置项。

## 后续

- 工具执行后进入下一轮时，新一轮的文本 delta 会**接着**前面的文本继续推给前端，前端按到达顺序追加即可；
  若将来要区分「本轮的第几段文本」，需要在 `message` 帧里加字段——属接口变更，需另立 ADR。
- `stream_options.include_usage` 尚未开启，因此拿不到 token 用量，`MAX_HISTORY_CHARS`
  （ADR-M016）只能继续按字符估算。是否开启待评估国产网关的兼容性。
- 若立项②「会话持久化」落地，决策流式的读流逻辑可复用，但持久化落账时机要重新设计
  （流式过程中中断的会话如何收尾）。
