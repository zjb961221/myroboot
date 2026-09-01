<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { apiErrorMessage, showToast } from './uiFeedback'

const apiBase = import.meta.env.VITE_API_BASE || '/api'
const token = localStorage.getItem('support_token') || ''
const agents = ref([])
const agentId = ref(Number(new URLSearchParams(location.search).get('agent') || 0))
const username = ref('')
const password = ref('')
const port = ref(3389)
const opening = ref(false)
const sessionId = ref('')
const frameUrl = ref('')
const full = ref(false)
const selected = computed(() => agents.value.find(a => Number(a.id) === Number(agentId.value)))

function headers(extra={}) { return { ...extra, Authorization:`Bearer ${token}` } }
async function request(url, options={}) {
  options.headers = headers(options.headers || {})
  const res = await fetch(url, options)
  if (res.status === 401 || res.status === 403) throw new Error('当前账号没有远程桌面权限')
  if (!res.ok) throw new Error(await apiErrorMessage(res))
  return res.status === 204 ? null : res.json()
}
async function load() {
  try {
    agents.value = await request(`${apiBase}/admin/remote/agents`)
    if (!agentId.value && agents.value.length) agentId.value = Number(agents.value[0].id)
  } catch (e) { showToast(e.message) }
}
async function openDesktop() {
  if (!selected.value) return showToast('请选择服务器')
  if (Number(selected.value.online) !== 1) return showToast('Agent 当前离线')
  if (!username.value.trim() || !password.value) return showToast('请输入 GNOME 远程桌面用户名和密码')
  await closeDesktop(false)
  opening.value = true
  try {
    const grant = await request(`${apiBase}/admin/remote/agents/${selected.value.id}/desktop-sessions`, {
      method:'POST', headers:{'Content-Type':'application/json'},
      body:JSON.stringify({username:username.value.trim(), password:password.value, port:Number(port.value)})
    })
    sessionId.value = grant.sessionId
    frameUrl.value = `${grant.path}?data=${encodeURIComponent(grant.data)}`
    password.value = ''
    showToast('远程桌面隧道已建立', 'success')
  } catch (e) { showToast(e.message) }
  finally { opening.value = false }
}
async function closeDesktop(notify=true) {
  const id = sessionId.value
  sessionId.value = ''
  frameUrl.value = ''
  if (id) {
    try { await request(`${apiBase}/admin/remote/desktop-sessions/${encodeURIComponent(id)}`, {method:'DELETE'}) }
    catch (e) { if (notify) showToast(e.message) }
  }
}
async function toggleFullscreen() {
  full.value = !full.value
  if (full.value) {
    try { await document.documentElement.requestFullscreen?.() } catch {}
  } else {
    try { if (document.fullscreenElement) await document.exitFullscreen?.() } catch {}
  }
}
onMounted(load)
onBeforeUnmount(() => closeDesktop(false))
</script>

