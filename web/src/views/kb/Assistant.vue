<script setup lang="ts">
import { ref, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import {
  chatKb,
  queryKb,
  type KbChatResult,
  type KbCitation,
  type KbHit
} from '@/api/kbAssistant'

interface Message {
  role: 'user' | 'assistant'
  content: string
  citations?: KbCitation[]
  retrievalMode?: string
  graphEvidence?: unknown[]
  mode?: string
  isError?: boolean
}

const messages = ref<Message[]>([])
const input = ref('')
const loading = ref(false)
const topK = ref(5)

const retrievalLabel: Record<string, string> = {
  vector: '向量检索',
  graph: '知识图谱',
  hybrid: '向量 + 图谱'
}

function modeLabel(mode?: string): string {
  if (!mode) return ''
  return retrievalLabel[mode] || mode
}

/**
 * 轻量富文本渲染：先整体 HTML 转义（防 XSS），再把知识库答案里的
 * **粗体** 与 "- " 列表转成真实标签，避免星号/横线裸露影响阅读。
 */
function renderRich(text: string): string {
  const escaped = text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
  const lines = escaped.split(/\r?\n/)
  const out: string[] = []
  let listOpen = false
  const closeList = () => {
    if (listOpen) {
      out.push('</ul>')
      listOpen = false
    }
  }
  for (const raw of lines) {
    const line = raw.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>').trim()
    if (/^[-•]\s+/.test(line)) {
      if (!listOpen) {
        out.push('<ul>')
        listOpen = true
      }
      out.push(`<li>${line.replace(/^[-•]\s+/, '')}</li>`)
    } else {
      closeList()
      if (line) out.push(`<p>${line}</p>`)
    }
  }
  closeList()
  return out.join('')
}

const chatScroll = ref<HTMLElement | null>(null)

function buildHistory() {
  return messages.value
    .filter((m) => m.role === 'user' || m.role === 'assistant')
    .filter((m) => !m.isError)
    .map((m) => ({ role: m.role, content: m.content }))
}

function scrollToBottom() {
  const el = chatScroll.value
  if (el) el.scrollTop = el.scrollHeight
}

async function send() {
  const question = input.value.trim()
  if (!question || loading.value) return
  messages.value.push({ role: 'user', content: question })
  input.value = ''
  loading.value = true
  const history = buildHistory().slice(0, -1)
  await nextTick()
  try {
    const res: KbChatResult = await chatKb({
      question,
      history,
      top_k: topK.value,
      min_relevance: 0.3
    })
    if (!res.ok) {
      messages.value.push({
        role: 'assistant',
        content: res.error || '知识库未返回结果',
        isError: true
      })
    } else {
      messages.value.push({
        role: 'assistant',
        content: res.answer || '',
        citations: res.citations || [],
        retrievalMode: res.retrieval_mode,
        graphEvidence: res.graph_evidence || [],
        mode: res.mode
      })
    }
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '调用知识库失败'
    messages.value.push({ role: 'assistant', content: msg, isError: true })
  } finally {
    loading.value = false
    await nextTick()
    scrollToBottom()
  }
}

const rawVisible = ref(false)
const rawHits = ref<KbHit[]>([])
const rawLoading = ref(false)
async function openRaw() {
  const lastUser = [...messages.value].reverse().find((m) => m.role === 'user')
  const question = (lastUser?.content || '').trim() || input.value.trim()
  if (!question) {
    ElMessage.warning('请先在下方输入一个问题')
    return
  }
  rawLoading.value = true
  try {
    const res = await queryKb({ question, top_k: topK.value })
    rawHits.value = res.hits || []
    rawVisible.value = true
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : '检索失败')
  } finally {
    rawLoading.value = false
  }
}
</script>

