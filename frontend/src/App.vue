<script setup>
import { computed, onMounted, ref } from 'vue'

const apiBase = import.meta.env.VITE_API_BASE || '/api'
const isAdminPath = computed(() => window.location.pathname.startsWith('/admin'))
const token = ref(localStorage.getItem('support_token') || '')
const role = ref(localStorage.getItem('support_role') || '')
const username = ref(localStorage.getItem('support_username') || '')
const loginForm = ref({ username: '', password: '' })
const loginError = ref('')

const keyword = ref('')
const faqs = ref([])
const loading = ref(false)
const selected = ref(null)
const showTicket = ref(false)
const submittedTicketId = ref(null)
const form = ref({ customerName: '', mineName: '', category: '', description: '', screenshotUrl: '' })
const ticketUploading = ref(false)

const tickets = ref([])
const adminFaqs = ref([])
const adminTab = ref('tickets')
const faqUploading = ref(false)
const faqForm = ref({ id: null, category: '', question: '', answer: '', keywords: '', enabled: true, images: [] })

const loggedIn = computed(() => !!token.value)
const canViewAdmin = computed(() => role.value === 'admin')

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
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(loginForm.value)
    })
    if (!res.ok) throw new Error('用户名或密码错误')
    const data = await res.json()
    token.value = data.token
    role.value = data.role
    username.value = data.username
    localStorage.setItem('support_token', data.token)
    localStorage.setItem('support_role', data.role)
    localStorage.setItem('support_username', data.username)
    if (data.role === 'admin') {
      window.location.href = '/admin'
    } else {
      window.location.href = '/'
    }
  } catch (e) {
    loginError.value = e.message || '登录失败'
  }
}

function clearSession() {
  token.value = ''
  role.value = ''
  username.value = ''
  localStorage.removeItem('support_token')
  localStorage.removeItem('support_role')
  localStorage.removeItem('support_username')
}

async function logout() {
  try { await request(`${apiBase}/auth/logout`, { method: 'POST' }) } catch {}
  clearSession()
  window.location.href = '/'
}

async function uploadImage(file) {
  const data = new FormData()
  data.append('file', file)
  return request(`${apiBase}/upload/image`, { method: 'POST', body: data })
}

async function loadFaq() {
  loading.value = true
  try {
    const url = keyword.value.trim()
      ? `${apiBase}/faq/search?q=${encodeURIComponent(keyword.value.trim())}`
      : `${apiBase}/faq`
    faqs.value = await request(url)
  } finally {
    loading.value = false
  }
}

function openFaq(item) {
  selected.value = item
  showTicket.value = false
  submittedTicketId.value = null
}

function unresolved() {
  form.value.category = selected.value?.category || ''
  form.value.description = selected.value ? `参考问题：${selected.value.question}\n实际问题：` : ''
  showTicket.value = true
  setTimeout(() => document.getElementById('ticket-form')?.scrollIntoView({ behavior: 'smooth' }), 50)
}

async function chooseTicketImage(event) {
  const file = event.target.files?.[0]
  if (!file) return
  ticketUploading.value = true
  try {
    const data = await uploadImage(file)
    form.value.screenshotUrl = data.url
  } catch (e) {
    alert(`上传失败：${e.message}`)
  } finally {
    ticketUploading.value = false
    event.target.value = ''
  }
}

async function submitTicket() {
  try {
    const data = await request(`${apiBase}/ticket`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(form.value)
    })
    submittedTicketId.value = data.ticketId
  } catch (e) {
    alert(`提交失败：${e.message}`)
  }
}

async function loadTickets() {
  tickets.value = await request(`${apiBase}/admin/tickets`)
}

async function updateTicketStatus(ticket, status) {
  await request(`${apiBase}/admin/tickets/${ticket.id}/status`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status })
  })
  ticket.status = status
}

async function loadAdminFaqs() {
  adminFaqs.value = await request(`${apiBase}/admin/faqs`)
}

function editFaq(item) {
  faqForm.value = {
    id: item.id,
    category: item.category,
    question: item.question,
    answer: item.answer,
    keywords: item.keywords || '',
    enabled: item.enabled === 1 || item.enabled === true,
    images: [...(item.images || [])]
  }
}

function resetFaqForm() {
  faqForm.value = { id: null, category: '', question: '', answer: '', keywords: '', enabled: true, images: [] }
}

async function chooseFaqImages(event) {
  const files = Array.from(event.target.files || [])
  if (!files.length) return
  faqUploading.value = true
  try {
    for (const file of files) {
      const data = await uploadImage(file)
      faqForm.value.images.push(data.url)
    }
  } catch (e) {
    alert(`图片上传失败：${e.message}`)
  } finally {
    faqUploading.value = false
    event.target.value = ''
  }
}

function removeFaqImage(index) {
  faqForm.value.images.splice(index, 1)
}

async function saveFaq() {
  try {
    const editing = !!faqForm.value.id
    const url = editing ? `${apiBase}/admin/faqs/${faqForm.value.id}` : `${apiBase}/admin/faqs`
    await request(url, {
      method: editing ? 'PUT' : 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(faqForm.value)
    })
    resetFaqForm()
    await loadAdminFaqs()
  } catch (e) {
    alert(`保存失败：${e.message}`)
  }
}

