<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { MagicStick, Setting, Link, ChatDotRound } from '@element-plus/icons-vue'
import { useSkillLibrary, type SkillItem } from '@/composables/useSkillLibrary'
import {
  fetchAiConfig,
  saveAiConfig,
  testAiConfig,
  sendAgentMessage,
  type AiConfigView,
  type AiConnectionCheck,
  type AgentToolCallEvent,
  type AgentToolResultEvent
} from '@/api/aiWriting'

const { skills, reset } = useSkillLibrary()

// 谁能改模型配置由后端判定：configView.editable 来自 view() 按当前用户角色计算，是唯一权威来源。
const configEditable = computed(() => configView.value?.editable ?? false)

/* ---------- 人设 / 系统提示词 ---------- */
// 原「Skill 库」降级为「预设人设」：点一下把它的提示词填进系统提示词。
const systemPrompt = ref('')
const activePersonaId = ref<string | null>(null)

function applyPersona(s: SkillItem) {
  systemPrompt.value = s.prompt
  activePersonaId.value = s.id
}
function clearPersona() {
  systemPrompt.value = ''
  activePersonaId.value = null
}

/* ---------- 对话 ---------- */
interface ChatToolCall {
  id: string
  name: string
  args: string
  result?: string
  open: boolean
}
interface ChatMessage {
  id: number
  role: 'user' | 'assistant'
  content: string
  toolCalls?: ChatToolCall[]
  streaming?: boolean
}

let msgSeq = 0
const messages = ref<ChatMessage[]>([])
const input = ref('')
const loading = ref(false)
const errorMsg = ref('')

const canSend = computed(() => input.value.trim().length > 0 && !loading.value)

function toolName(name: string): string {
  if (name === 'query_knowledge_base') return '检索企业知识库'
  return name
}
function prettyArgs(args: string): string {
  try {
    return JSON.stringify(JSON.parse(args), null, 2)
  } catch {
    return args || '（无）'
  }
}

async function send() {
  const text = input.value.trim()
  if (!text || loading.value) return
  // 推入 user 消息；再推入一个空的 assistant 占位，后面流式填充。
  const assistant: ChatMessage = {
    id: ++msgSeq,
    role: 'assistant',
    content: '',
    toolCalls: [],
    streaming: true
  }
  messages.value.push({ id: ++msgSeq, role: 'user', content: text })
  messages.value.push(assistant)
  input.value = ''
  errorMsg.value = ''
  loading.value = true

  // 历史 = 除当前空 assistant 外的所有消息（纯文本角色）。
  const history = messages.value
    .slice(0, -1)
    .map((m) => ({ role: m.role, content: m.content }))

  try {
     await sendAgentMessage(
       // tools: [] = 不启用任何工具（跳过企业知识库），智能体直连模型作答。
       { systemPrompt: systemPrompt.value.trim() || undefined, messages: history, tools: [] },
       {
        onMessage: (delta) => {
          assistant.content += delta
        },
        onToolCall: (e: AgentToolCallEvent) => {
          assistant.toolCalls!.push({
            id: e.id,
            name: e.name,
            args: e.args,
            open: false
          })
        },
        onToolResult: (e: AgentToolResultEvent) => {
          const tc = assistant.toolCalls!.find((t) => t.id === e.id)
          if (tc) tc.result = e.content
        },
        onError: (msg) => {
          errorMsg.value = msg
          if (!assistant.content) {
            assistant.content = '（出错了：' + msg + '）'
          }
          assistant.streaming = false
          loading.value = false
          ElMessage.error(msg)
        },
        onDone: () => {
          assistant.streaming = false
          loading.value = false
        }
      }
    )
  } catch (e: unknown) {
    errorMsg.value = e instanceof Error ? e.message : '发送失败'
    assistant.streaming = false
    loading.value = false
    ElMessage.error(errorMsg.value)
  }
}

function newChat() {
  messages.value = []
  errorMsg.value = ''
}

async function copyMessage(content: string) {
  if (!content) return
  try {
    await navigator.clipboard.writeText(content)
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('复制失败，请手动选择文本')
  }
}

function quoteMessage(content: string) {
  if (!content) return
  input.value = input.value ? input.value + '\n\n' + content : content
}

