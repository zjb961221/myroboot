<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import Quill from 'quill'
import 'quill/dist/quill.snow.css'
import { apiErrorMessage, showToast } from './uiFeedback'

const apiBase = import.meta.env.VITE_API_BASE || '/api'
const token = localStorage.getItem('support_token') || ''
const role = localStorage.getItem('support_role') || ''
const canViewAdmin = computed(() => role === 'admin')
const adminTab = ref('tickets')
const tickets = ref([])
const adminFaqs = ref([])
const users = ref([])
const importingUsers = ref(false)
const importResult = ref(null)
const faqUploading = ref(false)
const faqForm = ref({ id: null, category: '', question: '', answer: '', keywords: '', enabled: true, images: [], attachments: [] })
const editorEl = ref(null)
let quill = null
const logs = ref([])
const logFile = ref('')
const logLoading = ref(false)
const autoRefresh = ref(false)
let logTimer = null

function authHeaders(extra = {}) { return { ...extra, Authorization: `Bearer ${token}` } }
async function request(url, options = {}) {
  options.headers = authHeaders(options.headers || {})
  const res = await fetch(url, options)
  if (res.status === 401) { localStorage.removeItem('support_token'); location.href = '/'; throw new Error('登录已失效，请重新登录') }
  if (res.status === 403) throw new Error('无管理员权限')
  if (!res.ok) throw new Error(await apiErrorMessage(res))
  return res.status === 204 ? null : res.json()
}
async function logout() { try { await request(`${apiBase}/auth/logout`, { method: 'POST' }) } catch {} localStorage.clear(); location.href = '/' }
async function loadTickets() { tickets.value = await request(`${apiBase}/admin/tickets`) }
async function loadAdminFaqs() { adminFaqs.value = await request(`${apiBase}/admin/faqs`) }
async function loadUsers() { users.value = await request(`${apiBase}/admin/users`) }

function statusText(v) { return { pending: '待处理', processing: '处理中', resolved: '已解决', cancelled: '已撤销' }[v] || v }
function formatTime(v) { return v ? String(v).replace('T', ' ').slice(0, 19) : '-' }
function fileSize(v) { const n = Number(v || 0); if (n < 1024) return `${n} B`; if (n < 1048576) return `${(n / 1024).toFixed(1)} KB`; return `${(n / 1048576).toFixed(1)} MB` }
function isVideo(f) { return String(f.contentType || f.content_type || '').startsWith('video/') || /\.(mp4|mov|avi|mkv|webm)$/i.test(f.name || f.original_name || '') }
function isImage(f) { return String(f.contentType || f.content_type || '').startsWith('image/') || /\.(png|jpg|jpeg|gif|webp|bmp)$/i.test(f.name || f.original_name || '') }

async function setTicketProcessing(ticket) {
  try {
    await request(`${apiBase}/admin/tickets/${ticket.id}/status`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ status: 'processing' }) })
    await loadTickets(); showToast('工单已进入处理中', 'success')
  } catch (e) { showToast(e.message) }
}
async function deleteTicket(ticket) {
  if (!window.confirm(`确定删除工单 #${ticket.id} 吗？\n删除后将从正常列表隐藏，但系统会保留审计数据和附件，不会物理销毁。`)) return
  try {
    await request(`${apiBase}/admin/tickets/${ticket.id}`, { method: 'DELETE' })
    await loadTickets()
    showToast(`工单 #${ticket.id} 已删除`, 'success')
  } catch (e) { showToast(e.message) }
}

