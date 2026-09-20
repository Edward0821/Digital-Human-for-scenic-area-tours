<script setup>
const modules = [
  { name: '知识库管理', desc: '讲解词、文史资料、FAQ 的上传、更新与维护' },
  { name: '数字人形象管理', desc: '外观、服装、声音配置与发布' },
  { name: '游客感受度报告', desc: '关注点分析、情感趋势、服务建议' },
  { name: '数据大屏概览', desc: '服务人次、热门问答、满意度趋势' },
]
</script>

<template>
  <section class="admin">
    <h1>管理后台</h1>
    <p class="desc">以下模块与《需求分析文档》功能架构图对应，开发中逐步交付。</p>
    <div class="grid">
      <div v-for="m in modules" :key="m.name" class="card">
        <h3>{{ m.name }}</h3>
        <p>{{ m.desc }}</p>
      </div>
    </div>
  </section>
</template>

<style scoped>
.admin {
  max-width: 960px;
  margin: 0 auto;
}

.desc {
  color: #667;
}

.grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}

.card {
  border: 1px solid #dde3ec;
  border-radius: 8px;
  padding: 16px;
  background: #fff;
}
</style>
