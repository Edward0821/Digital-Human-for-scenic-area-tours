<script setup>
import { RouterLink, RouterView } from 'vue-router'
</script>

<template>
  <div class="layout">
    <header class="header">
      <span class="brand">景区导览服务 AI 数字人系统（第 7 组）</span>
      <nav>
        <RouterLink to="/">游客问答</RouterLink>
        <RouterLink to="/admin">管理后台</RouterLink>
      </nav>
    </header>
    <main class="main">
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.layout {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 24px;
  height: 56px;
  background: #1f3a5f;
  color: #fff;
}

.brand {
  font-weight: 600;
}

nav a {
  color: #cdd9ea;
  margin-left: 16px;
  text-decoration: none;
}

nav a.router-link-active {
  color: #fff;
  font-weight: 600;
}

.main {
  flex: 1;
  padding: 24px;
}
</style>
