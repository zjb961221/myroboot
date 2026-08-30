<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import { apiErrorMessage, showToast } from './uiFeedback'
import { copyText } from './clipboard'

const apiBase = import.meta.env.VITE_API_BASE || '/api'
const token = localStorage.getItem('support_token') || ''
const agents = ref([])
const audit = ref([])
const loading = ref(false)
const form = ref({ name: '', mineName: '' })
const created = ref(null)
const terminalOpen = ref(false)
const terminalAgent = ref(null)
const terminalEl = ref(null)
let ws = null
let terminal = null
let fitAddon = null
let inputDisposable = null
let resizeHandler = null

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

async function openTerminal(a){
  if(Number(a.online)!==1) return showToast('Agent 当前离线')
  closeTerminal(false)
  terminalAgent.value = a
  terminalOpen.value = true
  await nextTick()
  terminal = new Terminal({
    cursorBlink:true,
    convertEol:false,
    fontFamily:'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace',
    fontSize:14,
    scrollback:5000,
    theme:{background:'#0b1220'}
  })
  fitAddon = new FitAddon()
  terminal.loadAddon(fitAddon)
  terminal.open(terminalEl.value)
  fitAddon.fit()
  terminal.focus()

  try {
    const grant = await request(`${apiBase}/admin/remote/agents/${a.id}/terminal-ticket`, {method:'POST'})
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
    ws = new WebSocket(`${scheme}://${location.host}/api/remote/ws/terminal?ticket=${encodeURIComponent(grant.ticket)}`)
    ws.onopen = () => {
      fitAddon?.fit()
      sendResize()
      inputDisposable = terminal.onData(data => {
        if(ws?.readyState===WebSocket.OPEN) ws.send(JSON.stringify({type:'input',data}))
      })
    }
    ws.onmessage = event => {
      try {
        const msg = JSON.parse(event.data)
        if(msg.type==='terminal_output' && msg.data){
          const binary = atob(msg.data)
          const bytes = Uint8Array.from(binary, c => c.charCodeAt(0))
          terminal?.write(new TextDecoder().decode(bytes))
        } else if(msg.type==='terminal_error') {
          terminal?.writeln(`\r\n[错误] ${msg.message||'远程终端异常'}`)
        } else if(msg.type==='terminal_closed') {
          terminal?.writeln('\r\n[会话已关闭]')
        }
      } catch(e){ terminal?.writeln(`\r\n[协议解析失败] ${e.message}`) }
    }
    ws.onerror = () => terminal?.writeln('\r\n[连接异常] 请检查 Agent 实时通道和 Nginx WebSocket 配置。')
    ws.onclose = () => terminal?.writeln('\r\n[连接已断开]')
    resizeHandler = () => { fitAddon?.fit(); sendResize() }
    window.addEventListener('resize', resizeHandler)
  } catch(e) {
    terminal?.writeln(`\r\n[无法打开终端] ${e.message}`)
    showToast(e.message)
  }
}
function sendResize(){
  if(ws?.readyState===WebSocket.OPEN && terminal) ws.send(JSON.stringify({type:'resize',cols:terminal.cols,rows:terminal.rows}))
}
function closeTerminal(notify=true){
  if(notify && ws?.readyState===WebSocket.OPEN){ try{ws.send(JSON.stringify({type:'close'}))}catch{} }
  try{ws?.close()}catch{}
  ws=null
  inputDisposable?.dispose(); inputDisposable=null
  terminal?.dispose(); terminal=null; fitAddon=null
  if(resizeHandler) window.removeEventListener('resize',resizeHandler)
  resizeHandler=null
  terminalOpen.value=false; terminalAgent.value=null
}

onMounted(load)
onBeforeUnmount(()=>closeTerminal(false))
</script>

