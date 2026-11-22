<script setup>
/**
 * 文件存储演示页 —— 对应录屏讲解的五个问题：
 * 1 大文件分片 / 后台重组 / 坏块通知重传 → 卡片一（模拟分片损坏开关）
 * 2 小文件批量上传                     → 卡片二（多选并发调用 /files/simple）
 * 3 上传断点续传（progress 接口）/ 下载断点续传（Range: bytes=X- → 206）→ 卡片一暂停继续 + 卡片三暂停继续
 * 4 上传进度条（已完成分片字节 + 当前分片 ofnUploadProgress 加权）/ 下载进度条（reader 累计 / Content-Length）→ 两条进度条
 * 5 高并发下载 → 讲解口径：接口无锁无状态 + Range 分段多线程拉取 + 生产 Nginx sendfile
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import axios from 'axios'
import SparkMD5 from 'spark-md5'
import { http } from '../api/http' // 全局实例：自动带登录态 + 业务码拦截（FILE 接口现在需要 ADMIN 令牌）
import { getToken } from '../api/auth'

const BASE = 'files' // 相对全局 baseURL（/api/v1）

// ================= 公共：日志 + 已上传文件列表 =================
const logs = ref([])
let logSeq = 0
function log(tag, msg) {
  const t = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  logs.value.unshift(`[${t}] [${tag}] ${msg}`)
  if (logs.value.length > 200) logs.value.pop()
}

const fileList = ref([]) // 本会话上传成功的文件 {id, name, size}
function addToList(id, name, size) {
  if (!fileList.value.some((f) => f.id === id)) fileList.value.push({ id, name, size })
}
function idFromUrl(url) {
  const m = String(url).match(/files\/(\d+)\/download/)
  return m ? Number(m[1]) : null
}
function fmtSize(n) {
  if (n >= 1024 * 1024) return (n / 1024 / 1024).toFixed(2) + ' MB'
  if (n >= 1024) return (n / 1024).toFixed(1) + ' KB'
  return n + ' B'
}

/** 分块增量计算 md5（大文件不能一次性读进内存） */
async function calcMd5(file, onProgress) {
  const spark = new SparkMD5.ArrayBuffer()
  const step = 2 * 1024 * 1024
  for (let off = 0; off < file.size; off += step) {
    spark.append(await file.slice(off, off + step).arrayBuffer())
    onProgress?.(Math.min(100, Math.round(((off + step) / file.size) * 100)))
  }
  return spark.end()
}

// ================= 卡片一：大文件分片上传 =================
const bigFile = ref(null)
const chunkSizeMB = ref(1)
const corruptEnabled = ref(false)
const corruptAt = ref(2)
const md5Progress = ref(0)
const uploadProgress = ref(0)
const phase = ref('idle') // idle | hashing | uploading | done
const paused = ref(false)
const chunkSummary = reactive({ total: 0, done: 0 })

// 上传上下文：所有 worker 共享，JS 单线程所以 next++ 无竞态
let ctx = null

async function pickBigFile(e) {
  bigFile.value = e.target.files[0] || null
  phase.value = 'idle'
  uploadProgress.value = 0
  md5Progress.value = 0
  if (bigFile.value) log('大文件', `已选择：${bigFile.value.name}（${fmtSize(bigFile.value.size)}）`)
}

