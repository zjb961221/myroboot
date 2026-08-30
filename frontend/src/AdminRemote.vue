<script setup>
import { computed, onMounted, ref } from 'vue'
import { apiErrorMessage, showToast } from './uiFeedback'
import { copyText } from './clipboard'

const apiBase = import.meta.env.VITE_API_BASE || '/api'
const token = localStorage.getItem('support_token') || ''
const agents = ref([])
const audit = ref([])
const loading = ref(false)
const form = ref({ name: '', mineName: '' })
const created = ref(null)

const onlineCount = computed(() => agents.value.filter(a => Number(a.online) === 1).length)
function headers(extra={}) { return { ...extra, Authorization: `Bearer ${token}` } }
async function request(url, options={}) {
  options.headers = headers(options.headers || {})
  const res = await fetch(url, options)
  if (res.status === 401 || res.status === 403) throw new Error('当前账号没有远程运维管理权限')
  if (!res.ok) throw new Error(await apiErrorMessage(res))
  return res.status === 204 ? null : res.json()
}
function time(v){ return v ? String(v).replace('T',' ').slice(0,19) : '-' }
async function load(){
  loading.value = true
  try {
    const [a,l] = await Promise.all([request(`${apiBase}/admin/remote/agents`), request(`${apiBase}/admin/remote/audit?limit=50`)])
    agents.value = a; audit.value = l
  } catch(e){ showToast(e.message) } finally { loading.value=false }
}
async function createAgent(){
  if(!form.value.name.trim()) return showToast('请填写服务器名称')
  try{
    created.value = await request(`${apiBase}/admin/remote/agents`, { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(form.value) })
    form.value={name:'',mineName:''}; await load(); showToast('Agent 已创建，Token 只显示这一次','success')
  }catch(e){ showToast(e.message) }
}
async function rotate(a){
  if(!confirm(`确定重新生成 ${a.name} 的 Agent Token 吗？旧 Token 会立即失效。`)) return
  try{
    const r = await request(`${apiBase}/admin/remote/agents/${a.id}/rotate-token`, {method:'POST'})
    created.value = { agentId:a.agent_id, token:r.token, name:a.name, mineName:a.mine_name || '' }
    showToast('Token 已重新生成','success')
  }catch(e){showToast(e.message)}
}
async function toggle(a){
  try{
    await request(`${apiBase}/admin/remote/agents/${a.id}/enabled`, {method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({enabled:!Boolean(Number(a.enabled))})})
    await load(); showToast('状态已更新','success')
  }catch(e){showToast(e.message)}
}
function installEnv(){
  if(!created.value) return ''
  return `MYROBOOT_SERVER=${location.origin}\nMYROBOOT_AGENT_ID=${created.value.agentId}\nMYROBOOT_AGENT_TOKEN=${created.value.token}`
}
async function copyConfig(){ try{ await copyText(installEnv()); showToast('Agent 配置已复制','success') }catch(e){showToast(e.message)} }
onMounted(load)
</script>