<template>
  <main class="page remote-page">
    <section class="admin-header">
      <div><span class="badge">MYROBOOT REMOTE</span><h1>远程运维中心</h1><p>管理煤矿 Ubuntu 被控端。已接入 Agent 身份、心跳、实时通道和受控诊断终端。</p></div>
      <div class="header-actions"><a class="back-link" href="/admin">返回管理后台</a><button class="secondary" @click="load">{{loading?'刷新中…':'刷新'}}</button></div>
    </section>

    <section class="remote-stats">
      <div class="panel"><small>服务器</small><strong>{{agents.length}}</strong></div>
      <div class="panel"><small>在线</small><strong>{{onlineCount}}</strong></div>
      <div class="panel"><small>离线 / 停用</small><strong>{{agents.length-onlineCount}}</strong></div>
    </section>

    <section class="remote-grid">
      <div class="panel">
        <div class="panel-title"><div><h2>服务器列表</h2><p class="muted">90 秒内有心跳视为在线。Agent 0.2.0+ 同时建立 WebSocket 实时通道。</p></div></div>
        <div v-if="!agents.length" class="empty">还没有注册 Agent。</div>
        <div class="agent-list">
          <article v-for="a in agents" :key="a.id" class="agent-card">
            <div class="agent-main"><span class="online-dot" :class="{up:Number(a.online)===1}"></span><div><strong>{{a.name}}</strong><small>{{a.mine_name||'未填写矿井'}} · {{a.hostname||'等待首次心跳'}}</small></div></div>
            <div class="agent-meta"><span>{{a.os_name||'-'}}</span><span>Agent {{a.agent_version||'-'}}</span><span>IP {{a.private_ip||'-'}}</span><span>桌面 {{a.desktop_session||'-'}}</span><span>最后在线 {{time(a.last_seen)}}</span></div>
            <div class="row-actions"><button class="secondary small-btn" @click="rotate(a)">重置 Token</button><button class="small-btn" :class="Number(a.enabled)===1?'danger':'success'" @click="toggle(a)">{{Number(a.enabled)===1?'停用':'启用'}}</button><button class="primary small-btn" :disabled="Number(a.online)!==1" @click="openTerminal(a)">诊断终端</button><button class="primary small-btn" disabled title="后续接入 Ubuntu 桌面共享">桌面（开发中）</button></div>
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
      <div class="panel-title"><div><h2>远程运维审计</h2><p class="muted">记录 Agent 生命周期和终端会话，不记录终端输入内容。</p></div></div>
      <div class="table-wrap"><table><thead><tr><th>时间</th><th>服务器</th><th>操作人</th><th>动作</th><th>详情</th><th>来源 IP</th></tr></thead><tbody><tr v-for="l in audit" :key="l.id"><td>{{time(l.create_time)}}</td><td>{{l.agent_name||'-'}}</td><td>{{l.operator_name||'-'}}</td><td>{{l.action_type}}</td><td>{{l.detail||'-'}}</td><td>{{l.client_ip||'-'}}</td></tr></tbody></table></div>
    </section>

    <div v-if="terminalOpen" class="terminal-overlay" @click.self="closeTerminal()">
      <section class="terminal-dialog">
        <div class="terminal-head"><div><strong>{{terminalAgent?.name}} · 诊断终端</strong><small>受控只读命令：help / uptime / df / free / ip / docker ps / systemctl status / journalctl</small></div><button class="secondary small-btn" @click="closeTerminal()">关闭</button></div>
        <div ref="terminalEl" class="terminal-screen"></div>
      </section>
    </div>
  </main>
</template>

<style scoped>
.remote-page{display:grid;gap:20px}.remote-stats{display:grid;grid-template-columns:repeat(3,1fr);gap:14px}.remote-stats .panel{display:grid;gap:6px}.remote-stats small{color:#7d8da4}.remote-stats strong{font-size:30px}.remote-grid{display:grid;grid-template-columns:1.5fr .7fr;gap:20px}.agent-list{display:grid;gap:12px}.agent-card{border:1px solid #e3e9f1;border-radius:14px;padding:16px;display:grid;gap:13px}.agent-main{display:flex;gap:10px;align-items:center}.agent-main>div{display:grid;gap:3px}.agent-main small,.agent-meta{color:#7d8da4;font-size:13px}.online-dot{width:10px;height:10px;border-radius:50%;background:#a8b2c0;box-shadow:0 0 0 4px #f0f2f5}.online-dot.up{background:#1d9b63;box-shadow:0 0 0 4px #e6f6ef}.agent-meta{display:flex;gap:16px;flex-wrap:wrap}.create-agent{display:grid;gap:15px;align-content:start}.create-agent h2{margin:0}.token-box{border:1px solid #f1d28b;background:#fffaf0;border-radius:12px;padding:14px;display:grid;gap:10px}.token-box p{margin:0;color:#7a6845;font-size:13px}.token-box textarea{width:100%;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;border:1px solid #e2c980;border-radius:9px;padding:10px;resize:vertical}.audit-panel table{min-width:900px}.terminal-overlay{position:fixed;inset:0;background:rgba(5,12,24,.68);z-index:1000;padding:34px;display:grid;place-items:center}.terminal-dialog{width:min(1280px,96vw);height:min(820px,90vh);background:#0b1220;border-radius:16px;overflow:hidden;display:grid;grid-template-rows:auto 1fr;box-shadow:0 30px 90px rgba(0,0,0,.4)}.terminal-head{background:#111c2f;color:#fff;padding:13px 16px;display:flex;justify-content:space-between;align-items:center;gap:16px}.terminal-head>div{display:grid;gap:4px}.terminal-head small{color:#91a1b8}.terminal-screen{min-height:0;padding:10px}.terminal-screen :deep(.xterm){height:100%}.terminal-screen :deep(.xterm-viewport){border-radius:0 0 12px 12px}@media(max-width:900px){.remote-stats,.remote-grid{grid-template-columns:1fr}.agent-meta{display:grid;gap:5px}.terminal-overlay{padding:10px}.terminal-dialog{width:100%;height:92vh}.terminal-head{align-items:flex-start}}
</style>