/** 生成本地演示大文件（录屏时不用临时找大文件）。getRandomValues 单次上限 65536 字节，按 64KB 循环填充 */
function makeDemoFile() {
  const mb = 20
  const buf = new Uint8Array(mb * 1024 * 1024)
  for (let i = 0; i < buf.length; i += 65536) {
    crypto.getRandomValues(buf.subarray(i, Math.min(i + 65536, buf.length)))
  }
  const f = new File([buf], `演示大文件-${mb}mb.pdf`, { type: 'application/pdf' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(f)
  a.download = f.name
  a.click()
  URL.revokeObjectURL(a.href)
  log('大文件', `已生成 ${mb}MB 演示文件并下载到本地，请用它选择上传`)
}

async function startUpload() {
  if (!bigFile.value || phase.value === 'uploading' || phase.value === 'hashing') return
  paused.value = false
  phase.value = 'hashing'
  const file = bigFile.value
  const md5 = await calcMd5(file, (p) => (md5Progress.value = p))
  log('大文件', `md5 计算完成：${md5}`)

  // ① init：后端依次判断 秒传 / 断点续传接续 / 新建任务
  const chunkSize = Math.round(chunkSizeMB.value * 1024 * 1024)
  const { data: initRes } = await http.post(`${BASE}/init`, {
    fileName: file.name, fileSize: file.size, fileType: 'pdf', md5, chunkSize,
  })
  const init = initRes.data
  ctx = {
    file, fileId: init.fileId, chunkSize,
    chunkTotal: init.chunkTotal,
    next: 1,
    done: init.uploadedChunks?.length || 0,
    doneBytes: bytesOf(init.uploadedChunks || []),
    active: new Map(), // chunkNo -> loaded 字节
    uploaded: new Set(init.uploadedChunks || []),
    corruptDone: false,
    controller: new AbortController(),
    stopped: false,
  }
  chunkSummary.total = init.chunkTotal
  chunkSummary.done = ctx.done

  if (init.instant) {
    log('大文件', `⚡ 秒传命中：服务器已存在相同 md5 文件（id=${init.fileId}），0 字节传输`)
    phase.value = 'done'
    uploadProgress.value = 100
    addToList(init.fileId, init.fileName, file.size)
    return
  }
  if (ctx.done > 0) {
    log('大文件', `断点续传：复用任务 id=${init.fileId}，已完成 ${ctx.done}/${init.chunkTotal} 片，只补缺失分片`)
  } else {
    log('大文件', `新建上传任务 id=${init.fileId}：${init.chunkTotal} 片 × ${fmtSize(chunkSize)}/片，3 并发上传`)
  }

  // ② 3 并发传片
  phase.value = 'uploading'
  try {
    await Promise.all([workerLoop(), workerLoop(), workerLoop()])
  } catch (err) {
    log('大文件', `✗ 上传异常中止：${err.response?.data?.message || err.message}`)
    return
  }
  if (ctx.stopped) return // 暂停退出
  // ③ complete：缺片核对 → 后台重组 → 整体 md5 终校
  log('大文件', `全部分片就绪，调用 complete 请求后台重组…`)
  try {
    const { data: cRes } = await http.post(`${BASE}/${ctx.fileId}/complete`)
    log('大文件', `✓ 重组完成，整体 md5 终校通过 → ${cRes.data}`)
  } catch (err) {
    const resp = err.response?.data
    if (resp?.code === 10003 && Array.isArray(resp.data)) {
      log('大文件', `✗ 重组核对失败：缺失分片 [${resp.data.join(',')}] → 点"继续（断点续传）"补传后重试`)
    } else {
      log('大文件', `✗ complete 失败：${resp?.message || err.message}`)
    }
    return
  }
  phase.value = 'done'
  uploadProgress.value = 100
  addToList(ctx.fileId, file.name, file.size)
}

function bytesOf(nos) {
  if (!ctx) return 0
  return nos.reduce((s, no) => s + Math.min(ctx.chunkSize, ctx.file.size - (no - 1) * ctx.chunkSize), 0)
}

async function workerLoop() {
  while (true) {
    if (ctx.stopped) return
    const no = ctx.next++
    if (no > ctx.chunkTotal) return
    if (ctx.uploaded.has(no)) continue
    try {
      await sendChunk(no)
    } catch (err) {
      // 暂停导致的 abort：静默退出；真正的错误上抛给 Promise.all 的兜底
      if (ctx.stopped || axios.isCancel?.(err) || err.code === 'ERR_CANCELED') return
      throw err
    }
    if (ctx.stopped) return
  }
}

async function sendChunk(no) {
  const start = (no - 1) * ctx.chunkSize
  const raw = ctx.file.slice(start, Math.min(start + ctx.chunkSize, ctx.file.size))
  // X-Chunk-Md5 声称的是"原片"的 md5；若模拟损坏，篡改 body 后后端重算不一致 → 通知重传
  const claimedMd5 = await calcMd5(raw)
  let body = raw
  if (corruptEnabled.value && Number(corruptAt.value) === no && !ctx.corruptDone) {
    const buf = new Uint8Array(await raw.arrayBuffer())
    buf[0] ^= 0xff // 只改 1 个字节，模拟网络传输损坏
    body = new Blob([buf])
    ctx.corruptDone = true
    log('大文件', `⚠ 已人为篡改第 ${no} 片 1 个字节（模拟传输损坏）`)
  }
  try {
    await http.put(`${BASE}/${ctx.fileId}/chunks/${no}`, body, {
      headers: { 'X-Chunk-Md5': claimedMd5, 'Content-Type': 'application/octet-stream' },
      signal: ctx.controller.signal,
      onUploadProgress: (e) => {
        ctx.active.set(no, e.loaded || 0)
        refreshProgress()
      },
    })
  } catch (err) {
    if (axios.isCancel?.(err) || err.code === 'ERR_CANCELED') throw err
    const resp = err.response?.data
    if (resp?.code === 10001 && resp.data === no) {
      // 后端单片 md5 校验失败 → 明确告知分片号 → 自动重传原片（问题 1 的坏块通知）
      log('大文件', `✗ 后端校验：第 ${no} 片 md5 不一致（${resp.message}）→ 自动重传原片`)
      ctx.corruptDone = true // 防止再次篡改
      await sendChunk(no)
      return
    }
    throw err
  }
  ctx.active.delete(no)
  ctx.uploaded.add(no)
  ctx.done += 1
  ctx.doneBytes += Math.min(ctx.chunkSize, ctx.file.size - start)
  chunkSummary.done = ctx.done
  refreshProgress()
  log('大文件', `第 ${no}/${ctx.chunkTotal} 片上传成功（${fmtSize(Math.min(ctx.chunkSize, ctx.file.size - start))}）`)
}

function refreshProgress() {
  let activeBytes = 0
  ctx.active.forEach((v) => (activeBytes += v))
  uploadProgress.value = Math.min(99, Math.round(((ctx.doneBytes + activeBytes) / ctx.file.size) * 100))
}

/** 暂停：abort 所有在途分片请求 */
function pauseUpload() {
  if (!ctx || phase.value !== 'uploading') return
  paused.value = true
  ctx.stopped = true
  ctx.controller.abort()
  ctx.active.clear()
  log('大文件', `⏸ 已暂停（模拟浏览器关闭/断网），已完成 ${ctx.done}/${ctx.chunkTotal} 片`)
}

/** 继续：调 progress 接口拿服务器已收分片清单，只补缺失片（问题 3 上传侧） */
async function resumeUpload() {
  if (!ctx || !paused.value) return
  paused.value = false
  const { data: res } = await http.get(`${BASE}/${ctx.fileId}/progress`)
  const p = res.data
  ctx.uploaded = new Set(p.uploadedChunks || [])
  ctx.done = ctx.uploaded.size
  ctx.doneBytes = bytesOf([...ctx.uploaded])
  ctx.next = 1
  ctx.stopped = false
  ctx.controller = new AbortController()
  chunkSummary.done = ctx.done
  log('大文件', `▶ progress 接口返回已收分片 [${(p.uploadedChunks || []).join(',')}]，继续只补缺失的 ${ctx.chunkTotal - ctx.done} 片`)
  phase.value = 'uploading'
  try {
    await Promise.all([workerLoop(), workerLoop(), workerLoop()])
  } catch (err) {
    log('大文件', `✗ 上传异常中止：${err.response?.data?.message || err.message}`)
    return
  }
  if (ctx.stopped) return
  log('大文件', `全部分片就绪，调用 complete 请求后台重组…`)
  try {
    const { data: cRes } = await http.post(`${BASE}/${ctx.fileId}/complete`)
    log('大文件', `✓ 重组完成，整体 md5 终校通过 → ${cRes.data}`)
  } catch (err) {
    const resp = err.response?.data
    if (resp?.code === 10003 && Array.isArray(resp.data)) {
      log('大文件', `✗ 重组核对失败：缺失分片 [${resp.data.join(',')}] → 点"继续（断点续传）"补传后重试`)
    } else {
      log('大文件', `✗ complete 失败：${resp?.message || err.message}`)
    }
    return
  }
  phase.value = 'done'
  uploadProgress.value = 100
  addToList(ctx.fileId, ctx.file.name, ctx.file.size)
}

// ================= 卡片二：小文件批量上传 =================
const smallFiles = ref([])
const smallUploading = ref(false)

function pickSmallFiles(e) {
  smallFiles.value = [...e.target.files].map((f) => ({ file: f, state: '待上传' }))
  log('小文件', `已选择 ${smallFiles.value.length} 个文件，将并发调用 /files/simple`)
}

// ================= 卡片二·图片压缩（第十一周提速·③：canvas 压缩后再传） =================
/** 超过 1MB 的图片先压缩再传（quality 0.7 + 长边压到 1600px——按展示分辨率压，原图保留） */
const COMPRESS_OVER_BYTES = 1024 * 1024
const COMPRESS_QUALITY = 0.7

function isImage(f) {
  return f && f.type && f.type.startsWith('image/')
}

async function compressImage(file) {
  const bitmap = await createImageBitmap(file)
  const maxSide = 1600
  const scale = Math.min(1, maxSide / Math.max(bitmap.width, bitmap.height))
  const canvas = document.createElement('canvas')
  canvas.width = Math.round(bitmap.width * scale)
  canvas.height = Math.round(bitmap.height * scale)
  canvas.getContext('2d').drawImage(bitmap, 0, 0, canvas.width, canvas.height)
  bitmap.close?.()
  return new Promise((resolve, reject) => canvas.toBlob(
    (b) => (b ? resolve(b) : reject(new Error('canvas 压缩失败'))), 'image/jpeg', COMPRESS_QUALITY))
}

/** 批量 = 前端多选后并发循环调用单个接口（问题 2） */
async function uploadSmall() {
  if (!smallFiles.value.length || smallUploading.value) return
  smallUploading.value = true
  await Promise.all(smallFiles.value.map(async (item) => {
    try {
      item.state = '上传中…'
      let payload = item.file
      let finalName = item.file.name
      // 提速·③：超 1MB 的图片先 canvas 压缩（quality 0.7）再传，输出统一转 jpg
      if (isImage(payload) && payload.size > COMPRESS_OVER_BYTES) {
        const before = payload.size
        payload = await compressImage(payload)
        finalName = item.file.name.replace(/\.\w+$/, '') + '.jpg'
        const saved = Math.max(0, Math.round((1 - payload.size / before) * 100))
        item.compressed = `压缩：${fmtSize(before)} → ${fmtSize(payload.size)}（省 ${saved}%，quality ${COMPRESS_QUALITY}）`
        log('图片压缩', `✓ ${item.file.name} ${item.compressed}`)
      }
      const fd = new FormData()
      fd.append('file', payload, finalName)
      const { data: res } = await http.post(`${BASE}/simple`, fd)
      const id = idFromUrl(res.data)
      item.state = `✓ 成功 id=${id}`
      addToList(id, finalName, payload.size)
      log('小文件', `✓ ${finalName}（${fmtSize(payload.size)}）直传成功，id=${id}`)
    } catch (err) {
      item.state = `✗ 失败：${err.response?.data?.message || err.message}`
      log('小文件', `✗ ${item.file.name} 失败：${err.response?.data?.message || err.message}`)
    }
  }))
  smallUploading.value = false
}

// ================= 卡片三：下载（进度条 + Range 断点续传） =================
const dlState = reactive({ running: false, paused: false, progress: 0, note: '' })
let dlTask = null // {chunks, received, total, target}

async function startDownload(f) {
  if (dlState.running) return
  dlTask = { chunks: [], received: 0, total: f.size, target: f }
  dlState.running = true
  dlState.paused = false
  dlState.progress = 0
  await doDownload()
}

async function doDownload() {
  const t = dlTask
  const headers = t.received > 0 ? { Range: `bytes=${t.received}-` } : {}
  if (t.received > 0) log('下载', `▶ 携带 Range: bytes=${t.received}- 继续请求 id=${t.target.id}`)
  // fetch 不经 axios 实例：显式写 /api/v1 前缀 + 手动携带登录态（Vite 代理 /api → 8080）
  const resp = await fetch(`/api/v1/files/${t.target.id}/download`, {
    headers: { ...headers, Authorization: `Bearer ${getToken()}` },
  })
  if (t.received > 0) {
    log('下载', `✓ 响应 ${resp.status}（206 Partial Content），从断点 ${t.received} 字节处继续`)
  }
  const segLen = Number(resp.headers.get('Content-Length') || 0)
  t.total = t.received + segLen
  const name = fileNameFrom(resp.headers.get('Content-Disposition')) || t.target.name
  const reader = resp.body.getReader()
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    t.chunks.push(value)
    t.received += value.length
    dlState.progress = Math.round((t.received / t.total) * 100)
    if (dlState.paused) {
      await reader.cancel()
      log('下载', `⏸ 已暂停，已收 ${t.received}/${t.total} 字节（${dlState.progress}%），下次从该偏移续传`)
      dlState.running = false
      return
    }
  }
  // 合并保存
  const blob = new Blob(t.chunks)
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = name
  a.click()
  URL.revokeObjectURL(a.href)
  log('下载', `✓ 下载完成：${name}（${fmtSize(blob.size)}）已保存`)
  dlState.running = false
  dlState.progress = 100
}

function pauseDownload() {
  if (dlState.running) dlState.paused = true
}

async function resumeDownload() {
  if (dlTask && !dlState.running && dlState.progress < 100) {
    dlState.running = true
    dlState.paused = false
    await doDownload()
  }
}

function fileNameFrom(cd) {
  if (!cd) return null
  const m = cd.match(/filename\*=UTF-8''([^;]+)/)
  return m ? decodeURIComponent(m[1]) : null
}

const manualId = ref('')

// ================= 卡片四：图片懒加载墙（第十一周提速·②） =================
// 双保险：原生 loading="lazy"（浏览器可视区判断）+ IntersectionObserver（JS 按需赋 src）。
// 首屏只加载可视区内的图片，滚动到哪加载到哪——F12 Network 面板可数请求条数验证。
const wallImages = ref(
  Array.from({ length: 16 }, (_, i) => {
    const no = String(i + 1).padStart(2, '0')
    return { src: `/scenic/scenic-${no}.svg`, name: `景区示例图 ${no}`, loaded: false }
  }),
)
const wallEl = ref(null)
const loadedCount = computed(() => wallImages.value.filter((m) => m.loaded).length)
let wallObserver = null

function onImgLoad(e) {
  const src = e.target.getAttribute('data-src')
  const item = wallImages.value.find((m) => m.src === src)
  if (item) item.loaded = true
}

// ================= 计算属性 =================
const uploadPct = computed(() => uploadProgress.value)
const canResume = computed(() => paused.value && ctx && phase.value === 'uploading')

onMounted(() => {
  // IntersectionObserver：缩略图进入可视区（提前 100px 预载）才真正发起图片请求
  wallObserver = new IntersectionObserver(
    (entries) => {
      for (const en of entries) {
        if (en.isIntersecting) {
          const img = en.target
          img.src = img.getAttribute('data-src')
          wallObserver.unobserve(img) // 加载一次就不再观察
        }
      }
    },
    { rootMargin: '100px' },
  )
  wallEl.value?.querySelectorAll('img[data-src]').forEach((img) => wallObserver.observe(img))
})

onBeforeUnmount(() => wallObserver?.disconnect())
</script>

<template>
  <div class="file-view">
    <h2>文件存储演示（大文件分片 / 小文件批量 / 断点续传 / 进度条）</h2>

    <div class="grid">
      <!-- 卡片一：大文件分片上传 -->
      <section class="card">
        <h3>① 大文件分片上传（问题 1/3/4）</h3>
        <p class="hint">流程：spark-md5 分块计算 → init（秒传/续传判定）→ 3 并发 PUT 分片（单片 md5 校验）→ complete（缺片核对+重组+终校）</p>
        <div class="row">
          <input type="file" accept=".pdf,.txt,.md,.docx" @change="pickBigFile" />
          <button class="ghost" @click="makeDemoFile">生成 20MB 演示文件</button>
        </div>
        <div class="row">
          <label>分片大小：</label>
          <select v-model.number="chunkSizeMB">
            <option :value="5">5 MB（生产默认）</option>
            <option :value="1">1 MB</option>
            <option :value="0.25">256 KB（演示用小分片）</option>
          </select>
          <label class="ml">
            <input type="checkbox" v-model="corruptEnabled" />
            模拟分片损坏（篡改第
            <input type="number" v-model.number="corruptAt" min="1" style="width:52px" />
            片 1 个字节）
          </label>
        </div>
        <div class="row">
          <button :disabled="!bigFile || phase === 'hashing' || phase === 'uploading'" @click="startUpload">
            {{ phase === 'hashing' ? 'md5 计算中…' : phase === 'uploading' ? '上传中…' : '开始上传' }}
          </button>
          <button :disabled="phase !== 'uploading'" @click="pauseUpload">暂停</button>
          <button :disabled="!canResume" @click="resumeUpload">继续（断点续传）</button>
        </div>
        <div v-if="phase === 'hashing'" class="bar-wrap">
          <div class="bar-label">md5 计算 {{ md5Progress }}%</div>
          <div class="bar"><div class="fill hashing" :style="{ width: md5Progress + '%' }"></div></div>
        </div>
        <div v-if="phase === 'uploading' || phase === 'done'" class="bar-wrap">
          <div class="bar-label">
            上传 {{ uploadPct }}%（分片 {{ chunkSummary.done }}/{{ chunkSummary.total }}，3 并发 + 当前分片 onUploadProgress 加权）
          </div>
          <div class="bar"><div class="fill" :style="{ width: uploadPct + '%' }"></div></div>
        </div>
      </section>

      <!-- 卡片二：小文件批量上传 -->
      <section class="card">
        <h3>② 小文件批量上传（问题 2）</h3>
        <p class="hint">≤5MB 直传 /files/simple；批量 = 多选后前端并发循环调用（Promise.all）</p>
        <div class="row">
          <input type="file" multiple accept=".pdf,.txt,.md,.docx,.jpg,.jpeg,.png,.webp" @change="pickSmallFiles" />
          <button :disabled="!smallFiles.length || smallUploading" @click="uploadSmall">
            {{ smallUploading ? '并发上传中…' : '批量上传' }}
          </button>
        </div>
        <p class="hint">提速·③：选超 1MB 的图片会先走 canvas 压缩（quality 0.7、长边 1600px）再上传，日志可见压缩前后体积对比</p>
        <ul class="small-list">
          <li v-for="(item, i) in smallFiles" :key="i">
            <span>{{ item.file.name }}（{{ fmtSize(item.file.size) }}）{{ item.compressed ? '—— ' + item.compressed : '' }}</span>
            <span>{{ item.state }}</span>
          </li>
        </ul>
      </section>

      <!-- 卡片三：下载 -->
      <section class="card">
        <h3>③ 文件下载（问题 3/4/5）</h3>
        <p class="hint">fetch 流式读取：进度 = 已收字节 / Content-Length；暂停后携带 Range: bytes=X- 续传（206）；接口无锁 + Range 分段支持高并发</p>
        <div class="row">
          <input v-model="manualId" placeholder="或输入文件 id" style="width:140px" />
          <button
            :disabled="!manualId"
            @click="startDownload({ id: Number(manualId), name: `文件-${manualId}`, size: 0 })"
          >下载该 id</button>
        </div>
        <ul class="small-list">
          <li v-for="f in fileList" :key="f.id">
            <span>id={{ f.id }} {{ f.name }}（{{ fmtSize(f.size) }}）</span>
            <button :disabled="dlState.running" @click="startDownload(f)">下载</button>
          </li>
          <li v-if="!fileList.length" class="hint">（暂无文件，上传成功后自动出现在这里）</li>
        </ul>
        <div v-if="dlState.running || dlState.progress > 0" class="bar-wrap">
          <div class="bar-label">
            下载 {{ dlState.progress }}% {{ dlState.paused ? '（已暂停，可点继续走 Range 续传）' : '' }}
          </div>
          <div class="bar"><div class="fill dl" :style="{ width: dlState.progress + '%' }"></div></div>
          <button :disabled="!dlState.running" @click="pauseDownload">暂停</button>
          <button :disabled="dlState.running || dlState.progress >= 100 || dlState.progress === 0" @click="resumeDownload">继续（Range 续传）</button>
        </div>
      </section>
    </div>

    <!-- 卡片四：图片懒加载墙（第十一周提速·②） -->
    <section ref="wallEl" class="card">
      <h3>④ 图片懒加载墙（提速·②：loading="lazy" + IntersectionObserver 按需加载）</h3>
      <p class="hint">F12 Network 面板验证：首屏只请求可视区内的图片，往下滚动才加载其余——"已加载 x/16" 实时计数。权限表格分页见"权限管理"页。</p>
      <div class="wall">
        <figure v-for="(img, i) in wallImages" :key="i">
          <img :data-src="img.src" loading="lazy" alt="景区示例图" @load="onImgLoad" />
          <figcaption>{{ img.name }}<span v-if="img.loaded"> · 已加载</span></figcaption>
        </figure>
      </div>
      <p class="hint">已加载 {{ loadedCount }}/{{ wallImages.length }} 张</p>
    </section>

    <!-- 操作日志：录屏讲解的关键输出 -->
    <section class="card">
      <h3>操作日志（按时间倒序）</h3>
      <div class="log-box">
        <div v-for="(l, i) in logs" :key="i" class="log-line">{{ l }}</div>
        <div v-if="!logs.length" class="hint">（暂无日志）</div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.file-view { max-width: 1080px; margin: 0 auto; }
.grid { display: grid; grid-template-columns: 1fr; gap: 16px; }
.card {
  background: #fff; border: 1px solid #e3e8f0; border-radius: 8px;
  padding: 16px 20px; margin-bottom: 16px;
}
.card h3 { margin: 0 0 8px; font-size: 16px; }
.hint { color: #8a94a6; font-size: 12px; margin: 4px 0 10px; }
.row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin: 8px 0; }
.row .ml { margin-left: 8px; }
.small-list { list-style: none; margin: 8px 0 0; padding: 0; font-size: 13px; }
.small-list li { display: flex; justify-content: space-between; align-items: center; padding: 4px 0; border-bottom: 1px dashed #f0f2f5; }
.bar-wrap { margin-top: 10px; }
.bar-label { font-size: 12px; color: #556; margin-bottom: 4px; }
.bar { height: 14px; background: #eef1f6; border-radius: 7px; overflow: hidden; }
.fill { height: 100%; background: #2f6fed; transition: width .2s; }
.fill.hashing { background: #f0a020; }
.fill.dl { background: #1fa667; }
.log-box {
  background: #0f172a; color: #c9f7d9; font-family: Consolas, monospace;
  font-size: 12px; border-radius: 6px; padding: 12px; max-height: 260px; overflow: auto;
}
.log-line { line-height: 1.7; white-space: pre-wrap; word-break: break-all; }
button {
  padding: 6px 14px; border: 1px solid #2f6fed; background: #2f6fed; color: #fff;
  border-radius: 6px; cursor: pointer; font-size: 13px;
}
button:disabled { opacity: .45; cursor: not-allowed; }
button.ghost { background: #fff; color: #2f6fed; }
input[type="file"] { font-size: 13px; }
.wall { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 10px; }
.wall figure { margin: 0; }
.wall img {
  width: 100%; aspect-ratio: 640 / 420; object-fit: cover; display: block;
  border-radius: 6px; background: #eef1f6;
}
.wall figcaption { font-size: 12px; color: #8a94a6; margin-top: 4px; }
</style>
