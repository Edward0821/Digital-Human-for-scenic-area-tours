<script setup>
/**
 * 实时聊天页（第十一周任务 B1）——游客与数字人的 WS 实时问答。
 *
 * 契约（接口文档 v1.3，组员 A 后端已实现 feat/ws-backend）：
 * - WS 端点 /ws/qa?sessionId=<uuid>[&token=...]（握手即鉴权：匿名游客允许，带了无效 token 才拒绝）
 * - C→S chat {clientId, text}；S→C ack {clientId, seq}；S→C answer {seq, question, answer, latencyMs}
 * - 心跳：每 30s 发文本帧 "ping" → 服务端回 "pong"（PPT P07：太长被防火墙回收，太短被当攻击）
 * - seq 规则（PPT P11）：服务端 Redis INCR 发号、会话内严格递增。
 *   seq == lastSeq+1 直接插入；seq > lastSeq+1 先展示再补拉；seq <= lastSeq 丢弃。
 *   不用时间戳排序——两台手机时钟不同步必乱序（老师课上点名的追问）。
 * - HTTP 补拉兜底（PPT P12"推是加速，拉是兜底"）：连接建立后 + 收到空洞 seq 时调
 *   GET /api/v1/qa/sync?sessionId=&afterSeq= → {messages[], lastSeq}
 * - lastSeq 存 localStorage（key 按 sessionId 区分），刷新页面/重连后从断点继续
 * - 渲染前显式 HTML 转义（PPT P16 防 XSS——AI 生成代码最常见漏洞）
 */
import { nextTick, onUnmounted, ref } from 'vue'
import { http } from '../api/http'
import { getToken } from '../api/auth'

// ============ 会话标识 ============
// sessionId 持久化：刷新/重连后 lastSeq 游标才能对上同一条会话
const SESSION_KEY = 'chat:sessionId'
let sessionId = localStorage.getItem(SESSION_KEY)
if (!sessionId) {
  sessionId = (crypto.randomUUID && crypto.randomUUID()) || String(Date.now()) + Math.random().toString(16).slice(2)
  localStorage.setItem(SESSION_KEY, sessionId)
}
// clientId = sessionId:本地自增（PPT P10 去重锚点：由客户端生成，
// 重发同一条时 clientId 不变，服务端 SETNX/唯一键两层拦截）
const CLIENTNO_KEY = `chat:clientNo:${sessionId}`
let clientNo = Number(localStorage.getItem(CLIENTNO_KEY) || 0)

// ============ 消息状态 ============
// { role:'user'|'bot', text, status:'sending'|'sent'|'failed', clientId?, seq?, latencyMs? }
const messages = ref([])
const lastSeq = Number(localStorage.getItem(`chat:lastSeq:${sessionId}`) || 0)
const seenSeqs = new Set() // 已入列的 seq（去重：补拉与推送可能撞同一条）
let lastSeqCur = lastSeq

function saveLastSeq(v) {
  lastSeqCur = v
  localStorage.setItem(`chat:lastSeq:${sessionId}`, String(v))
}

// ============ WS 连接管理 ============
const wsState = ref('connecting') // connecting | open | closed
let ws = null
let heartbeatTimer = null
let retry = 0 // 指数退避计数
let reconnectTimer = null