/* ---------- 模型设置（接口地址 / 模型 / API Key） ---------- */
const configOpen = ref(false)
const configLoading = ref(false)
const configSaving = ref(false)
const testing = ref(false)
const testCheck = ref<AiConnectionCheck | null>(null)
const configView = ref<AiConfigView | null>(null)
const cfg = ref({ baseUrl: '', model: '', apiKey: '' })

async function loadConfig(silent = false) {
  configLoading.value = true
  try {
    configView.value = await fetchAiConfig()
    cfg.value.baseUrl = configView.value.baseUrl || ''
    cfg.value.model = configView.value.model || ''
    cfg.value.apiKey = ''
  } catch (e: unknown) {
    configView.value = null
    if (!silent) {
      ElMessage.error(e instanceof Error ? e.message : '读取模型配置失败')
    }
  } finally {
    configLoading.value = false
  }
}

async function openConfig() {
  cfg.value = { baseUrl: '', model: '', apiKey: '' }
  testCheck.value = null
  configOpen.value = true
  await loadConfig()
}

async function testConnection() {
  testing.value = true
  testCheck.value = null
  try {
    const check = await testAiConfig({
      baseUrl: cfg.value.baseUrl.trim(),
      model: cfg.value.model.trim(),
      apiKey: cfg.value.apiKey.trim()
    })
    testCheck.value = check
    if (check.ok) ElMessage.success('连接成功，可以直接对话了')
    else ElMessage.error(check.message)
  } catch (e: unknown) {
    const reason = e instanceof Error ? e.message : '测试连接失败'
    testCheck.value = { ok: false, message: reason, latencyMs: 0 }
    ElMessage.error(reason)
  } finally {
    testing.value = false
  }
}

