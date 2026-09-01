<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import Quill from 'quill'
import 'quill/dist/quill.snow.css'
import { apiErrorMessage, showToast } from './uiFeedback'
import { copyText } from './clipboard'

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
const faqShareTarget = ref(null)
const faqShares = ref([])
const faqShareHours = ref(24)
const faqShareUrl = ref('')
const faqSharing = ref(false)
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
function activeShare(item) { if (Number(item.revoked) === 1 || item.revoked === true) return false; const d = new Date(String(item.expires_time || '').replace(' ', 'T')); return !Number.isNaN(d.getTime()) && d.getTime() > Date.now() }

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
async function openFaqShare(item) {
  faqShareTarget.value = item
  faqShareUrl.value = ''
  faqShareHours.value = 24
  try { faqShares.value = await request(`${apiBase}/faqs/${item.id}/shares`) } catch (e) { faqShares.value = []; showToast(e.message) }
}
function closeFaqShare() { faqShareTarget.value = null; faqShares.value = []; faqShareUrl.value = '' }
async function createFaqShare() {
  if (!faqShareTarget.value) return
  faqSharing.value = true
  faqShareUrl.value = ''
  try {
    const data = await request(`${apiBase}/faqs/${faqShareTarget.value.id}/shares`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ hours: Number(faqShareHours.value) }) })
    faqShareUrl.value = `${location.origin}/share/faq#${data.token}`
    faqShares.value = await request(`${apiBase}/faqs/${faqShareTarget.value.id}/shares`)
    try { await copyText(faqShareUrl.value); showToast('问题库分享链接已生成并复制', 'success') }
    catch { showToast('分享链接已生成，请手动复制', 'success') }
  } catch (e) { showToast(e.message) } finally { faqSharing.value = false }
}
async function copyFaqShare() {
  if (!faqShareUrl.value) return
  try { await copyText(faqShareUrl.value); showToast('分享链接已复制', 'success') }
  catch { showToast('复制失败，请长按或选中链接手动复制') }
}
async function revokeFaqShare(item) {
  if (!faqShareTarget.value || !confirm('撤销后，已经发出去的这个问题库分享链接会立即失效。确定撤销吗？')) return
  try {
    await request(`${apiBase}/faqs/${faqShareTarget.value.id}/shares/${item.id}`, { method: 'DELETE' })
    faqShares.value = await request(`${apiBase}/faqs/${faqShareTarget.value.id}/shares`)
    showToast('分享链接已撤销', 'success')
  } catch (e) { showToast(e.message) }
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
  try { await copyText(logs.value.join('\n')); showToast('日志已复制', 'success') } catch { showToast('复制失败，请手动选择日志内容') }
}
function configureAutoRefresh() {
  if (logTimer) clearInterval(logTimer)
  if (autoRefresh.value) logTimer = setInterval(loadLogs, 5000)
}