<template>
<main class="desktop-page" :class="{fullscreen:full}">
  <header class="toolbar">
    <div>
      <a class="back" href="/admin/remote">← 远程运维中心</a>
      <strong>{{selected?.name || 'GNOME 远程桌面'}}</strong>
      <span v-if="selected">{{selected.hostname || '-'}} · Agent {{selected.agent_version || '-'}}</span>
    </div>
    <div class="toolbar-actions">
      <button v-if="frameUrl" @click="toggleFullscreen">{{full?'退出全屏':'全屏'}}</button>
      <button v-if="frameUrl" class="danger" @click="closeDesktop()">断开</button>
    </div>
  </header>

  <section v-if="!frameUrl" class="connect-card">
    <div class="intro">
      <span class="badge">GNOME REMOTE DESKTOP</span>
      <h1>连接 Ubuntu 图形桌面</h1>
      <p>MYROBOOT 通过 Agent 的出站 WebSocket 建立受控 RDP 隧道。矿端不需要向公网开放 3389。</p>
    </div>
    <label>服务器
      <select v-model.number="agentId">
        <option v-for="a in agents" :key="a.id" :value="Number(a.id)">{{a.name}} · {{Number(a.online)===1?'在线':'离线'}} · Agent {{a.agent_version||'-'}}</option>
      </select>
    </label>
    <div class="two">
      <label>GNOME Remote Desktop 用户名<input v-model="username" autocomplete="off" placeholder="桌面共享设置里的用户名"></label>
      <label>RDP 端口<input v-model.number="port" type="number" min="3389" max="3399"></label>
    </div>
    <label>GNOME Remote Desktop 密码<input v-model="password" type="password" autocomplete="new-password" placeholder="仅本次会话使用，不保存到数据库"></label>
    <div class="security">
      <strong>安全边界</strong>
      <span>凭据只用于生成 90 秒有效的加密 Guacamole 授权，不写入 MYROBOOT 数据库；Agent 只允许连接本机 127.0.0.1 的 3389-3399 端口。</span>
    </div>
    <button class="primary connect" :disabled="opening || !selected || Number(selected?.online)!==1" @click="openDesktop">{{opening?'正在建立安全隧道…':'连接远程桌面'}}</button>
    <p class="hint">Ubuntu 24.04：设置 → 系统 → 远程桌面 → 桌面共享，开启“桌面共享”和“远程控制”。如果同时启用了 Remote Login，桌面共享端口可能变为 3390。</p>
  </section>

  <section v-else class="viewer">
    <iframe :src="frameUrl" allow="clipboard-read; clipboard-write; fullscreen" referrerpolicy="no-referrer"></iframe>
  </section>
</main>
</template>

<style scoped>
.desktop-page{min-height:100vh;background:#07101d;color:#e8eef7;display:grid;grid-template-rows:auto 1fr}.toolbar{height:64px;padding:0 18px;background:#0d1929;border-bottom:1px solid #263750;display:flex;align-items:center;justify-content:space-between;gap:16px}.toolbar>div:first-child{display:flex;align-items:center;gap:14px;min-width:0}.toolbar strong{font-size:15px}.toolbar span{color:#91a1b8;font-size:13px}.back{color:#a9c7f7;text-decoration:none}.toolbar-actions{display:flex;gap:8px}.toolbar button{border:1px solid #43536a;background:#172438;color:#fff;border-radius:8px;padding:8px 13px}.toolbar button.danger{border-color:#7a3540;background:#4f1f28}.connect-card{width:min(780px,calc(100vw - 32px));margin:46px auto;align-self:start;background:#fff;color:#172033;border-radius:18px;padding:26px;display:grid;gap:16px;box-shadow:0 24px 80px #0006}.intro h1{margin:9px 0 5px}.intro p,.hint{color:#66758b}.connect-card label{display:grid;gap:7px;font-weight:600}.connect-card input,.connect-card select{width:100%;box-sizing:border-box;border:1px solid #ccd5e1;border-radius:9px;padding:11px;background:#fff}.two{display:grid;grid-template-columns:1fr 180px;gap:12px}.security{background:#f2f7ff;border:1px solid #d4e4fb;border-radius:11px;padding:12px;display:grid;gap:4px;font-size:13px;color:#4c617d}.connect{border:0;border-radius:10px;padding:12px 18px;font-weight:700;background:#1769d2;color:white}.connect:disabled{opacity:.5}.viewer{min-height:0}.viewer iframe{display:block;width:100%;height:calc(100vh - 64px);border:0;background:#050a12}.fullscreen .toolbar{height:50px}.fullscreen .viewer iframe{height:calc(100vh - 50px)}.badge{font-size:11px;letter-spacing:.12em;color:#1769d2}.hint{font-size:13px;margin:0}@media(max-width:700px){.toolbar span{display:none}.two{grid-template-columns:1fr}.connect-card{margin:18px auto;padding:18px}}
</style>
