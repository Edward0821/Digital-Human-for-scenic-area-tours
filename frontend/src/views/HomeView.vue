<script setup>
import { onMounted, ref } from 'vue'
import { getHealth } from '../api/health'

const question = ref('')
const health = ref(null)

onMounted(async () => {
  try {
    health.value = await getHealth()
  } catch {
    health.value = { status: 'UNREACHABLE' }
  }
})

function send() {
  if (!question.value.trim()) return
  alert(`问答功能建设中，你输入了：${question.value}`)
}
</script>

<template>
  <section class="home">
    <h1>游客问答</h1>
    <p class="desc">支持文字、语音提问与拍照识景，回答由本地知识库检索增强保证准确性。</p>

    <div class="ask-box">
      <input
        v-model="question"
        type="text"
        placeholder="例如：这个景点有什么历史故事？"
        @keyup.enter="send"
      />
      <button @click="send">发送</button>
    </div>

    <div class="health-card">
      <h3>后端服务状态（/api/health）</h3>
      <template v-if="health">
        <p>应用：{{ health.app }} — {{ health.status }}</p>
        <p v-if="health.components">
          MySQL：{{ health.components.mysql }} ｜ Redis：{{ health.components.redis }}
        </p>
        <p v-if="health.time">检测时间：{{ health.time }}</p>
        <p v-if="health.status === 'UNREACHABLE'">
          后端未启动或不可达：请先运行 backend 工程（默认 8080 端口）
        </p>
      </template>
      <p v-else>检测中…</p>
    </div>
  </section>
</template>

<style scoped>
.home {
  max-width: 720px;
  margin: 0 auto;
}

.desc {
  color: #667;
}

.ask-box {
  display: flex;
  gap: 8px;
  margin: 16px 0 32px;
}

.ask-box input {
  flex: 1;
  padding: 10px 12px;
  border: 1px solid #ccd;
  border-radius: 6px;
}

.ask-box button {
  padding: 10px 24px;
  border: none;
  border-radius: 6px;
  background: #2a6fdb;
  color: #fff;
  cursor: pointer;
}

.health-card {
  border: 1px solid #dde3ec;
  border-radius: 8px;
  padding: 16px;
  background: #f7f9fc;
}
</style>
