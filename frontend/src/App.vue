<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import Quill from 'quill'
import 'quill/dist/quill.snow.css'

const apiBase = import.meta.env.VITE_API_BASE || '/api'
const isAdminPath = computed(() => window.location.pathname.startsWith('/admin'))
const token = ref(localStorage.getItem('support_token') || '')
const role = ref(localStorage.getItem('support_role') || '')
const username = ref(localStorage.getItem('support_username') || '')
const profile = ref(JSON.parse(localStorage.getItem('support_profile') || '{}'))
const loginForm = ref({ username: '', password: '' })
const loginError = ref('')
const loggedIn = computed(() => !!token.value)
const canViewAdmin = computed(() => role.value === 'admin')

const customerTab = ref('knowledge')
const keyword = ref('')
const faqs = ref([])
const loading = ref(false)
const selected = ref(null)
const showTicket = ref(false)
const submittedTicketId = ref(null)
const myTickets = ref([])
const form = ref({ customerName: profile.value.companyName || '', mineName: profile.value.mineName || '', category: '', description: '', screenshotUrl: '' })
const ticketUploading = ref(false)

const adminTab = ref('tickets')
const tickets = ref([])
const adminFaqs = ref([])
const users = ref([])
const faqUploading = ref(false)
const importingUsers = ref(false)
const importResult = ref(null)
const faqForm = ref({ id: null, category: '', question: '', answer: '', keywords: '', enabled: true, images: [] })
const resolution = ref({ ticketId: null, reason: '', result: '' })
const editorEl = ref(null)
let quill = null

function authHeaders(extra = {}) {
  return token.value ? { ...extra, Authorization: `Bearer ${token.value}` } : extra
}

async function request(url, options = {}) {
  options.headers = authHeaders(options.headers || {})
  const res = await fetch(url, options)
  if (res.status === 401 || res.status === 403) {
    if (res.status === 401) clearSession()
    throw new Error(res.status === 403 ? '无权限访问' : '登录已失效')
  }
  if (!res.ok) throw new Error(await res.text())
  return res.status === 204 ? null : res.json()
}

async function login() {
  loginError.value = ''
  try {
    const res = await fetch(`${apiBase}/auth/login`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(loginForm.value)
    })
    if (!res.ok) throw new Error('用户名或密码错误')
    const data = await res.json()
    token.value = data.token
    role.value = data.role
    username.value = data.username
    profile.value = data
    localStorage.setItem('support_token', data.token)
    localStorage.setItem('support_role', data.role)
    localStorage.setItem('support_username', data.username)
    localStorage.setItem('support_profile', JSON.stringify(data))
    window.location.href = data.role === 'admin' ? '/admin' : '/'
  } catch (e) {
    loginError.value = e.message || '登录失败'
  }
}

function clearSession() {
  token.value = ''; role.value = ''; username.value = ''; profile.value = {}
  localStorage.removeItem('support_token'); localStorage.removeItem('support_role'); localStorage.removeItem('support_username'); localStorage.removeItem('support_profile')
}

async function logout() {
  try { await request(`${apiBase}/auth/logout`, { method: 'POST' }) } catch {}
  clearSession(); window.location.href = '/'
}

async function uploadImage(file) {
  const data = new FormData(); data.append('file', file)
  return request(`${apiBase}/upload/image`, { method: 'POST', body: data })
}

async function loadFaq() {
  loading.value = true
  try {
    const url = keyword.value.trim() ? `${apiBase}/faq/search?q=${encodeURIComponent(keyword.value.trim())}` : `${apiBase}/faq`
    faqs.value = await request(url)
  } finally { loading.value = false }
}

function openFaq(item) { selected.value = item; showTicket.value = false; submittedTicketId.value = null }
function unresolved() {
  form.value.category = selected.value?.category || ''
  form.value.description = selected.value ? `参考问题：${selected.value.question}\n实际问题：` : ''
  showTicket.value = true
  setTimeout(() => document.getElementById('ticket-form')?.scrollIntoView({ behavior: 'smooth' }), 50)
}

async function chooseTicketImage(event) {
  const file = event.target.files?.[0]; if (!file) return
  ticketUploading.value = true
  try { form.value.screenshotUrl = (await uploadImage(file)).url } catch (e) { alert(`上传失败：${e.message}`) }
  finally { ticketUploading.value = false; event.target.value = '' }
}

async function submitTicket() {
  try {
    const data = await request(`${apiBase}/ticket`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(form.value) })
    submittedTicketId.value = data.ticketId
    await loadMyTickets()
  } catch (e) { alert(`提交失败：${e.message}`) }
}