function initEditor() {
  nextTick(() => {
    if (!editorEl.value) return
    if (!quill || quill.container !== editorEl.value) {
      quill = new Quill(editorEl.value, {
        theme: 'snow',
        placeholder: '写清楚现象、原因、处理步骤、注意事项……',
        modules: { toolbar: [[{ header: [1, 2, 3, false] }], ['bold', 'italic', 'underline', 'strike'], [{ color: [] }, { background: [] }], [{ list: 'ordered' }, { list: 'bullet' }], [{ indent: '-1' }, { indent: '+1' }], [{ align: [] }], ['blockquote', 'code-block'], ['link'], ['clean']] }
      })
      quill.on('text-change', () => { faqForm.value.answer = quill.root.innerHTML })
    }
    quill.root.innerHTML = faqForm.value.answer || ''
  })
}
function editFaq(item) {
  faqForm.value = {
    id: item.id, category: item.category, question: item.question, answer: item.answer,
    keywords: item.keywords || '', enabled: item.enabled === 1 || item.enabled === true,
    images: [...(item.images || [])],
    attachments: (item.attachments || []).map(a => ({ url: a.file_url, name: a.original_name, contentType: a.content_type, size: a.file_size }))
  }
  initEditor()
}
function resetFaqForm() {
  faqForm.value = { id: null, category: '', question: '', answer: '', keywords: '', enabled: true, images: [], attachments: [] }
  if (quill) quill.setText('')
}
async function uploadFaqAttachments(event) {
  const files = Array.from(event.target.files || [])
  if (!files.length) return
  if (faqForm.value.attachments.length + files.length > 20) { showToast('一个问题最多上传 20 个附件'); event.target.value = ''; return }
  faqUploading.value = true
  let success = 0
  try {
    for (const file of files) {
      const fd = new FormData(); fd.append('file', file)
      try {
        const data = await request(`${apiBase}/upload/attachment`, { method: 'POST', body: fd })
        faqForm.value.attachments.push(data); success++
      } catch (e) { showToast(`${file.name}：${e.message}`) }
    }
    if (success) showToast(`已上传 ${success} 个附件`, 'success')
  } finally { faqUploading.value = false; event.target.value = '' }
}
function removeFaqAttachment(index) { faqForm.value.attachments.splice(index, 1) }
async function saveFaq() {
  faqForm.value.answer = quill?.root.innerHTML || faqForm.value.answer
  if (!faqForm.value.category.trim()) return showToast('请填写分类')
  if (!faqForm.value.question.trim()) return showToast('请填写问题标题')
  if (!faqForm.value.answer || faqForm.value.answer === '<p><br></p>') return showToast('请填写解决方案')
  try {
    const editing = !!faqForm.value.id
    await request(editing ? `${apiBase}/admin/faqs/${faqForm.value.id}` : `${apiBase}/admin/faqs`, {
      method: editing ? 'PUT' : 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(faqForm.value)
    })
    showToast(editing ? '问题库已更新' : '问题已录入', 'success')
    resetFaqForm(); await loadAdminFaqs()
  } catch (e) { showToast(e.message) }
}
async function deleteFaq(item) {
  if (!confirm(`确定删除“${item.question}”吗？`)) return
  try { await request(`${apiBase}/admin/faqs/${item.id}`, { method: 'DELETE' }); await loadAdminFaqs(); showToast('已删除', 'success') } catch (e) { showToast(e.message) }
}

async function importUsers(event) {
  const file = event.target.files?.[0]; if (!file) return
  importingUsers.value = true; importResult.value = null
  try {
    const fd = new FormData(); fd.append('file', file)
    importResult.value = await request(`${apiBase}/admin/users/import`, { method: 'POST', body: fd })
    await loadUsers()
    showToast(importResult.value.errors?.length ? '导入完成，但有部分行未通过校验' : '用户导入成功', importResult.value.errors?.length ? 'warning' : 'success')
  } catch (e) { showToast(e.message) }
  finally { importingUsers.value = false; event.target.value = '' }
}
async function toggleUser(user) {
  try {
    const enabled = !(user.enabled === 1 || user.enabled === true)
    await request(`${apiBase}/admin/users/${user.id}/enabled`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ enabled }) })
    await loadUsers()
  } catch (e) { showToast(e.message) }
}

async function loadLogs() {
  logLoading.value = true
  try {
    const data = await request(`${apiBase}/admin/logs?lines=300`)
    logs.value = data.lines || []; logFile.value = data.file || ''
    nextTick(() => { const el = document.querySelector('.log-console'); if (el) el.scrollTop = el.scrollHeight })
  } catch (e) { showToast(e.message) }
  finally { logLoading.value = false }
}
async function copyLogs() {
  try { await navigator.clipboard.writeText(logs.value.join('\n')); showToast('日志已复制', 'success') } catch { showToast('复制失败，请手动选择日志内容') }
}
function configureAutoRefresh() {
  if (logTimer) clearInterval(logTimer)
  if (autoRefresh.value) logTimer = setInterval(loadLogs, 5000)
}

watch(adminTab, value => {
  if (value === 'faqs') initEditor()
  if (value === 'logs') loadLogs()
})
watch(autoRefresh, configureAutoRefresh)
onMounted(async () => {
  if (!canViewAdmin.value) return
  try { await Promise.all([loadTickets(), loadAdminFaqs(), loadUsers()]) } catch (e) { showToast(e.message) }
})
</script>