function latencyText(ms: number) {
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${ms}ms`
}

async function saveConfig() {
  if (!configEditable.value) {
    ElMessage.warning('仅系统管理员可修改模型配置')
    return
  }
  if (!cfg.value.baseUrl.trim() || !cfg.value.model.trim()) {
    ElMessage.warning('接口地址与模型名必填')
    return
  }
  configSaving.value = true
  try {
    configView.value = await saveAiConfig({
      baseUrl: cfg.value.baseUrl.trim(),
      model: cfg.value.model.trim(),
      apiKey: cfg.value.apiKey.trim()
    })
    cfg.value.apiKey = ''
    ElMessage.success('模型配置已保存，下次对话即生效')
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : '保存模型配置失败')
  } finally {
    configSaving.value = false
  }
}

onMounted(() => loadConfig(true))
</script>

<template>
  <div class="agent-studio">
    <el-card shadow="never" class="ws-card">
      <div class="ws-header">
        <div class="ws-title">
          <el-icon :size="18"><ChatDotRound /></el-icon>
          <span>AI 智能体</span>
          <el-tag v-if="configView?.model" size="small" effect="plain" class="ws-config-tag">
            {{ configView.model }}
          </el-tag>
        </div>
        <div class="ws-header-actions">
          <el-button size="small" text @click="newChat">新对话</el-button>
          <el-button type="primary" plain size="default" :loading="configLoading" @click="openConfig">
            <el-icon style="margin-right: 4px"><Setting /></el-icon>
            模型设置
          </el-button>
        </div>
      </div>

      <div class="ws-body">
        <!-- 左：人设 / 系统提示词 + 预设 -->
        <div class="ws-persona">
          <label class="ws-label">人设 / 系统提示词</label>
          <el-input
            v-model="systemPrompt"
            type="textarea"
            :rows="5"
            resize="none"
            placeholder="定义这个智能体的角色与风格，例如：你是一名资深机关文秘，擅长润色公文……（留空则为通用助手）"
          />
          <div class="ws-persona-presets">
            <span class="ws-presets-title">预设人设</span>
            <div class="ws-chips">
              <span
                v-for="s in skills"
                :key="s.id"
                class="ws-chip"
                :class="{ active: activePersonaId === s.id }"
                @click="applyPersona(s)"
              >
                <el-icon v-if="activePersonaId === s.id"><MagicStick /></el-icon>
                {{ s.name }}
              </span>
            </div>
            <el-button size="small" text @click="clearPersona">清空人设</el-button>
            <el-button size="small" text @click="reset">恢复默认</el-button>
          </div>
          <p class="ws-hint">
            密钥保存在服务端、浏览器不接触；兼容任意 OpenAI 兼容接口；当前来源：{{
              configView
                ? configView.source === 'db'
                  ? '页面配置'
                  : '环境变量'
                : '未配置'
            }}
          </p>
          <p class="ws-hint">智能体可调用「检索企业知识库」等工具获取事实依据（需知识库已启动）。</p>
        </div>

        <!-- 右：对话 -->
        <div class="ws-chat">
          <div class="ws-messages" ref="msgScroll">
            <div v-if="messages.length === 0" class="ws-empty">
              和智能体开始对话吧。可以问它写点什么、润色公文，或让它去知识库里查制度。
            </div>
            <div
              v-for="m in messages"
              :key="m.id"
              class="ws-msg"
              :class="m.role === 'user' ? 'is-user' : 'is-assistant'"
            >
              <div class="ws-msg-role">{{ m.role === 'user' ? '你' : '智能体' }}</div>
              <div class="ws-msg-bubble">
                <pre v-if="m.content" class="ws-msg-text">{{ m.content }}</pre>
                <div v-if="m.streaming && !m.content" class="ws-typing">思考中…</div>

                <div v-if="m.toolCalls && m.toolCalls.length" class="ws-tools">
                  <div v-for="tc in m.toolCalls" :key="tc.id" class="ws-tool">
                    <div class="ws-tool-head" @click="tc.open = !tc.open">
                      <el-icon><Link /></el-icon>
                      <span class="ws-tool-name">{{ toolName(tc.name) }}</span>
                      <span v-if="!tc.result" class="ws-tool-spin">调用中…</span>
                      <span v-else class="ws-tool-done">已返回</span>
                      <span class="ws-tool-toggle">{{ tc.open ? '收起' : '详情' }}</span>
                    </div>
                    <div v-if="tc.open" class="ws-tool-body">
                      <div class="ws-tool-sub">入参</div>
                      <pre class="ws-tool-pre">{{ prettyArgs(tc.args) }}</pre>
                      <div class="ws-tool-sub">返回</div>
                      <pre class="ws-tool-pre">{{ tc.result || '（无）' }}</pre>
                    </div>
                  </div>
                </div>

                <div v-if="m.role === 'assistant' && m.content" class="ws-msg-actions">
                  <el-button size="small" text @click="copyMessage(m.content)">复制</el-button>
                  <el-button size="small" text type="primary" @click="quoteMessage(m.content)">引用到输入</el-button>
                </div>
              </div>
            </div>
            <div v-if="errorMsg" class="ws-error-bar">{{ errorMsg }}</div>
          </div>

          <div class="ws-input-bar">
            <el-input
              v-model="input"
              type="textarea"
              :rows="2"
              resize="none"
              :disabled="loading"
              placeholder="输入消息，Enter 发送，Shift+Enter 换行"
              @keydown.enter.exact.prevent="send"
            />
            <el-button type="primary" :loading="loading" :disabled="!canSend" @click="send">
              {{ loading ? '生成中…' : '发送' }}
            </el-button>
          </div>
        </div>
      </div>
    </el-card>

    <!-- 模型设置：只填一次，密钥落服务端；非管理员打开即只读 -->
    <el-dialog v-model="configOpen" title="模型设置" width="540px" :close-on-click-modal="false">
      <div v-loading="configLoading" class="ws-cfg-body">
        <p class="ws-cfg-note">
          支持任意 OpenAI 兼容接口（DeepSeek / 通义 / OpenAI / 本地 Ollama 等）。
          密钥写入服务端 <code>ai_config</code> 单行表，全公司共用、重启不丢；浏览器只拿得到脱敏结果。
        </p>
        <div class="ws-field">
          <label class="ws-label">接口地址（Base URL）</label>
          <el-input
            v-model="cfg.baseUrl"
            placeholder="https://api.deepseek.com/v1"
            :disabled="!configEditable || configLoading"
          />
        </div>
        <div class="ws-field">
          <label class="ws-label">模型名</label>
          <el-input
            v-model="cfg.model"
            placeholder="deepseek-chat"
            :disabled="!configEditable || configLoading"
          />
        </div>
        <div class="ws-field">
          <label class="ws-label">API Key</label>
          <el-input
            v-model="cfg.apiKey"
            type="password"
            show-password
            placeholder="留空表示不修改，沿用现有密钥"
            :disabled="!configEditable || configLoading"
          />
          <p v-if="configView?.apiKeyMasked" class="ws-cfg-masked">
            当前已配置：{{ configView.apiKeyMasked }}
          </p>
          <p v-else class="ws-cfg-masked">当前未配置密钥（可能来自环境变量 WORKBENCH_AI_API_KEY）</p>
        </div>
        <p v-if="configView?.source === 'env'" class="ws-cfg-note">
          当前生效来源是环境变量；保存后会切换为页面配置（优先级更高）。
        </p>
        <div v-if="configEditable" class="ws-cfg-test">
          <el-button size="small" :loading="testing" @click="testConnection">测试连接</el-button>
          <span v-if="testCheck" class="ws-cfg-test-result" :class="testCheck.ok ? 'is-ok' : 'is-bad'">
            {{ testCheck.ok ? '连接成功' : '连接失败' }}
            <template v-if="testCheck.latencyMs > 0"> · {{ latencyText(testCheck.latencyMs) }}</template>
            <br />
            {{ testCheck.message }}
          </span>
        </div>
        <p v-if="!configEditable" class="ws-cfg-note">
          你没有修改模型配置的权限（仅系统管理员与 HR 可改），此处只能查看，不能修改。
        </p>
      </div>
      <template #footer>
        <el-button @click="configOpen = false">关闭</el-button>
        <el-button v-if="configEditable" type="primary" :loading="configSaving" @click="saveConfig">
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.agent-studio {
  height: calc(100vh - 180px);
}
.ws-card {
  display: flex;
  flex-direction: column;
  height: 100%;
  border-radius: var(--glass-radius, 16px);
  background: #fffdf7;
  border: 1px solid rgba(100, 80, 55, 0.18);
  box-shadow: 0 2px 14px rgba(60, 45, 22, 0.06);
}
.ws-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  border-bottom: 1px solid rgba(100, 80, 55, 0.16);
}
.ws-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 650;
  color: #2c2418;
}
.ws-title .el-icon {
  color: var(--sunrise-gold, #c9974a);
}
.ws-config-tag {
  margin-left: 8px;
  font-weight: 500;
}
.ws-header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.ws-body {
  flex: 1;
  display: flex;
  gap: 16px;
  padding: 16px 18px;
  min-height: 0;
}
/* 左边人设栏 */
.ws-persona {
  flex: 0 0 300px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}
.ws-persona-presets {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.ws-presets-title {
  font-size: 12.5px;
  font-weight: 600;
  color: #5a4a35;
}
.ws-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.ws-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 5px 10px;
  border-radius: 999px;
  font-size: 12.5px;
  background: #fff;
  border: 1px solid rgba(120, 100, 70, 0.28);
  color: #5a4a35;
  cursor: pointer;
  transition: all 0.15s ease;
}
.ws-chip:hover {
  border-color: var(--sunrise-gold, #c9974a);
  color: #2c2418;
}
.ws-chip.active {
  background: var(--sunrise-gold, #c9974a);
  border-color: var(--sunrise-gold, #c9974a);
  color: #fff;
}
.ws-hint {
  margin: 0;
  font-size: 11.5px;
  color: #9e8e7a;
  line-height: 1.7;
}
/* 右边对话栏 */
.ws-chat {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}
.ws-messages {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 4px;
  min-height: 0;
}
.ws-empty {
  margin: auto;
  max-width: 420px;
  text-align: center;
  color: #9e8e7a;
  font-size: 13px;
  line-height: 1.9;
}
.ws-msg {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-width: 86%;
}
.ws-msg.is-user {
  align-self: flex-end;
  align-items: flex-end;
}
.ws-msg.is-assistant {
  align-self: flex-start;
}
.ws-msg-role {
  font-size: 11px;
  color: #9e8e7a;
  padding: 0 4px;
}
.ws-msg-bubble {
  border-radius: 14px;
  padding: 10px 14px;
  background: #ffffff;
  border: 1px solid rgba(100, 80, 55, 0.18);
  box-shadow: 0 1px 6px rgba(60, 45, 22, 0.04);
}
.ws-msg.is-user .ws-msg-bubble {
  background: var(--sunrise-gold, #c9974a);
  border-color: var(--sunrise-gold, #c9974a);
  color: #fff;
}
.ws-msg-text {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  font-size: 13.5px;
  line-height: 1.85;
  color: #3d3226;
}
.ws-msg.is-user .ws-msg-text {
  color: #fff;
}
.ws-typing {
  color: #9e8e7a;
  font-size: 13px;
}
.ws-msg-actions {
  display: flex;
  gap: 6px;
  margin-top: 6px;
}
/* 工具调用卡片 */
.ws-tools {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 8px;
}
.ws-tool {
  border: 1px dashed rgba(100, 80, 55, 0.32);
  border-radius: 10px;
  background: rgba(100, 80, 55, 0.05);
  overflow: hidden;
}
.ws-tool-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  cursor: pointer;
  font-size: 12.5px;
  color: #5a4a35;
}
.ws-tool-head .el-icon {
  color: var(--sunrise-gold, #c9974a);
}
.ws-tool-name {
  font-weight: 600;
}
.ws-tool-spin {
  color: #b07d2a;
}
.ws-tool-done {
  color: #0f6e56;
}
.ws-tool-toggle {
  margin-left: auto;
  color: #9e8e7a;
}
.ws-tool-body {
  padding: 0 10px 8px;
  font-size: 12px;
  color: #5a4a35;
}
.ws-tool-sub {
  font-weight: 600;
  margin: 6px 0 2px;
  color: #7a6a55;
}
.ws-tool-pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11.5px;
  line-height: 1.6;
  background: rgba(255, 255, 255, 0.7);
  border-radius: 6px;
  padding: 6px 8px;
  max-height: 220px;
  overflow: auto;
}
.ws-error-bar {
  align-self: center;
  color: #a32d2d;
  font-size: 12.5px;
  background: rgba(163, 45, 45, 0.08);
  border-radius: 8px;
  padding: 6px 12px;
}
.ws-input-bar {
  display: flex;
  gap: 10px;
  align-items: flex-end;
}
.ws-input-bar .el-button {
  flex: 0 0 auto;
}
.ws-cfg-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.ws-cfg-note,
.ws-cfg-masked {
  margin: 0;
  font-size: 12px;
  line-height: 1.8;
  color: #9e8e7a;
}
.ws-cfg-note code {
  font-size: 11.5px;
  padding: 0 4px;
  border-radius: 4px;
  background: rgba(100, 80, 55, 0.1);
}
.ws-cfg-test {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}
.ws-cfg-test-result {
  padding-top: 3px;
  font-size: 12px;
  line-height: 1.7;
}
.ws-cfg-test-result.is-ok {
  color: #0f6e56;
}
.ws-cfg-test-result.is-bad {
  color: #a32d2d;
}
.ws-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.ws-label {
  font-size: 12.5px;
  font-weight: 600;
  color: #5a4a35;
}

/* Element Plus deep overrides: white input fields + dark text */
:deep(.el-input__wrapper),
:deep(.el-textarea__inner),
:deep(.el-select .el-input__wrapper) {
  background: #ffffff !important;
  box-shadow: 0 0 0 1px rgba(120, 100, 70, 0.28) inset !important;
}
:deep(.el-input__inner),
:deep(.el-textarea__inner),
:deep(.el-select .el-input__inner) {
  color: #2c2418 !important;
}
:deep(.el-input__inner::placeholder),
:deep(.el-textarea__inner::placeholder),
:deep(.el-select .el-input__inner::placeholder) {
  color: #b0a08a !important;
}
:deep(.el-textarea__inner) {
  padding: 10px 12px;
}

@media (max-width: 900px) {
  .ws-body {
    flex-direction: column;
    overflow-y: auto;
  }
  .ws-persona {
    flex: none;
  }
  .ws-messages {
    min-height: 320px;
  }
}
</style>