<template>
  <main class="page remote-page">
    <section class="admin-header">
      <div><span class="badge">MYROBOOT REMOTE</span><h1>远程运维中心</h1><p>管理煤矿 Ubuntu 被控端。第一阶段先完成 Agent 注册、身份认证、心跳和审计。</p></div>
      <div class="header-actions"><a class="back-link" href="/admin">返回管理后台</a><button class="secondary" @click="load">{{loading?'刷新中…':'刷新'}}</button></div>
    </section>

    <section class="remote-stats">
      <div class="panel"><small>服务器</small><strong>{{agents.length}}</strong></div>
      <div class="panel"><small>在线</small><strong>{{onlineCount}}</strong></div>
      <div class="panel"><small>离线 / 停用</small><strong>{{agents.length-onlineCount}}</strong></div>
    </section>

    <section class="remote-grid">
      <div class="panel">
        <div class="panel-title"><div><h2>服务器列表</h2><p class="muted">90 秒内有心跳视为在线。</p></div></div>
        <div v-if="!agents.length" class="empty">还没有注册 Agent。</div>
        <div class="agent-list">
          <article v-for="a in agents" :key="a.id" class="agent-card">
            <div class="agent-main"><span class="online-dot" :class="{up:Number(a.online)===1}"></span><div><strong>{{a.name}}</strong><small>{{a.mine_name||'未填写矿井'}} · {{a.hostname||'等待首次心跳'}}</small></div></div>
            <div class="agent-meta"><span>{{a.os_name||'-'}}</span><span>IP {{a.private_ip||'-'}}</span><span>桌面 {{a.desktop_session||'-'}}</span><span>最后在线 {{time(a.last_seen)}}</span></div>
            <div class="row-actions"><button class="secondary small-btn" @click="rotate(a)">重置 Token</button><button class="small-btn" :class="Number(a.enabled)===1?'danger':'success'" @click="toggle(a)">{{Number(a.enabled)===1?'停用':'启用'}}</button><button class="primary small-btn" disabled title="下一阶段接入 WebSocket/PTY">终端（开发中）</button><button class="primary small-btn" disabled title="后续接入 Ubuntu 桌面共享">桌面（开发中）</button></div>
          </article>
        </div>
      </div>

      <div class="panel create-agent">
        <h2>新增被控端</h2>
        <label>服务器名称<input v-model="form.name" placeholder="例如：骆驼山前置机" /></label>
        <label>矿井名称<input v-model="form.mineName" placeholder="例如：骆驼山煤矿" /></label>
        <button class="primary" @click="createAgent">生成 Agent 身份</button>
        <div v-if="created" class="token-box">
          <strong>{{created.name}} 的一次性配置</strong>
          <p>Token 不会再次明文显示。部署完成后如遗失，只能重新生成。</p>
          <textarea :value="installEnv()" readonly rows="6"></textarea>
          <button class="secondary" @click="copyConfig">复制配置</button>
        </div>
      </div>
    </section>

    <section class="panel audit-panel">
      <div class="panel-title"><div><h2>远程运维审计</h2><p class="muted">记录 Agent 创建、停用、Token 轮换；终端和桌面会话随后接入。</p></div></div>
      <div class="table-wrap"><table><thead><tr><th>时间</th><th>服务器</th><th>操作人</th><th>动作</th><th>详情</th><th>来源 IP</th></tr></thead><tbody><tr v-for="l in audit" :key="l.id"><td>{{time(l.create_time)}}</td><td>{{l.agent_name||'-'}}</td><td>{{l.operator_name||'-'}}</td><td>{{l.action_type}}</td><td>{{l.detail||'-'}}</td><td>{{l.client_ip||'-'}}</td></tr></tbody></table></div>
    </section>
  </main>
</template>

<style scoped>
.remote-page{display:grid;gap:20px}.remote-stats{display:grid;grid-template-columns:repeat(3,1fr);gap:14px}.remote-stats .panel{display:grid;gap:6px}.remote-stats small{color:#7d8da4}.remote-stats strong{font-size:30px}.remote-grid{display:grid;grid-template-columns:1.5fr .7fr;gap:20px}.agent-list{display:grid;gap:12px}.agent-card{border:1px solid #e3e9f1;border-radius:14px;padding:16px;display:grid;gap:13px}.agent-main{display:flex;gap:10px;align-items:center}.agent-main>div{display:grid;gap:3px}.agent-main small,.agent-meta{color:#7d8da4;font-size:13px}.online-dot{width:10px;height:10px;border-radius:50%;background:#a8b2c0;box-shadow:0 0 0 4px #f0f2f5}.online-dot.up{background:#1d9b63;box-shadow:0 0 0 4px #e6f6ef}.agent-meta{display:flex;gap:16px;flex-wrap:wrap}.create-agent{display:grid;gap:15px;align-content:start}.create-agent h2{margin:0}.token-box{border:1px solid #f1d28b;background:#fffaf0;border-radius:12px;padding:14px;display:grid;gap:10px}.token-box p{margin:0;color:#7a6845;font-size:13px}.token-box textarea{width:100%;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;border:1px solid #e2c980;border-radius:9px;padding:10px;resize:vertical}.audit-panel table{min-width:900px}@media(max-width:900px){.remote-stats,.remote-grid{grid-template-columns:1fr}.agent-meta{display:grid;gap:5px}}
</style>