function connect() {
  clearTimeout(reconnectTimer)
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  // token 可选参数：登录态带给服务端握手校验；匿名游客不带（PPT P16 握手即鉴权）
  const token = getToken()
  const url = `${proto}://${location.host}/ws/qa?sessionId=${sessionId}${token ? `&token=${encodeURIComponent(token)}` : ''}`
  wsState.value = 'connecting'
  ws = new WebSocket(url)

  // onopen（PPT 坑点第 1/2 条：心跳与退避必须成对出现）
  ws.onopen = () => {
    wsState.value = 'open'
    retry = 0 // ① 重置退避计数
    startHeartbeat() // ② 启动 30s 心跳
    syncPull('连接建立') // ③ 补拉兜底（推是加速，拉是兜底）
  }

  ws.onmessage = (ev) => {
    // 心跳响应兼容纯文本 "pong" 与 JSON {"type":"pong"}（与后端实现一致）
    if (ev.data === 'pong') return
    let frame
    try {
      frame = JSON.parse(ev.data)
    } catch {
      return // 非法帧忽略
    }
    if (frame.type === 'ack') onAck(frame)
    else if (frame.type === 'answer') onAnswer(frame)
  }

  ws.onclose = () => {
    wsState.value = 'closed'
    stopHeartbeat()
    // 指数退避重连：1s → 2s → 4s → … 上限 30s（Console 可见退避节奏）
    const delay = Math.min(1000 * 2 ** retry++, 30000)
    console.warn(`[chat] 连接断开，${delay}ms 后第 ${retry} 次重连`)
    reconnectTimer = setTimeout(connect, delay)
  }
  ws.onerror = () => ws && ws.close()
}

function startHeartbeat() {
  stopHeartbeat()
  heartbeatTimer = setInterval(() => {
    if (ws && ws.readyState === WebSocket.OPEN) ws.send('ping')
  }, 30000)
}
function stopHeartbeat() {
  clearInterval(heartbeatTimer)
  heartbeatTimer = null
}

// ============ HTTP 补拉（游标兜底） ============
let syncing = false
async function syncPull(reason) {
  if (syncing) return
  syncing = true
  try {
    const { data: res } = await http.get('/qa/sync', {
      params: { sessionId, afterSeq: lastSeqCur },
    })
    const list = res.data?.messages || []
    for (const m of list) applyAnswer(m, true)
    if (res.data?.lastSeq > lastSeqCur) saveLastSeq(res.data.lastSeq)
    if (list.length) scrollBottom()
    console.log(`[chat] 补拉（${reason}）：${list.length} 条，游标推进到 ${lastSeqCur}`)
  } catch (e) {
    console.warn(`[chat] 补拉失败（${reason}）：`, e.message)
  } finally {
    syncing = false
  }
}

// ============ 收帧处理 ============
function onAck(frame) {
  const msg = messages.value.find((m) => m.clientId === frame.clientId && m.role === 'user')
  if (msg) {
    msg.status = 'sent' // "发送中" → 对勾（PPT P10）
    msg.seq = frame.seq
  }
}

function onAnswer(frame) {
  if (frame.seq <= lastSeqCur) return // <=lastSeq 丢弃（重复推送）
  applyAnswer(frame, false)
  scrollBottom()
  if (frame.seq > lastSeqCur + 1) syncPull('空洞seq') // 有空洞：先展示再补拉
  saveLastSeq(Math.max(lastSeqCur, frame.seq))
}

/** 把一轮问答入列（question+answer 成对）。fromSync=true 时只补不重复 */
function applyAnswer(m) {
  if (seenSeqs.has(m.seq)) return
  seenSeqs.add(m.seq)
  // 已有对应用户气泡（ack 后等答案的）→ 挂到它下面；否则整对插入（补拉场景）
  const idx = messages.value.findIndex((u) => u.role === 'user' && u.seq === m.seq)
  const pair = [
    ...(idx === -1 ? [{ role: 'user', text: m.question, status: 'sent', seq: m.seq, clientId: null }] : []),
    { role: 'bot', text: m.answer, seq: m.seq, latencyMs: m.latencyMs },
  ]
  if (idx === -1) messages.value.push(...pair)
  else messages.value.splice(idx + 1, 0, pair[0])
}

async function scrollBottom() {
  await nextTick()
  const box = document.querySelector('.chat-box')
  if (box) box.scrollTop = box.scrollHeight
}

// ============ 发送 ============
const draft = ref('')
const sending = ref(false)

function send() {
  const text = draft.value.trim()
  if (!text || sending.value) return
  if (!ws || ws.readyState !== WebSocket.OPEN) {
    messages.value.push({ role: 'user', text, status: 'failed', clientId: null })
    scrollBottom()
    return
  }
  sending.value = true
  const clientId = `${sessionId}:${++clientNo}`
  localStorage.setItem(CLIENTNO_KEY, String(clientNo))
  messages.value.push({ role: 'user', text, status: 'sending', clientId })
  ws.send(JSON.stringify({ type: 'chat', clientId, text }))
  draft.value = ''
  scrollBottom()
  setTimeout(() => (sending.value = false), 300) // 简易节流防连点
}

