# ADR-M015 知识库问答改用 SSE 推送（传输层流式，内容层分段）

- 日期：2026-11-06
- 状态：已实施
- 相关：`KbProxyController`、`web/src/api/kbAssistant.ts`、`web/src/views/kb/Assistant.vue`

## 背景

用户要求「回答改为流式输出」。取证结果决定了可选方案的范围：

1. 知识库侧（`.pi`，127.0.0.1:8765）`/openapi.json` 中**没有任何流式端点**；
2. `/api/chat` 的 `ChatRequest` 只有 `question`、`history`、`top_k`、`min_relevance`，**没有 `stream` 开关**；
3. 工作台侧 `AgentService` 已有可复用的 SSE 写法（`SseEmitter` + JSON 字符串帧，不传 mediaType）。

## 备选方案

| 方案 | 做法 | 代价 |
| --- | --- | --- |
| S1 真 token 流 | 改知识库加流式端点，工作台逐 token 透传 | 需改动另一个项目（.pi），跨仓发布 |
| **S2 工作台侧分段（采纳）** | 工作台拿整段答案后按句切片，逐帧推送 | 首字延迟不变，不冒称 token 流 |
| S3 前端假打字 | 后端不变，前端定时器吐字 | 首字延迟同样不变，且把延迟问题藏进前端 |

## 决策

采纳 **S2**：新增 `POST /api/kb/chat/stream`，`produces=text/event-stream`；服务端拿整段答案后按句切片推送。

理由：不跨仓改动、不引入新依赖、复用本仓已验证的 SSE 写法，且前端可以先把引用（meta）渲染出来再等正文。

## 接口契约

帧体为 JSON，事件名留空（与 `AgentService` 一致）：

| type | 载荷 | 说明 |
| --- | --- | --- |
| `meta` | `citations`、`retrieval_mode`、`mode`、`graph_evidence` | 正文前先到，先把引用渲染出来 |
| `delta` | `content` | 一段正文，前端顺序拼接 |
| `done` | 无 | 正常结束 |
| `error` | `message` | 中文可读原因；与 `done` 互斥 |

切片规则（`splitAnswer`，已由 `KbProxyControllerStreamTest` 盯住）：优先在 `。！？；\n` 后断开，最长 `MAX_PIECE_CHARS=24` 字；**只决定节奏，不增删任何字符**，拼接必须逐字等于原文。

运行时参数：帧间隔 `FRAME_INTERVAL_MS=25`、连接超时 `STREAM_TIMEOUT_MS=120000`、推送线程池 `STREAM_POOL_SIZE=4`（daemon，`@PreDestroy` 关闭）。客户端断开由 `AtomicBoolean cancelled` 停止推送，`onCompletion/onError/onTimeout` 都会置位。

## 诚实说明（写进代码注释，避免后续误用）

**这是传输层真 SSE，内容层不是 token 级流式。** 首字延迟仍等于知识库整段生成耗时（实测约 53s），只是拿到后逐段显现。要降低首字延迟只能走 S1。

## 验证

1. `KbProxyControllerStreamTest` 7 用例（空输入、拼接还原、句末切断、换行切断、无标点硬切、恰好 24 字、任意片段不超限）。
2. 真实端点：`POST /api/kb/chat/stream` → `HTTP 200`、`content-type: text/event-stream`、`meta → delta → done`。
3. 前端解析器用真实 SSE 字节流驱动（含「一帧被劈成两个 TCP 分片」的重组用例）：`meta` 先到、`delta` 数一致、首末帧间隔 123ms（证明逐帧而非一次性）、拼接逐字相同、`done` 只触发一次、401 触发重新登录。
4. 浏览器实测：发送后 53.4s 出现首段，此后每约 150ms 增长，最终 2313 字，引用 5 条正常渲染，`思考中` 消隐。

## 回滚

删除 `KbProxyController#chatStream`/`runChatStream`/`splitAnswer` 及辅助方法，前端把 `send()` 换回 `chatKb`。原 `/chat`、`/query` 未改动，回滚不影响既有链路。