async function loadMyTickets() { myTickets.value = await request(`${apiBase}/tickets/mine`) }
async function loadTickets() { tickets.value = await request(`${apiBase}/admin/tickets`) }
async function loadAdminFaqs() { adminFaqs.value = await request(`${apiBase}/admin/faqs`) }
async function loadUsers() { users.value = await request(`${apiBase}/admin/users`) }

async function setTicketProcessing(ticket) {
  await request(`${apiBase}/admin/tickets/${ticket.id}/status`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ status: 'processing' }) })
  await loadTickets()
}

function startResolve(ticket) {
  resolution.value = { ticketId: ticket.id, reason: ticket.resolution_reason || '', result: ticket.resolution_result || '' }
  setTimeout(() => document.getElementById('resolution-box')?.scrollIntoView({ behavior: 'smooth' }), 50)
}

async function saveResolution() {
  if (!resolution.value.reason.trim() || !resolution.value.result.trim()) return alert('请填写具体原因和处理回执')
  await request(`${apiBase}/admin/tickets/${resolution.value.ticketId}/status`, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status: 'resolved', resolutionReason: resolution.value.reason, resolutionResult: resolution.value.result })
  })
  resolution.value = { ticketId: null, reason: '', result: '' }
  await loadTickets()
}

function initEditor() {
  nextTick(() => {
    if (!editorEl.value) return
    if (!quill) {
      quill = new Quill(editorEl.value, {
        theme: 'snow', placeholder: '写清楚现象、原因、排查步骤、注意事项……',
        modules: { toolbar: [[{ header: [1, 2, 3, false] }], ['bold', 'italic', 'underline', 'strike'], [{ color: [] }, { background: [] }], [{ list: 'ordered' }, { list: 'bullet' }], [{ indent: '-1' }, { indent: '+1' }], [{ align: [] }], ['blockquote', 'code-block'], ['link'], ['clean']] }
      })
      quill.on('text-change', () => { faqForm.value.answer = quill.root.innerHTML })
    }
    quill.root.innerHTML = faqForm.value.answer || ''
  })
}

function editFaq(item) {
  faqForm.value = { id: item.id, category: item.category, question: item.question, answer: item.answer, keywords: item.keywords || '', enabled: item.enabled === 1 || item.enabled === true, images: [...(item.images || [])] }
  initEditor()
}
function resetFaqForm() {
  faqForm.value = { id: null, category: '', question: '', answer: '', keywords: '', enabled: true, images: [] }
  if (quill) quill.setContents([])
}
async function chooseFaqImages(event) {
  const files = Array.from(event.target.files || []); if (!files.length) return
  faqUploading.value = true
  try { for (const file of files) faqForm.value.images.push((await uploadImage(file)).url) }
  catch (e) { alert(`图片上传失败：${e.message}`) }
  finally { faqUploading.value = false; event.target.value = '' }
}
function removeFaqImage(index) { faqForm.value.images.splice(index, 1) }
async function saveFaq() {
  faqForm.value.answer = quill?.root.innerHTML || faqForm.value.answer
  try {
    const editing = !!faqForm.value.id
    await request(editing ? `${apiBase}/admin/faqs/${faqForm.value.id}` : `${apiBase}/admin/faqs`, {
      method: editing ? 'PUT' : 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(faqForm.value)
    })
    resetFaqForm(); await loadAdminFaqs()
  } catch (e) { alert(`保存失败：${e.message}`) }
}
async function deleteFaq(item) {
  if (!confirm(`确定删除“${item.question}”吗？`)) return
  await request(`${apiBase}/admin/faqs/${item.id}`, { method: 'DELETE' }); await loadAdminFaqs()
}

async function downloadUserTemplate() {
  const res = await fetch(`${apiBase}/admin/users/template`, { headers: authHeaders() })
  if (!res.ok) return alert('模板下载失败')
  const blob = await res.blob(); const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = '用户导入模板.xlsx'; a.click(); URL.revokeObjectURL(a.href)
}
async function importUsers(event) {
  const file = event.target.files?.[0]; if (!file) return
  importingUsers.value = true; importResult.value = null
  try {
    const fd = new FormData(); fd.append('file', file)
    importResult.value = await request(`${apiBase}/admin/users/import`, { method: 'POST', body: fd })
    await loadUsers()
  } catch (e) { alert(`导入失败：${e.message}`) }
  finally { importingUsers.value = false; event.target.value = '' }
}
async function toggleUser(user) {
  const enabled = !(user.enabled === 1 || user.enabled === true)
  await request(`${apiBase}/admin/users/${user.id}/enabled`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ enabled }) })
  await loadUsers()
}

