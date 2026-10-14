import { ref, watch } from 'vue'

export interface SkillItem {
  id: string
  name: string
  prompt: string
}

const STORAGE_KEY = 'workbench-ai-skills'

const DEFAULT_SKILLS: SkillItem[] = [
  {
    id: 'official-polish',
    name: '公文润色',
    prompt:
      '你是一名资深机关文秘。请将用户提供的公文草稿润色得更规范、得体：修正语病与标点，统一公文体例（如"一是/二是"或分点），语气庄重、客观、简洁；不增减事实，不臆造数据、文号或政策。保留原文所有实质信息。'
  },
  {
    id: 'notice-rewrite',
    name: '通知/通报改写',
    prompt:
      '你是一名行政文书。请将用户提供的要点改写为一份正式的公司通知（或通报）：标题自拟、主送规范、正文条理清晰。语言正式、准确，避免口语；不要输出 [公司名称]、[部门名称]、[日期] 等占位符，如果用户没有提供落款单位或日期，直接省略；如信息不足，用一句话说明缺少什么，而不是插入占位符。'
  },
  {
    id: 'work-summary',
    name: '工作总结',
    prompt:
      '你是一名综合管理岗同事。请根据用户提供的素材/要点，撰写一段结构化的工作总结：先总述，再分"主要工作/成效/不足/下一步"四部分，用分点呈现，突出量化成果；语言精炼、务实，不夸大。'
  },
  {
    id: 'meeting-minutes',
    name: '会议纪要',
    prompt:
      '你是一名会议记录员。请将用户提供的会议记录/录音要点整理为标准会议纪要：含会议主题、时间地点（占位）、参会人（占位）、议程、议定事项、待办与责任人（占位）、下一步。用分点，结论先行，去掉闲聊。'
  },
  {
    id: 'resume-bullet',
    name: '简历亮点',
    prompt:
      '你是一名求职辅导。请将用户的经历/职责改写为突出成果的中英文简历 bullet：以强动词开头，尽量量化（数字、比例、金额），每条 1–2 行；删除无意义套话；保持真实，不虚构。'
  },
  {
    id: 'email-draft',
    name: '邮件拟稿',
    prompt:
      '你是一名职场助理。请根据用户的目的草拟一封得体的邮件：主题明确、称呼与落款规范、正文简洁有礼、行动项清晰；必要时提供"正式/温和"两种语气备选。不编造收件人信息。'
  }
]

function load(): SkillItem[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) {
      const arr = JSON.parse(raw)
      if (Array.isArray(arr)) {
        return arr
          .filter(
            (x: any) =>
              x && typeof x.id === 'string' && typeof x.name === 'string' && typeof x.prompt === 'string'
          )
          .map((x: any) => ({ id: x.id, name: x.name, prompt: x.prompt }))
      }
    }
  } catch {
    /* 忽略损坏数据，回落默认 */
  }
  return DEFAULT_SKILLS.map((s) => ({ ...s }))
}

// 模块级单例：跨组件/页面共享同一份收藏，持久化到当前浏览器 localStorage。
const skills = ref<SkillItem[]>(load())

watch(
  skills,
  (v) => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(v))
    } catch {
      /* 存储不可用时静默 */
    }
  },
  { deep: true }
)

function genId(): string {
  return 'skill-' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6)
}

export function useSkillLibrary() {
  function add(s: Omit<SkillItem, 'id'>) {
    skills.value.push({ id: genId(), name: s.name, prompt: s.prompt })
  }
  function update(id: string, patch: Partial<Omit<SkillItem, 'id'>>) {
    const i = skills.value.findIndex((x) => x.id === id)
    if (i >= 0) skills.value[i] = { ...skills.value[i], ...patch }
  }
  function remove(id: string) {
    skills.value = skills.value.filter((x) => x.id !== id)
  }
  function reset() {
    skills.value = DEFAULT_SKILLS.map((s) => ({ ...s }))
  }
  return { skills, add, update, remove, reset }
}
