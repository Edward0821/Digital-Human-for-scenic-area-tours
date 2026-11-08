<script setup>
/**
 * 登录/注册页（第九周：登录方式 ≥2 种 + 验证码 + 忘记密码）。
 * 三张卡：登录（密码方式/邮箱验证码方式 Tab 切换）、注册、忘记密码。
 * 密码方式：用户名 + 密码 + 图形验证码（点击图片刷新）；邮箱方式：邮箱 + 6 位码（60s 倒计时）。
 */
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { http } from '../api/http'
import { saveLogin } from '../api/auth'

const router = useRouter()
const mode = ref('login') // login | register | reset
const loginWay = ref('PASSWORD') // PASSWORD | EMAIL_CODE
const msg = ref('')
const ok = ref(false)

const captcha = ref({ captchaId: '', imageBase64: '' })
const emailCountdown = ref(0)

const loginForm = reactive({ username: '', password: '', captchaCode: '', email: '', emailCode: '' })
const regForm = reactive({ username: '', password: '', nickname: '', email: '' })
const resetForm = reactive({ email: '', emailCode: '', newPassword: '' })

async function refreshCaptcha() {
  const { data: res } = await http.get('/auth/captcha')
  captcha.value = res.data
  loginForm.captchaCode = ''
}
refreshCaptcha()

async function sendEmailCode() {
  if (emailCountdown.value > 0) return
  const email = mode.value === 'reset' ? resetForm.email : loginForm.email
  if (!email) { msg.value = '请先填邮箱'; ok.value = false; return }
  try {
    await http.post('/auth/email-code', { email, scene: mode.value === 'reset' ? 'RESET' : 'LOGIN' })
    msg.value = '验证码已发送（10 分钟内有效；本机未配 SMTP 时看后端日志）'
    ok.value = true
    emailCountdown.value = 60
    const timer = setInterval(() => {
      if (--emailCountdown.value <= 0) clearInterval(timer)
    }, 1000)
  } catch (e) {
    msg.value = e.response?.data?.message || e.message
    ok.value = false
  }
}

function afterLogin(data) {
  saveLogin(data)
  router.push(data.role === 'ADMIN' ? '/perm' : '/')
}

async function doLogin() {
  try {
    const body = loginWay.value === 'PASSWORD'
      ? { loginType: 'PASSWORD', username: loginForm.username, password: loginForm.password,
          captchaId: captcha.value.captchaId, captchaCode: loginForm.captchaCode }
      : { loginType: 'EMAIL_CODE', email: loginForm.email, emailCode: loginForm.emailCode }
    const { data: res } = await http.post('/auth/login', body)
    afterLogin(res.data)
  } catch (e) {
    msg.value = e.response?.data?.message || e.message
    ok.value = false
    if (loginWay.value === 'PASSWORD') refreshCaptcha()
  }
}

async function doRegister() {
  try {
    await http.post('/auth/register', regForm)
    msg.value = '注册成功，请登录'
    ok.value = true
    loginForm.username = regForm.username
    mode.value = 'login'
  } catch (e) {
    msg.value = e.response?.data?.message || e.message
    ok.value = false
  }
}