// ============ HTML 转义（PPT P16 防 XSS：AI 生成代码最常见漏洞） ============
const ESC_MAP = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }
function esc(s) {
  return String(s).replace(/[&<>"']/g, (c) => ESC_MAP[c])
}

connect()

onUnmounted(() => {
  stopHeartbeat()
  clearTimeout(reconnectTimer)
  if (ws) ws.onclose = null // 手动离开页面不再触发重连
  ws && ws.close()
})
</script>

<template>
  <div class="chat-view">
    <h2>实时问答（WebSocket：一次握手双向通信）</h2>
    <p class="hint">
      会话 <code>{{ sessionId.slice(0, 8) }}…</code>
      <span :class="['dot', wsState]">{{ wsState === 'open' ? '已连接' : wsState === 'connecting' ? '连接中…' : '已断开（自动重连中）' }}</span>
    </p>

    <div class="chat-box">
      <div v-for="(m, i) in messages" :key="i" :class="['line', m.role]">
        <div :class="['bubble', m.role]">
          <!-- 渲染前显式 HTML 转义（PPT P16 防 XSS） -->
          <span v-html="esc(m.text)"></span>
          <em v-if="m.latencyMs != null" class="latency">{{ m.latencyMs }}ms</em>
        </div>
        <span v-if="m.role === 'user'" class="status">
          <template v-if="m.status === 'sending'">发送中…</template>
          <template v-else-if="m.status === 'sent'">✓</template>
          <template v-else>✗ 未送达</template>
        </span>
      </div>
      <div v-if="!messages.length" class="hint empty">问点什么吧，比如"门票多少钱""缆车几点停运"</div>
    </div>

    <div class="input-row">
      <input
        v-model="draft"
        maxlength="500"
        placeholder="输入问题（回车发送，≤500 字）"
        @keyup.enter="send"
      />
      <button :disabled="!draft.trim() || sending" @click="send">发送</button>
    </div>
  </div>
</template>

<style scoped>
.chat-view { max-width: 760px; margin: 0 auto; }
.hint { color: #8a94a6; font-size: 12px; }
.dot { margin-left: 8px; font-weight: 600; }
.dot.open { color: #1fa667; }
.dot.connecting { color: #f0a020; }
.dot.closed { color: #d93026; }
.chat-box {
  height: 420px; overflow-y: auto; background: #f6f8fb;
  border: 1px solid #e3e8f0; border-radius: 8px; padding: 14px; margin: 10px 0;
}
.line { display: flex; align-items: flex-end; gap: 6px; margin: 8px 0; }
.line.bot { justify-content: flex-start; }
.line.user { justify-content: flex-end; }
.bubble {
  max-width: 70%; padding: 8px 12px; border-radius: 10px; font-size: 14px; line-height: 1.6;
  word-break: break-word; white-space: pre-wrap;
}
.bubble.bot { background: #fff; border: 1px solid #e3e8f0; border-top-left-radius: 2px; }
.bubble.user { background: #2f6fed; color: #fff; border-top-right-radius: 2px; }
.latency { display: block; font-size: 11px; opacity: .75; margin-top: 2px; font-style: normal; }
.status { font-size: 12px; color: #8a94a6; }
.empty { text-align: center; margin-top: 180px; }
.input-row { display: flex; gap: 8px; }
.input-row input {
  flex: 1; padding: 10px 12px; border: 1px solid #dbe2ee; border-radius: 6px; font-size: 14px;
}
.input-row button {
  padding: 10px 22px; border: none; background: #2f6fed; color: #fff;
  border-radius: 6px; cursor: pointer; font-size: 14px;
}
.input-row button:disabled { opacity: .45; cursor: not-allowed; }
code { background: #f0f4fb; padding: 1px 5px; border-radius: 4px; }
</style>
