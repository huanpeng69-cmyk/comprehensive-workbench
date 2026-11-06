# ADR-M016：Agent 上下文长度治理（字符预算 + 从旧到新淘汰）

- 状态：已采纳
- 日期：2026-11-06
- 关联：`server/src/main/java/com/workbench/ai/application/AgentService.java`
- 关联测试：`AgentServiceHistoryTrimTest`

## 背景

`AgentRequest` 的设计是「服务端无状态编排」：客户端每轮把**完整**对话历史发上来。
原实现只做两件事：

1. `MAX_PROMPT_LENGTH = 8_000` 限制 system 提示词；
2. `MAX_CONTENT_LENGTH = 30_000` 对**每条**消息单独截断。

两者都防不住**总量**。第 N 轮请求体约等于前 N−1 轮之和，随轮次线性增长，最终撞上模型
上下文窗口，模型返回 400，用户看到的是一句与「对话太长」毫无关系的错误。

同时，单条 30_000 字截断本身是有损的：多轮追问里模型看到的是**残缺的前半段**，
它会基于半截内容继续作答，比直接报错更难排查。

另外 `WEB` 端 `WritingStudio.vue` 侧无轮数上限，也没有历史淘汰，所以这个缺陷是必然触发的，
只是触发得晚。

## 备选

- **S1 摘要压缩**：超预算时调一次模型把早期对话压成摘要再带上。
  效果最好，但每轮可能多一次计费调用，且摘要本身也要占预算、失败路径要降级，
  复杂度与「省下来的钱」不成正比。**暂不采用。**
- **S2 token 预算**：按模型真实 token 数算预算最准。
  代价是要引入 tokenizer（`jtokkit` 等），违反项目「零新增依赖」约束；
  不同模型分词器还不一样，写死一种反而不准。**不采用。**
- **S3 字符预算 + 从旧到新淘汰**（采纳）：用字符数当 token 的保守上界，
  从最旧的消息开始丢，保留最近的若干轮，被省略时在 system 里加一句说明。
- **S4 前端限制轮数**：治标，且客户端可绕过（服务端无状态，谁都能直接打接口）。**不采用。**

## 决策

在 `AgentService.runAgent` 组消息之前插入 `trimHistory`：

1. **预算**：`MAX_HISTORY_CHARS = 60_000` 字符（不含 system 提示词）。
   按中文 1 字 ≈ 1～1.5 token 估，留足模型窗口与输出空间。
2. **淘汰顺序**：从**最新到最旧**反向累计，累计到超预算为止，留在预算内的连续后缀。
   即「保留最近 N 轮」，不挖空中间，避免历史出现逻辑断裂。
3. **最新一条无条件保留**：即使本轮提问本身就超预算也不丢。
   丢掉它等于这轮直接答非所问，比超窗口更糟。
4. **省略提示**：`dropped > 0` 时在 system 内容后追加
   `（说明：更早的 N 条对话因长度限制已省略；如需其中信息，请直接询问用户。）`
   ——明确告诉模型「你看不到更早的内容」，避免它假装记得并编造。
5. **两级截断仍保留**：先按总预算淘汰消息，再按 `MAX_CONTENT_LENGTH` 截断单条。
   前者管总量，后者管单条异常（比如用户粘贴一整篇文档）。

## 契约与可见性

- `trimHistory(List<AgentMessage>)` 与 `omittedNote(int)` 为**包级可见静态方法**，
  便于测试直接断言（与 `resolveCaller()`、`lastUserMessage()` 的既有做法一致）。
- 返回 `record TrimmedHistory(List<AgentMessage> kept, int dropped)`；
  构造器把 `kept` 归一化为非 null。
- **不改变对外接口**：`/ai/agent` 的请求/响应、SSE 帧类型（message / tool_call /
  tool_result / done / error）全部不变，前端零改动。

## 验证

- `mvn -o test`：**255 tests / 0 failures**（新增 8 例）。
- 新增用例覆盖：null / 空列表 / 短历史原样保留 / 超预算丢最旧（4×20000 → 留 3 条）/
  最新一条超预算仍保留 / 100×1000 → 恰好留 60 条且总长 = 60000 / content 为 null 按 0 计 /
  提示语含条数。
- **未执行**：`mvn -o verify`。原因是 `target/workbench-server-0.1.0.jar` 正被一个运行中的
  java 进程占用（Windows 文件锁），本改动不涉及 schema 与接口，`test` 已覆盖编译与单测。

## 回滚

删除 `trimHistory` / `omittedNote` / `TrimmedHistory` 与 `MAX_HISTORY_CHARS`，
把 `runAgent` 里 `trimmed.kept()` 还原为 `request.messages()`、`systemContent` 还原为
`systemPrompt` 即可，无数据结构变更、无迁移。

## 后续

- 若立项②「会话持久化」，历史会由服务端保存并变长，届时需重新评估预算（可能按会话滚动摘要），
  本 ADR 的参数要一并复核。
- 摘要压缩（S1）留作后续优化：只有在确认「长对话是主场景」且成本可接受时才值得做。