<template>
  <el-card shadow="never" class="kb-assistant">
    <div class="kb-header">
      <div class="kb-title">
        <el-icon :size="18"><Collection /></el-icon>
        <span>企业知识库 · 智能问答</span>
      </div>
      <div class="kb-actions">
        <el-select v-model="topK" size="small" style="width: 110px">
          <template #prefix>召回</template>
          <el-option :value="3" label="3 条" />
          <el-option :value="5" label="5 条" />
          <el-option :value="8" label="8 条" />
          <el-option :value="10" label="10 条" />
        </el-select>
        <el-button size="small" :loading="rawLoading" @click="openRaw">查看原始检索</el-button>
      </div>
    </div>

    <div ref="chatScroll" class="kb-chat">
      <div v-if="messages.length === 0" class="kb-empty">
        向企业知识库提问吧，例如：「差旅费报销标准是多少？」「工会慰问金申请流程？」
      </div>
      <div
        v-for="(m, i) in messages"
        :key="i"
        class="kb-msg"
        :class="m.role === 'user' ? 'is-user' : 'is-bot'"
      >
        <div class="kb-bubble" :class="{ 'is-error': m.isError }">
          <p class="kb-text" v-html="renderRich(m.content)"></p>
          <template v-if="m.role === 'assistant' && !m.isError && m.citations && m.citations.length">
            <div class="kb-mode" v-if="m.retrievalMode">
              <el-tag size="small" type="warning" effect="plain">
                {{ modeLabel(m.retrievalMode) }}
              </el-tag>
              <span class="kb-cite-count">引用 {{ m.citations.length }} 条</span>
            </div>
            <ul class="kb-cites">
              <li v-for="c in m.citations" :key="c.index">
                <details>
                  <summary>
                    <span class="kb-cite-idx">[{{ c.index }}]</span>
                    <span class="kb-cite-src">{{ c.source }}</span>
                    <span class="kb-cite-page">p.{{ c.page_range }}</span>
                  </summary>
                  <pre class="kb-cite-text">{{ c.text }}</pre>
                </details>
              </li>
            </ul>
          </template>
        </div>
      </div>
      <div v-if="loading" class="kb-msg is-bot">
        <div class="kb-bubble"><span class="kb-typing">知识库思考中…</span></div>
      </div>
    </div>

    <div class="kb-input">
      <el-input
        v-model="input"
        type="textarea"
        :rows="2"
        resize="none"
        placeholder="输入问题，Enter 发送（Shift+Enter 换行）"
        @keydown.enter.exact.prevent="send"
      />
      <el-button type="primary" :disabled="loading || !input.trim()" @click="send">
        发送
      </el-button>
    </div>

    <el-dialog v-model="rawVisible" title="原始检索结果" width="720px" top="6vh">
      <div v-loading="rawLoading">
        <el-empty v-if="!rawHits.length" description="没有命中的片段" />
        <div v-for="(h, i) in rawHits" :key="i" class="kb-raw-item">
          <div class="kb-raw-meta">
            <span class="kb-raw-src">{{ h.source }}</span>
            <span class="kb-raw-page">p.{{ h.page_range }}</span>
            <el-tag v-if="h.evidence_type" size="small" effect="plain">{{ h.evidence_type }}</el-tag>
            <span class="kb-raw-rel" v-if="h.relevance != null">相关度 {{ (h.relevance * 100).toFixed(0) }}%</span>
          </div>
          <pre class="kb-raw-text">{{ h.text }}</pre>
        </div>
      </div>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.kb-assistant {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 180px);
  border-radius: var(--glass-radius, 16px);
}
.kb-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 12px;
  border-bottom: 1px solid rgba(120, 100, 70, 0.14);
}
.kb-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 650;
  color: var(--text-primary);
}
.kb-title .el-icon {
  color: var(--sunrise-gold, #c9974a);
}
.kb-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.kb-context {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  margin-bottom: 4px;
  background: rgba(201, 151, 74, 0.08);
  border: 1px solid rgba(201, 151, 74, 0.25);
  border-radius: 10px;
  font-size: 12.5px;
  color: var(--text-secondary);
}
.kb-context-text {
  flex: 1;
}
.kb-chat {
  flex: 1;
  overflow-y: auto;
  padding: 16px 4px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 0;
}
.kb-empty {
  margin: auto;
  max-width: 420px;
  text-align: center;
  color: var(--text-tertiary);
  font-size: 13px;
  line-height: 1.9;
}
.kb-msg {
  display: flex;
}
.kb-msg.is-user {
  justify-content: flex-end;
}
.kb-msg.is-bot {
  justify-content: flex-start;
}
.kb-bubble {
  max-width: 78%;
  padding: 10px 14px;
  border-radius: 14px;
  /* 浅色近实底 + 深棕文字：glass 主题全局 --text-* 均为白色系，
     白字压半透明白底不可读，这里显式用深色对齐提问气泡的对比度 */
  background: rgba(255, 253, 248, 0.92);
  border: 1px solid rgba(120, 100, 70, 0.22);
  color: #3a2f21;
}
.kb-msg.is-user .kb-bubble {
  background: linear-gradient(135deg, var(--sunrise-gold, #e9be6e), var(--sunrise-amber, #c9974a));
  color: #fff;
  border-color: transparent;
}
.kb-bubble.is-error {
  background: rgba(229, 115, 115, 0.1);
  border-color: rgba(229, 115, 115, 0.4);
  color: #c0392b;
}
.kb-text {
  margin: 0;
  word-break: break-word;
  font-size: 13.5px;
  line-height: 1.8;
}
.kb-text p { margin: 0 0 6px; }
.kb-text p:last-child { margin-bottom: 0; }
.kb-text ul { margin: 2px 0 8px; padding-left: 18px; }
.kb-text li { margin: 2px 0; }
.kb-text strong { color: #2c2418; font-weight: 700; }
.kb-mode {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}
.kb-cite-count {
  font-size: 12px;
  color: #8a7a63;
}
.kb-cites {
  list-style: none;
  margin: 8px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.kb-cites details {
  background: rgba(255, 255, 255, 0.5);
  border: 1px solid rgba(120, 100, 70, 0.12);
  border-radius: 8px;
  padding: 4px 8px;
}
.kb-cites summary {
  cursor: pointer;
  font-size: 12px;
  display: flex;
  gap: 6px;
  align-items: baseline;
}
.kb-cite-idx {
  font-weight: 700;
  color: #a97b2f;
}
.kb-cite-src {
  color: #4a4030;
}
.kb-cite-page {
  color: #8a7a63;
}
.kb-cite-text {
  margin: 6px 0 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  line-height: 1.7;
  color: #4a4030;
  max-height: 180px;
  overflow-y: auto;
}
.kb-typing {
  color: #8a7a63;
  font-size: 13px;
}
.kb-input {
  display: flex;
  gap: 10px;
  align-items: flex-end;
  padding-top: 12px;
  border-top: 1px solid rgba(120, 100, 70, 0.14);
}
.kb-input .el-button {
  flex-shrink: 0;
}
.kb-raw-item {
  border-bottom: 1px dashed rgba(120, 100, 70, 0.16);
  padding: 10px 0;
}
.kb-raw-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: #4a4030;
}
.kb-raw-rel {
  margin-left: auto;
  color: #a97b2f;
  font-weight: 600;
}
.kb-raw-text {
  margin: 6px 0 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  line-height: 1.7;
  color: #4a4030;
  max-height: 200px;
  overflow-y: auto;
}
</style>
