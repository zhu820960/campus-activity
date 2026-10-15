<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowRight } from '../icons'
import { api } from '../api'
import { homePath, setUser } from '../session'

const route = useRoute()
const router = useRouter()
const busy = ref(false)
const error = ref('')
const form = reactive({ email: '', password: '' })

async function submit() {
  busy.value = true
  error.value = ''
  try {
    const account = await api('/auth/login', {
      method: 'POST', body: { email: form.email, password: form.password },
    })
    if (!account.roles?.some((role) => role === 'ADMIN' || role === 'ORGANIZER')) {
      await api('/auth/logout', { method: 'POST' }).catch(() => {})
      setUser(null)
      throw new Error('学生 Web 入口已停用，小程序学生端正在开发中。')
    }
    setUser(account)
    const next = typeof route.query.next === 'string' && route.query.next.startsWith('/')
      && !route.query.next.startsWith('//') ? route.query.next : homePath(account)
    router.replace(next)
  } catch (cause) {
    error.value = cause.message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="auth-experience">
    <div class="auth-visual">
      <div class="auth-visual-copy">
        <p class="auth-kicker">CAMPUS ACTIVITIES</p>
        <h1>校园活动</h1>
        <p>报名与签到管理系统</p>
      </div>
    </div>
    <div class="auth-workspace">
      <div class="auth-workspace-inner">
        <p class="auth-form-kicker">校园活动</p>
        <form class="login-form" @submit.prevent="submit">
          <h2>管理端登录</h2>
          <label>管理员账号 / 组织者学号<input v-model.trim="form.email" type="text" autocomplete="username" required /></label>
          <label>密码<input v-model="form.password" type="password" autocomplete="current-password" minlength="8" required /></label>
          <p v-if="error" class="message error" role="alert">{{ error }}</p>
          <button class="button primary full" :disabled="busy" type="submit">{{ busy ? '请稍候…' : '登录' }}<ArrowRight :size="17" /></button>
        </form>
      </div>
    </div>
  </div>
</template>