async function deleteFaq(item) {
  if (!confirm(`确定删除“${item.question}”吗？`)) return
  await request(`${apiBase}/admin/faqs/${item.id}`, { method: 'DELETE' })
  await loadAdminFaqs()
}

function statusText(status) {
  return { pending: '待处理', processing: '处理中', resolved: '已解决' }[status] || status
}

onMounted(async () => {
  if (!loggedIn.value) return
  if (isAdminPath.value) {
    if (!canViewAdmin.value) return
    await Promise.all([loadTickets(), loadAdminFaqs()])
  } else {
    await loadFaq()
  }
})
</script>

<template>
  <main v-if="!loggedIn" class="login-page">
    <section class="login-card">
      <span class="badge dark">MYROBOOT SUPPORT</span>
      <h1>技术支持服务平台</h1>
      <p>登录后先从问题库自助排查，确实无法解决时再提交工单。</p>
      <label>用户名<input v-model="loginForm.username" @keyup.enter="login" placeholder="请输入用户名" /></label>
      <label>密码<input v-model="loginForm.password" type="password" @keyup.enter="login" placeholder="请输入密码" /></label>
      <div v-if="loginError" class="login-error">{{ loginError }}</div>
      <button class="primary login-button" @click="login">登录</button>
      <div class="login-tip">测试客户：customer / customer123　管理员：admin / admin123</div>
    </section>
  </main>

  <main v-else-if="isAdminPath" class="page admin-page">
    <section v-if="!canViewAdmin" class="panel forbidden">
      <h2>无管理员权限</h2>
      <p>当前账号只能使用客户自助服务。</p>
      <a class="primary link-button" href="/">返回客户页面</a>
    </section>

    <template v-else>
      <section class="admin-header">
        <div>
          <span class="badge">MYROBOOT ADMIN</span>
          <h1>技术支持管理后台</h1>
          <p>维护问题库、图片资料和客户工单。</p>
        </div>
        <div class="header-actions"><a class="back-link" href="/">客户页面</a><button class="secondary" @click="logout">退出登录</button></div>
      </section>

      <div class="admin-tabs">
        <button :class="{ active: adminTab === 'tickets' }" @click="adminTab = 'tickets'">工单管理</button>
        <button :class="{ active: adminTab === 'faqs' }" @click="adminTab = 'faqs'">问题库管理</button>
      </div>

      <section v-if="adminTab === 'tickets'" class="panel admin-panel">
        <div class="panel-title"><h2>工单列表</h2><span>{{ tickets.length }} 条</span></div>
        <div class="table-wrap">
          <table>
            <thead><tr><th>编号</th><th>客户/矿井</th><th>类型</th><th>问题描述</th><th>截图</th><th>状态</th><th>提交时间</th></tr></thead>
            <tbody>
              <tr v-for="ticket in tickets" :key="ticket.id">
                <td>#{{ ticket.id }}</td>
                <td>{{ ticket.customer_name || '-' }}<br><small>{{ ticket.mine_name || '-' }}</small></td>
                <td>{{ ticket.category || '-' }}</td>
                <td class="desc-cell">{{ ticket.description }}</td>
                <td><a v-if="ticket.screenshot_url" :href="ticket.screenshot_url" target="_blank"><img class="thumb" :src="ticket.screenshot_url" /></a><span v-else>-</span></td>
                <td><select :value="ticket.status" @change="updateTicketStatus(ticket, $event.target.value)"><option value="pending">待处理</option><option value="processing">处理中</option><option value="resolved">已解决</option></select><span class="status" :class="ticket.status">{{ statusText(ticket.status) }}</span></td>
                <td>{{ ticket.create_time || '-' }}</td>
              </tr>
              <tr v-if="tickets.length === 0"><td colspan="7" class="empty">暂无工单</td></tr>
            </tbody>
          </table>
        </div>
      </section>

      <section v-else class="admin-grid">
        <div class="panel admin-panel">
          <div class="panel-title"><h2>问题库</h2><span>{{ adminFaqs.length }} 条</span></div>
          <div class="faq-admin-list">
            <div v-for="item in adminFaqs" :key="item.id" class="faq-admin-item">
              <div><span class="category">{{ item.category }}</span><strong>{{ item.question }}</strong><small>{{ item.enabled ? '已启用' : '已停用' }} · {{ item.images?.length || 0 }} 张图</small></div>
              <div class="row-actions"><button class="secondary" @click="editFaq(item)">编辑</button><button class="danger" @click="deleteFaq(item)">删除</button></div>
            </div>
          </div>
        </div>

        <div class="panel admin-panel faq-editor">
          <h2>{{ faqForm.id ? '编辑问题' : '录入新问题' }}</h2>
          <label>分类<input v-model="faqForm.category" placeholder="例如：视频 / APP / 数据上传 / 网络" /></label>
          <label>问题标题<input v-model="faqForm.question" placeholder="例如：APP 登录不上怎么办？" /></label>
          <label>处理步骤<textarea v-model="faqForm.answer" rows="10" placeholder="建议按 1、2、3 的顺序写清楚排查步骤"></textarea></label>
          <label>搜索关键词<input v-model="faqForm.keywords" placeholder="例如：登录 认证 token 账号" /></label>
          <label>问题图片
            <input type="file" accept="image/*" multiple @change="chooseFaqImages" />
            <small>{{ faqUploading ? '图片上传中...' : '支持一次选择多张图片，单张不超过 10MB' }}</small>
          </label>
          <div v-if="faqForm.images.length" class="image-grid">
            <div v-for="(image, index) in faqForm.images" :key="image" class="image-item"><img :src="image" /><button @click="removeFaqImage(index)">×</button></div>
          </div>
          <label class="check-row"><input v-model="faqForm.enabled" type="checkbox" /> 启用并展示给客户</label>
          <div class="actions"><button class="primary" @click="saveFaq">保存到问题库</button><button v-if="faqForm.id" class="secondary" @click="resetFaqForm">取消编辑</button></div>
        </div>
      </section>
    </template>
  </main>

  <main v-else class="page">
    <section class="hero">
      <div><span class="badge">MYROBOOT SUPPORT</span><h1>客户自助技术支持</h1><p>先自己查问题库，只有标准方案无法解决时再提交工单。</p></div>
      <div class="header-actions customer-actions"><span>{{ username }}</span><button class="secondary" @click="logout">退出</button></div>
    </section>

    <section class="flow-panel">
      <div class="flow-step active"><span>1</span><div><strong>搜索问题库</strong><small>输入现象或报错关键词</small></div></div>
      <div class="flow-arrow">→</div>
      <div class="flow-step"><span>2</span><div><strong>按方案自助排查</strong><small>查看步骤和现场图片</small></div></div>
      <div class="flow-arrow">→</div>
      <div class="flow-step"><span>3</span><div><strong>仍未解决再提工单</strong><small>上传截图，减少来回沟通</small></div></div>
    </section>

    <section class="search-section panel">
      <div><h2>先从问题库查找</h2><p>建议搜索报错文字、功能名称或现象，例如“视频黑屏”“数据未上报”。</p></div>
      <div class="search-box light"><input v-model="keyword" @keyup.enter="loadFaq" placeholder="输入问题关键词" /><button @click="loadFaq">搜索</button></div>
    </section>

    <section class="content">
      <div class="panel list-panel">
        <div class="panel-title"><h2>问题库</h2><span>{{ loading ? '搜索中...' : `${faqs.length} 条结果` }}</span></div>
        <button v-for="item in faqs" :key="item.id" class="faq-item" @click="openFaq(item)"><span class="category">{{ item.category }}</span><strong>{{ item.question }}</strong><span class="arrow">›</span></button>
        <div v-if="!loading && faqs.length === 0" class="empty">没有搜到对应问题。可以换关键词，确认没有方案后再提交工单。</div>
      </div>

      <div class="panel detail-panel">
        <template v-if="selected">
          <span class="category">{{ selected.category }}</span><h2>{{ selected.question }}</h2><div class="answer">{{ selected.answer }}</div>
          <div v-if="selected.images?.length" class="solution-images"><a v-for="image in selected.images" :key="image" :href="image" target="_blank"><img :src="image" /></a></div>
          <div class="resolved-box"><strong>按照上面的步骤操作后，问题解决了吗？</strong><div class="actions"><button class="success" @click="selected = null">已解决，不用提工单</button><button class="secondary" @click="unresolved">仍未解决，提交工单</button></div></div>
        </template>
        <template v-else><div class="placeholder"><h2>选择一个问题查看处理方案</h2><p>这里会展示管理员维护的标准步骤和图片。</p></div></template>
      </div>
    </section>

    <section id="ticket-form" v-if="showTicket" class="panel ticket-panel">
      <div v-if="submittedTicketId" class="submitted"><h2>问题已提交</h2><p>工单编号：#{{ submittedTicketId }}</p><p>后台已经可以看到这条工单，请保留工单编号。</p></div>
      <template v-else>
        <div class="ticket-warning"><strong>提交前确认：</strong>你已经搜索并尝试过问题库里的标准处理方案。</div>
        <h2>提交未解决问题</h2>
        <div class="form-grid"><label>客户名称<input v-model="form.customerName" placeholder="公司/单位名称" /></label><label>矿井名称<input v-model="form.mineName" placeholder="例如：XX煤矿" /></label><label>问题类型<input v-model="form.category" placeholder="APP / 视频 / 数据上传 / 服务器" /></label><label>故障截图<input type="file" accept="image/*" @change="chooseTicketImage" /><small>{{ ticketUploading ? '上传中...' : '请尽量上传能看清报错的截图' }}</small></label></div>
        <div v-if="form.screenshotUrl" class="ticket-preview"><img :src="form.screenshotUrl" /></div>
        <label class="full">问题描述<textarea v-model="form.description" rows="7" placeholder="请写清：发生时间、报错信息、影响范围、是否所有人都出现、已经尝试过什么"></textarea></label>
        <button class="primary" @click="submitTicket">确认提交工单</button>
      </template>
    </section>
  </main>
</template>