function statusText(status) { return { pending: '待处理', processing: '处理中', resolved: '已解决' }[status] || status }
function formatTime(value) { return value ? String(value).replace('T', ' ').slice(0, 19) : '-' }

watch(adminTab, (value) => { if (value === 'faqs') initEditor() })
watch(customerTab, (value) => { if (value === 'tickets') loadMyTickets() })

onMounted(async () => {
  if (!loggedIn.value) return
  if (isAdminPath.value) {
    if (!canViewAdmin.value) return
    await Promise.all([loadTickets(), loadAdminFaqs(), loadUsers()])
  } else {
    await Promise.all([loadFaq(), loadMyTickets()])
  }
})
</script>

<template>
  <main v-if="!loggedIn" class="login-page">
    <section class="login-card">
      <span class="badge dark">MYROBOOT SUPPORT</span><h1>技术支持服务平台</h1>
      <p>登录后先从问题库自助排查，确实无法解决时再提交工单。</p>
      <label>用户名<input v-model="loginForm.username" @keyup.enter="login" placeholder="请输入用户名" /></label>
      <label>密码<input v-model="loginForm.password" type="password" @keyup.enter="login" placeholder="请输入密码" /></label>
      <div v-if="loginError" class="login-error">{{ loginError }}</div>
      <button class="primary login-button" @click="login">登录</button>
    </section>
  </main>

  <main v-else-if="isAdminPath" class="page admin-page">
    <section v-if="!canViewAdmin" class="panel forbidden"><h2>无管理员权限</h2><a class="primary link-button" href="/">返回客户页面</a></section>
    <template v-else>
      <section class="admin-header">
        <div><span class="badge">MYROBOOT ADMIN</span><h1>技术支持管理后台</h1><p>管理用户、问题知识库和客户工单闭环。</p></div>
        <div class="header-actions"><a class="back-link" href="/">客户页面</a><button class="secondary" @click="logout">退出</button></div>
      </section>
      <div class="admin-tabs">
        <button :class="{ active: adminTab === 'tickets' }" @click="adminTab='tickets'">工单管理</button>
        <button :class="{ active: adminTab === 'faqs' }" @click="adminTab='faqs'">问题库管理</button>
        <button :class="{ active: adminTab === 'users' }" @click="adminTab='users'">用户管理</button>
      </div>

      <template v-if="adminTab === 'tickets'">
        <section class="panel admin-panel">
          <div class="panel-title"><h2>工单列表</h2><span>{{ tickets.length }} 条</span></div>
          <div class="table-wrap"><table>
            <thead><tr><th>编号</th><th>客户</th><th>类型</th><th>问题</th><th>截图</th><th>状态</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="ticket in tickets" :key="ticket.id">
                <td>#{{ ticket.id }}<br><small>{{ formatTime(ticket.create_time) }}</small></td>
                <td>{{ ticket.customer_name || '-' }}<br><small>{{ ticket.mine_name || '-' }}</small></td>
                <td>{{ ticket.category || '-' }}</td><td class="desc-cell">{{ ticket.description }}</td>
                <td><a v-if="ticket.screenshot_url" :href="ticket.screenshot_url" target="_blank"><img class="thumb" :src="ticket.screenshot_url" /></a><span v-else>-</span></td>
                <td><span class="status-pill" :class="ticket.status">{{ statusText(ticket.status) }}</span></td>
                <td class="row-actions"><button v-if="ticket.status==='pending'" class="secondary" @click="setTicketProcessing(ticket)">开始处理</button><button class="primary small-btn" @click="startResolve(ticket)">{{ ticket.status==='resolved' ? '查看/修改回执' : '填写解决回执' }}</button></td>
              </tr>
            </tbody>
          </table></div>
        </section>
        <section v-if="resolution.ticketId" id="resolution-box" class="panel resolution-panel">
          <div class="panel-title"><h2>工单 #{{ resolution.ticketId }} 解决回执</h2><button class="secondary" @click="resolution={ticketId:null,reason:'',result:''}">关闭</button></div>
          <label>问题具体原因<textarea v-model="resolution.reason" rows="4" placeholder="例如：矿端前置机到中心网络存在丢包，导致长连接中断"></textarea></label>
          <label>处理结果 / 给客户的回执<textarea v-model="resolution.result" rows="6" placeholder="写清楚做了什么、当前结果、客户还需要配合什么"></textarea></label>
          <button class="primary" @click="saveResolution">保存并标记已解决</button>
        </section>
      </template>

      <section v-else-if="adminTab === 'faqs'" class="admin-grid">
        <div class="panel admin-panel">
          <div class="panel-title"><h2>问题库</h2><span>{{ adminFaqs.length }} 条</span></div>
          <div class="faq-admin-list"><div v-for="item in adminFaqs" :key="item.id" class="faq-admin-item"><div><span class="category">{{ item.category }}</span><strong>{{ item.question }}</strong><small>{{ item.enabled ? '已启用' : '已停用' }} · {{ item.images?.length || 0 }} 张图</small></div><div class="row-actions"><button class="secondary" @click="editFaq(item)">编辑</button><button class="danger" @click="deleteFaq(item)">删除</button></div></div></div>
        </div>
        <div class="panel admin-panel faq-editor">
          <h2>{{ faqForm.id ? '编辑问题' : '录入新问题' }}</h2>
          <label>分类<input v-model="faqForm.category" placeholder="视频 / APP / 数据上传 / 网络" /></label>
          <label>问题标题<input v-model="faqForm.question" placeholder="客户会怎么描述这个问题？" /></label>
          <label>解决方案</label><div ref="editorEl" class="rich-editor"></div>
          <label>搜索关键词<input v-model="faqForm.keywords" placeholder="多个关键词可用空格分隔" /></label>
          <label>附加图片<input type="file" accept="image/*" multiple @change="chooseFaqImages" /><small>{{ faqUploading ? '上传中...' : '可一次上传多张现场截图' }}</small></label>
          <div v-if="faqForm.images.length" class="image-grid"><div v-for="(image,index) in faqForm.images" :key="image" class="image-item"><img :src="image" /><button @click="removeFaqImage(index)">×</button></div></div>
          <label class="check-row"><input v-model="faqForm.enabled" type="checkbox" /> 启用并展示给客户</label>
          <div class="actions"><button class="primary" @click="saveFaq">保存到问题库</button><button v-if="faqForm.id" class="secondary" @click="resetFaqForm">取消编辑</button></div>
        </div>
      </section>

      <section v-else class="panel admin-panel">
        <div class="panel-title"><div><h2>用户管理</h2><p class="muted">通过 Excel 批量维护客户账号、单位和矿井信息。</p></div><span>{{ users.length }} 个账号</span></div>
        <div class="import-bar"><button class="secondary" @click="downloadUserTemplate">下载 Excel 模板</button><label class="file-button">{{ importingUsers ? '导入中...' : '选择 Excel 导入' }}<input type="file" accept=".xlsx,.xls" @change="importUsers" /></label></div>
        <div v-if="importResult" class="import-result">新增 {{ importResult.created }} 人，更新 {{ importResult.updated }} 人。<div v-for="err in importResult.errors" :key="err" class="login-error">{{ err }}</div></div>
        <div class="table-wrap"><table><thead><tr><th>账号</th><th>姓名</th><th>单位</th><th>矿井</th><th>手机号</th><th>角色</th><th>状态</th><th>操作</th></tr></thead><tbody>
          <tr v-for="user in users" :key="user.id"><td>{{ user.username }}</td><td>{{ user.display_name || '-' }}</td><td>{{ user.company_name || '-' }}</td><td>{{ user.mine_name || '-' }}</td><td>{{ user.phone || '-' }}</td><td>{{ user.role }}</td><td>{{ user.enabled ? '启用' : '停用' }}</td><td><button class="secondary small-btn" @click="toggleUser(user)">{{ user.enabled ? '停用' : '启用' }}</button></td></tr>
        </tbody></table></div>
      </section>
    </template>
  </main>

  <main v-else class="page">
    <section class="hero"><div><span class="badge">MYROBOOT SUPPORT</span><h1>客户自助技术支持</h1><p>先自己查问题库，标准方案无法解决时再提交工单。</p></div><div class="header-actions customer-actions"><span>{{ profile.displayName || username }}<small>{{ profile.mineName || profile.companyName }}</small></span><button class="secondary" @click="logout">退出</button></div></section>
    <section class="flow-panel"><div class="flow-step"><span>1</span><div><strong>搜索问题库</strong><small>输入现象或报错关键词</small></div></div><div class="flow-arrow">→</div><div class="flow-step"><span>2</span><div><strong>按方案自助排查</strong><small>查看标准步骤和图片</small></div></div><div class="flow-arrow">→</div><div class="flow-step"><span>3</span><div><strong>仍未解决再提工单</strong><small>处理结果会回执给你</small></div></div></section>
    <div class="customer-tabs"><button :class="{active:customerTab==='knowledge'}" @click="customerTab='knowledge'">问题库 / 提交问题</button><button :class="{active:customerTab==='tickets'}" @click="customerTab='tickets'">我的工单 <span v-if="myTickets.length">{{ myTickets.length }}</span></button></div>

    <template v-if="customerTab==='knowledge'">
      <section class="search-section panel"><div><h2>第一步：先从问题库查找</h2><p>搜索报错文字、功能名称或问题现象。</p></div><div class="search-box light"><input v-model="keyword" @keyup.enter="loadFaq" placeholder="例如：视频黑屏、APP 登录不上、数据未上报" /><button @click="loadFaq">搜索</button></div></section>
      <section class="content"><div class="panel list-panel"><div class="panel-title"><h2>匹配问题</h2><span>{{ loading ? '搜索中...' : `${faqs.length} 条` }}</span></div><button v-for="item in faqs" :key="item.id" class="faq-item" @click="openFaq(item)"><span class="category">{{ item.category }}</span><strong>{{ item.question }}</strong><span class="arrow">›</span></button><div v-if="!loading&&!faqs.length" class="empty">没有找到对应问题，可以提交工单。</div></div>
        <div class="panel detail-panel"><template v-if="selected"><span class="category">{{ selected.category }}</span><h2>{{ selected.question }}</h2><div class="answer rich-content" v-html="selected.answer"></div><div v-if="selected.images?.length" class="knowledge-images"><a v-for="image in selected.images" :key="image" :href="image" target="_blank"><img :src="image" /></a></div><div class="resolved-box"><strong>按上面的方案操作后，问题解决了吗？</strong><div class="actions"><button class="success" @click="selected=null">已经解决</button><button class="secondary" @click="unresolved">没有解决，提交工单</button></div></div></template><template v-else><div class="placeholder"><h2>先选择一个问题</h2><p>这里会显示完整解决方案、步骤和图片。</p><button class="secondary" @click="showTicket=true">确实找不到，提交问题</button></div></template></div></section>
      <section v-if="showTicket" id="ticket-form" class="panel ticket-panel"><div v-if="submittedTicketId" class="submitted"><h2>问题已提交</h2><p>工单编号：#{{ submittedTicketId }}</p><button class="primary" @click="customerTab='tickets'">查看我的工单</button></div><template v-else><h2>第二步：提交完整故障信息</h2><div class="form-grid"><label>客户名称<input v-model="form.customerName" /></label><label>矿井名称<input v-model="form.mineName" /></label><label>问题类型<input v-model="form.category" placeholder="APP / 视频 / 数据 / 网络" /></label><label>故障截图<input type="file" accept="image/*" @change="chooseTicketImage" /><small>{{ ticketUploading ? '上传中...' : '建议上传报错截图' }}</small></label></div><div v-if="form.screenshotUrl" class="ticket-preview"><img :src="form.screenshotUrl" /></div><label class="full">问题描述<textarea v-model="form.description" rows="7" placeholder="发生时间、报错信息、影响范围、已经尝试过什么"></textarea></label><button class="primary" @click="submitTicket">提交工单</button></template></section>
    </template>

    <section v-else class="ticket-history"><div class="panel-title"><h2>我的工单</h2><span>{{ myTickets.length }} 条</span></div><div v-if="!myTickets.length" class="panel empty">暂时没有提交过工单。</div><article v-for="ticket in myTickets" :key="ticket.id" class="panel ticket-card"><div class="ticket-card-head"><div><strong>#{{ ticket.id }} {{ ticket.category || '技术问题' }}</strong><small>{{ formatTime(ticket.create_time) }}</small></div><span class="status-pill" :class="ticket.status">{{ statusText(ticket.status) }}</span></div><p class="ticket-description">{{ ticket.description }}</p><a v-if="ticket.screenshot_url" :href="ticket.screenshot_url" target="_blank"><img class="ticket-shot" :src="ticket.screenshot_url" /></a><div v-if="ticket.status==='resolved'" class="receipt"><h3>处理回执</h3><dl><dt>问题具体原因</dt><dd>{{ ticket.resolution_reason || '-' }}</dd><dt>处理结果</dt><dd>{{ ticket.resolution_result || '-' }}</dd><dt>解决时间</dt><dd>{{ formatTime(ticket.resolved_time) }}</dd></dl></div><div v-else class="waiting-receipt">技术人员正在处理，解决后这里会显示具体原因和处理回执。</div></article></section>
  </main>
</template>
