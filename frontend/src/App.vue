<script setup>
import { RouterLink, RouterView, useRouter } from 'vue-router'
import { authState, clearLogin } from './api/auth'
import { http } from './api/http'

const router = useRouter()

function logout() {
  http.post('/auth/logout').catch(() => {}).finally(() => {
    clearLogin()
    router.push('/login')
  })
}
</script>

<template>
  <div class="layout">
    <header class="header">
      <span class="brand">景区导览服务 AI 数字人系统（第 7 组）</span>
      <nav>
        <RouterLink to="/">游客问答</RouterLink>
        <RouterLink to="/admin">管理后台</RouterLink>
        <RouterLink to="/files">文件存储</RouterLink>
        <RouterLink v-if="authState.admin" to="/perm">权限管理</RouterLink>
        <span v-if="authState.loggedIn" class="user-area">
          {{ authState.user.username }}（{{ authState.user.role }}）
          <a href="#" @click.prevent="logout">退出</a>
        </span>
        <RouterLink v-else to="/login">登录 / 注册</RouterLink>
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

nav a, nav .user-area {
  color: #cdd9ea;
  margin-left: 16px;
  text-decoration: none;
}

nav .user-area a {
  color: #ffd479;
  margin-left: 8px;
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