<template>
  <main class="page admin-page">
    <section v-if="!canViewAdmin" class="panel forbidden"><h2>无管理员权限</h2><a class="primary link-button" href="/">返回登录</a></section>
    <template v-else>
      <section class="admin-header">
        <div><span class="badge">MYROBOOT ADMIN</span><h1>技术支持管理后台</h1><p>统一管理工单、问题库、客户账号和系统日志。</p></div>
        <div class="header-actions"><a class="back-link" href="/">客户页面</a><button class="secondary" @click="logout">退出</button></div>
      </section>

      <div class="admin-tabs">
        <button :class="{active:adminTab==='tickets'}" @click="adminTab='tickets'">工单管理</button>
        <button :class="{active:adminTab==='faqs'}" @click="adminTab='faqs'">问题库管理</button>
        <button :class="{active:adminTab==='users'}" @click="adminTab='users'">用户管理</button>
        <button :class="{active:adminTab==='logs'}" @click="adminTab='logs'">后台日志</button>
      </div>

      <section v-if="adminTab==='tickets'" class="panel admin-panel">
        <div class="panel-title"><h2>工单列表</h2><span>{{ tickets.length }} 条</span></div>
        <div class="table-wrap"><table><thead><tr><th>编号</th><th>客户/矿井</th><th>类型</th><th>问题</th><th>附件</th><th>状态</th><th>操作</th></tr></thead><tbody>
          <tr v-for="ticket in tickets" :key="ticket.id">
            <td>#{{ticket.id}}<br><small>{{formatTime(ticket.create_time)}}</small></td>
            <td>{{ticket.customer_name||'-'}}<br><small>{{ticket.mine_name||'-'}}</small></td>
            <td>{{ticket.category||'-'}}</td><td class="desc-cell">{{ticket.description}}</td>
            <td>{{ticket.attachments?.length||0}} 个</td><td><span class="status-pill" :class="ticket.status">{{statusText(ticket.status)}}</span></td>
            <td class="row-actions"><button v-if="ticket.status==='pending'" class="secondary small-btn" @click="setTicketProcessing(ticket)">开始处理</button><a class="primary link-button small-btn" :href="`/admin/ticket-detail?id=${ticket.id}`">{{ticket.status==='cancelled'?'查看详情':'处理 / 回执'}}</a><button class="danger small-btn" @click="deleteTicket(ticket)">删除</button></td>
          </tr>
        </tbody></table></div>
      </section>

      <section v-else-if="adminTab==='faqs'" class="admin-grid">
        <div class="panel admin-panel">
          <div class="panel-title"><div><h2>问题库</h2><p class="muted">客户首页搜索到的标准解决方案。</p></div><span>{{adminFaqs.length}} 条</span></div>
          <div class="faq-admin-list"><div v-for="item in adminFaqs" :key="item.id" class="faq-admin-item"><div><span class="category">{{item.category}}</span><strong>{{item.question}}</strong><small>{{item.enabled?'已启用':'已停用'}} · {{item.attachments?.length||0}} 个附件<span v-if="item.images?.length"> · {{item.images.length}} 个历史图片</span></small></div><div class="row-actions"><button class="secondary" @click="editFaq(item)">编辑</button><button class="danger" @click="deleteFaq(item)">删除</button></div></div></div>
        </div>
        <div class="panel admin-panel faq-editor">
          <h2>{{faqForm.id?'编辑问题':'录入新问题'}}</h2>
          <label>分类 <em class="required">*</em><input v-model="faqForm.category" placeholder="视频 / APP / 数据上传 / 网络" /></label>
          <label>问题标题 <em class="required">*</em><input v-model="faqForm.question" placeholder="客户会怎么描述这个问题？" /></label>
          <label>解决方案 <em class="required">*</em></label><div ref="editorEl" class="rich-editor"></div>
          <label>搜索关键词<input v-model="faqForm.keywords" placeholder="多个关键词可用空格分隔" /></label>

          <div class="kb-attachment-box">
            <div class="kb-attachment-head"><div><strong>附件</strong><small>图片、Excel、Word、PPT、PDF、日志、压缩包、视频统一从这里上传。普通文件≤30MB，视频≤200MB，最多20个。</small></div><label class="file-button">{{faqUploading?'上传中...':'选择附件'}}<input type="file" multiple accept=".png,.jpg,.jpeg,.gif,.webp,.bmp,.pdf,.txt,.log,.csv,.json,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.zip,.rar,.7z,.mp4,.mov,.avi,.mkv,.webm" @change="uploadFaqAttachments" /></label></div>
            <div v-if="faqForm.attachments.length" class="kb-files">
              <div v-for="(file,index) in faqForm.attachments" :key="file.url" class="kb-file">
                <img v-if="isImage(file)" :src="file.url" />
                <video v-else-if="isVideo(file)" :src="file.url" controls preload="metadata"></video>
                <div><strong>{{file.name}}</strong><small>{{fileSize(file.size)}}</small></div>
                <button class="danger small-btn" @click="removeFaqAttachment(index)">移除</button>
              </div>
            </div>
            <div v-if="faqForm.images.length" class="legacy-note">已有历史图片会继续保留并在客户首页展示；以后新增文件请统一使用“附件”。</div>
          </div>

          <label class="check-row"><input v-model="faqForm.enabled" type="checkbox" /> 启用并展示给客户</label>
          <div class="actions"><button class="primary" :disabled="faqUploading" @click="saveFaq">保存到问题库</button><button v-if="faqForm.id" class="secondary" @click="resetFaqForm">取消编辑</button></div>
        </div>
      </section>

      <section v-else-if="adminTab==='users'" class="panel admin-panel">
        <div class="panel-title"><div><h2>用户管理</h2><p class="muted">批量导入或单个维护客户账号。</p></div><span>{{users.length}} 个账号</span></div>
        <div class="import-bar"><a class="secondary link-button" href="/api/templates/mine-users.xlsx" download="煤矿用户导入模板.xlsx">下载 Excel 模板</a><label class="file-button">{{importingUsers?'导入中...':'选择 Excel 导入'}}<input type="file" accept=".xlsx,.xls" @change="importUsers" /></label><a class="primary link-button" href="/admin/users/manage">单个新增/编辑</a></div>
        <div v-if="importResult" class="import-result">新增 {{importResult.created}} 人，更新 {{importResult.updated}} 人。<div v-for="err in importResult.errors" :key="err" class="login-error">{{err}}</div></div>
        <div class="table-wrap"><table><thead><tr><th>账号</th><th>姓名</th><th>煤矿</th><th>手机号</th><th>邮箱</th><th>角色</th><th>状态</th><th>操作</th></tr></thead><tbody>
          <tr v-for="user in users" :key="user.id"><td>{{user.username}}</td><td>{{user.display_name||'-'}}</td><td>{{user.mine_name||'-'}}</td><td>{{user.phone||'-'}}</td><td>{{user.email||'-'}}</td><td>{{user.role}}</td><td>{{user.enabled?'启用':'停用'}}</td><td><button class="secondary small-btn" @click="toggleUser(user)">{{user.enabled?'停用':'启用'}}</button></td></tr>
        </tbody></table></div>
      </section>

      <section v-else class="panel admin-panel log-panel">
        <div class="panel-title"><div><h2>后台日志</h2><p class="muted">直接查看 Spring Boot 最近日志，定位邮箱、上传、数据库和接口异常。敏感字段会做基础脱敏。</p></div><span>{{logs.length}} 行</span></div>
        <div class="log-toolbar"><button class="primary" :disabled="logLoading" @click="loadLogs">{{logLoading?'刷新中...':'刷新日志'}}</button><button class="secondary" @click="copyLogs">复制日志</button><label class="auto-check"><input v-model="autoRefresh" type="checkbox" /> 每5秒自动刷新</label><small>{{logFile}}</small></div>
        <pre class="log-console">{{logs.join('\n')}}</pre>
      </section>
    </template>
  </main>