watch(adminTab, value => {
  if (value === 'faqs') initEditor()
  if (value === 'logs') loadLogs()
  if (value !== 'faqs') closeFaqShare()
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

      <section v-if="adminTab==='tickets'" class="panel admin-panel ticket-list-panel">
        <div class="panel-title ticket-list-title"><div><h2>工单列表</h2><p class="muted">快速确认谁提出、哪个矿井、由谁负责以及当前处理状态。</p></div><span>{{ tickets.length }} 条</span></div>
        <div class="table-wrap ticket-table-wrap"><table class="ticket-table"><thead><tr><th>工单</th><th>提出人</th><th>矿井</th><th>处理人</th><th>类型 / 状态</th><th>问题摘要</th><th>附件</th><th>操作</th></tr></thead><tbody>
          <tr v-for="ticket in tickets" :key="ticket.id">
            <td class="ticket-id-cell"><strong>#{{ticket.id}}</strong><small>{{formatTime(ticket.create_time)}}</small></td>
            <td><div class="person-stack"><strong>{{ticket.submitter_name||ticket.display_name||ticket.username||'-'}}</strong><small v-if="ticket.customer_name">{{ticket.customer_name}}</small></div></td>
            <td class="source-cell"><strong>{{ticket.mine_name||'-'}}</strong></td>
            <td><div class="person-stack"><strong>{{ticket.assigned_name||'暂未分配'}}</strong><small v-if="ticket.assigned_time">分配于 {{formatTime(ticket.assigned_time)}}</small></div></td>
            <td><div class="type-status"><span>{{ticket.category||'-'}}</span><span class="status-pill" :class="ticket.status">{{statusText(ticket.status)}}</span></div></td>
            <td class="desc-cell ticket-desc-cell" :title="ticket.description">{{ticket.description}}</td>
            <td class="attachment-count">{{ticket.attachments?.length||0}} 个</td>
            <td class="row-actions ticket-actions"><button v-if="ticket.status==='pending'" class="secondary small-btn" @click="setTicketProcessing(ticket)">开始处理</button><a class="primary link-button small-btn" :href="`/admin/ticket-detail?id=${ticket.id}`">{{ticket.status==='cancelled'?'查看详情':'处理 / 回执'}}</a><button class="danger small-btn" @click="deleteTicket(ticket)">删除</button></td>
          </tr>
        </tbody></table></div>
      </section>

      <section v-else-if="adminTab==='faqs'" class="admin-grid">
        <div class="panel admin-panel">
          <div class="panel-title"><div><h2>问题库</h2><p class="muted">客户首页搜索到的标准解决方案。</p></div><span>{{adminFaqs.length}} 条</span></div>
          <div class="faq-admin-list"><div v-for="item in adminFaqs" :key="item.id" class="faq-admin-item"><div class="faq-item-content"><span class="category">{{item.category}}</span><strong :title="item.question">{{item.question}}</strong><small>{{item.enabled?'已启用':'已停用'}} · {{item.attachments?.length||0}} 个附件<span v-if="item.images?.length"> · {{item.images.length}} 个历史图片</span></small></div><div class="row-actions faq-row-actions"><button class="secondary" @click="openFaqShare(item)">分享</button><button class="secondary" @click="editFaq(item)">编辑</button><button class="danger" @click="deleteFaq(item)">删除</button></div></div></div>

          <div v-if="faqShareTarget" class="faq-share-box">
            <div class="faq-share-head"><div><span class="category">{{faqShareTarget.category}}</span><h3>{{faqShareTarget.question}}</h3><p>生成临时外链，外部人员无需登录即可查看该问题的解决方案和附件。链接可随时撤销。</p></div><button class="secondary small-btn" @click="closeFaqShare">关闭</button></div>
            <div class="faq-share-create"><select v-model="faqShareHours"><option :value="1">1 小时</option><option :value="24">24 小时</option><option :value="72">3 天</option><option :value="168">7 天</option><option :value="720">30 天</option></select><button class="primary" :disabled="faqSharing" @click="createFaqShare">{{faqSharing?'生成中…':'生成分享链接'}}</button></div>
            <div v-if="faqShareUrl" class="faq-share-url"><b>新分享链接（只显示这一次）</b><div><input :value="faqShareUrl" readonly @focus="$event.target.select()"/><button class="secondary" @click="copyFaqShare">复制</button></div><small>服务器仅保存令牌哈希，不保存分享令牌明文。HTTP 环境也会自动使用兼容复制方案。</small></div>
            <div v-if="faqShares.length" class="faq-share-list"><div v-for="share in faqShares" :key="share.id"><div><b>{{activeShare(share)?'有效':'已失效'}}</b><small>创建 {{formatTime(share.create_time)}} · 到期 {{formatTime(share.expires_time)}} · 已访问 {{share.access_count||0}} 次</small></div><button v-if="activeShare(share)" class="danger small-btn" @click="revokeFaqShare(share)">撤销</button></div></div>
            <div v-else class="muted faq-share-empty">暂未创建分享链接</div>
          </div>
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

      <section v-else class="panel admin-panel">
        <div class="panel-title"><div><h2>后台日志</h2><p class="muted">查看后端最近日志，排查接口异常、邮件发送和工单处理问题。</p></div><div class="row-actions"><label class="check-row compact"><input v-model="autoRefresh" type="checkbox"/> 5 秒自动刷新</label><button class="secondary small-btn" :disabled="logLoading" @click="loadLogs">{{logLoading?'刷新中...':'刷新'}}</button><button class="secondary small-btn" @click="copyLogs">复制日志</button></div></div>
        <div class="log-meta">{{logFile||'日志文件'}} · 最近 {{logs.length}} 行</div>
        <pre class="log-console">{{logs.join('\n')}}</pre>
      </section>
    </template>
  </main>
</template>