async function doReset() {
  try {
    await http.post('/auth/reset-password', resetForm)
    msg.value = '密码已重置，请用新密码登录'
    ok.value = true
    mode.value = 'login'
  } catch (e) {
    msg.value = e.response?.data?.message || e.message
    ok.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <div class="card">
      <div class="tabs">
        <button :class="{ active: mode === 'login' }" @click="mode = 'login'">登录</button>
        <button :class="{ active: mode === 'register' }" @click="mode = 'register'">注册</button>
        <button :class="{ active: mode === 'reset' }" @click="mode = 'reset'">忘记密码</button>
      </div>

      <!-- 登录：双方式 -->
      <div v-if="mode === 'login'">
        <div class="way-switch">
          <button :class="{ active: loginWay === 'PASSWORD' }" @click="loginWay = 'PASSWORD'">账号密码</button>
          <button :class="{ active: loginWay === 'EMAIL_CODE' }" @click="loginWay = 'EMAIL_CODE'">邮箱验证码</button>
        </div>
        <template v-if="loginWay === 'PASSWORD'">
          <input v-model="loginForm.username" placeholder="用户名" />
          <input v-model="loginForm.password" type="password" placeholder="密码" />
          <div class="captcha-row">
            <input v-model="loginForm.captchaCode" placeholder="图形验证码" />
            <img v-if="captcha.imageBase64" :src="captcha.imageBase64" title="点击刷新" @click="refreshCaptcha" />
          </div>
        </template>
        <template v-else>
          <input v-model="loginForm.email" placeholder="注册时的邮箱" />
          <div class="captcha-row">
            <input v-model="loginForm.emailCode" placeholder="6 位邮箱验证码" />
            <button class="ghost" :disabled="emailCountdown > 0" @click="sendEmailCode">
              {{ emailCountdown > 0 ? emailCountdown + 's' : '发送验证码' }}
            </button>
          </div>
        </template>
        <button class="primary" @click="doLogin">登 录</button>
      </div>

      <!-- 注册 -->
      <div v-else-if="mode === 'register'">
        <input v-model="regForm.username" placeholder="用户名（3~50 字符）" />
        <input v-model="regForm.password" type="password" placeholder="密码" />
        <input v-model="regForm.nickname" placeholder="昵称（可选）" />
        <input v-model="regForm.email" placeholder="邮箱（可选，用于验证码登录/找回密码）" />
        <button class="primary" @click="doRegister">注 册</button>
      </div>

      <!-- 忘记密码 -->
      <div v-else>
        <input v-model="resetForm.email" placeholder="注册时的邮箱" />
        <div class="captcha-row">
          <input v-model="resetForm.emailCode" placeholder="6 位邮箱验证码" />
          <button class="ghost" :disabled="emailCountdown > 0" @click="sendEmailCode">
            {{ emailCountdown > 0 ? emailCountdown + 's' : '发送验证码' }}
          </button>
        </div>
        <input v-model="resetForm.newPassword" type="password" placeholder="新密码" />
        <button class="primary" @click="doReset">重置密码</button>
      </div>

      <p v-if="msg" :class="ok ? 'ok' : 'err'">{{ msg }}</p>
    </div>
  </div>
</template>

<style scoped>
.login-wrap { display: flex; justify-content: center; padding-top: 40px; }
.card {
  width: 380px; background: #fff; border: 1px solid #e3e8f0; border-radius: 10px;
  padding: 24px; display: flex; flex-direction: column; gap: 12px;
}
.tabs, .way-switch { display: flex; gap: 8px; }
.tabs button, .way-switch button {
  flex: 1; padding: 8px; border: 1px solid #dbe2ee; background: #f6f8fb; border-radius: 6px; cursor: pointer;
}
.tabs button.active, .way-switch button.active { background: #2f6fed; color: #fff; border-color: #2f6fed; }
input {
  padding: 10px; border: 1px solid #dbe2ee; border-radius: 6px; font-size: 14px; box-sizing: border-box; width: 100%;
}
.captcha-row { display: flex; gap: 8px; align-items: center; }
.captcha-row input { flex: 1; }
.captcha-row img { height: 40px; border-radius: 6px; cursor: pointer; }
.primary { padding: 10px; background: #2f6fed; color: #fff; border: none; border-radius: 6px; cursor: pointer; }
.ghost { background: #fff; color: #2f6fed; border: 1px solid #2f6fed; border-radius: 6px; padding: 8px 10px; cursor: pointer; white-space: nowrap; }
.ok { color: #1fa667; font-size: 13px; margin: 0; }
.err { color: #d93026; font-size: 13px; margin: 0; }
</style>