</template>

<style scoped>
.required{color:#d92d20;font-style:normal}.kb-attachment-box{border:1px dashed #cad6e6;border-radius:14px;padding:16px;background:#fafcff}.kb-attachment-head{display:flex;justify-content:space-between;gap:18px;align-items:center}.kb-attachment-head>div{display:grid;gap:5px}.kb-attachment-head small{color:#7d8da4;line-height:1.5}.kb-files{display:grid;gap:9px;margin-top:14px}.kb-file{display:grid;grid-template-columns:96px 1fr auto;gap:12px;align-items:center;padding:10px;border:1px solid #e1e7ef;background:#fff;border-radius:10px}.kb-file img,.kb-file video{width:96px;height:72px;object-fit:cover;border-radius:8px;background:#111}.kb-file>div{display:grid;gap:4px;min-width:0}.kb-file strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.kb-file small,.legacy-note{color:#8190a3;font-size:13px}.legacy-note{margin-top:12px;padding:10px;border-radius:8px;background:#f4f6f9}.log-panel{overflow:visible}.log-toolbar{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-bottom:14px}.log-toolbar small{margin-left:auto;color:#7d8da4}.auto-check{display:flex;align-items:center;gap:7px;font-weight:500}.auto-check input{width:auto}.log-console{margin:0;background:#0d1726;color:#d7e2ef;border-radius:13px;padding:18px;min-height:520px;max-height:68vh;overflow:auto;font:12px/1.65 ui-monospace,SFMono-Regular,Consolas,monospace;white-space:pre-wrap;word-break:break-all}.link-button{display:inline-block}.faq-editor .file-button{width:max-content}@media(max-width:800px){.kb-attachment-head{display:grid}.kb-file{grid-template-columns:72px 1fr}.kb-file img,.kb-file video{width:72px;height:58px}.kb-file button{grid-column:2}.log-toolbar small{width:100%;margin-left:0}}
</style>