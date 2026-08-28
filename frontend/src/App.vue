<script setup>
import { computed, onMounted, ref } from 'vue'

const apiBase = import.meta.env.VITE_API_BASE || 'http://localhost:8080/api'
const isAdmin = computed(() => window.location.pathname.startsWith('/admin'))

const keyword = ref('')
const faqs = ref([])
const loading = ref(false)
const selected = ref(null)
const showTicket = ref(false)
const submittedTicketId = ref(null)
const form = ref({ customerName: '', mineName: '', category: '', description: '', screenshotUrl: '' })

const tickets = ref([])
const adminFaqs = ref([])
const adminTab = ref('tickets')
const faqForm = ref({ id: null, category: '', question: '', answer: '', keywords: '', enabled: true })

async function request(url, options) {
  const res = await fetch(url, options)
  if (!res.ok) throw new Error(await res.text())
  return res.status === 204 ? null : res.json()
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
}

async function submitTicket() {
  try {
    const data = await request(`${apiBase}/ticket`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(form.value)
    })
    submittedTicketId.value = data.ticketId
  } catch {
    alert('提交失败，请检查问题描述是否填写完整')
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
    enabled: item.enabled === 1 || item.enabled === true
  }
}

function resetFaqForm() {
  faqForm.value = { id: null, category: '', question: '', answer: '', keywords: '', enabled: true }
}

async function saveFaq() {
  const editing = !!faqForm.value.id
  const url = editing ? `${apiBase}/admin/faqs/${faqForm.value.id}` : `${apiBase}/admin/faqs`
  await request(url, {
    method: editing ? 'PUT' : 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(faqForm.value)
  })
  resetFaqForm()
  await loadAdminFaqs()
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
  if (isAdmin.value) {
    await Promise.all([loadTickets(), loadAdminFaqs()])
  } else {
    await loadFaq()
  }
})
</script>

<template>
  <main v-if="isAdmin" class="page admin-page">
    <section class="admin-header">
      <div>
        <span class="badge">MYROBOOT ADMIN</span>
        <h1>技术支持管理后台</h1>
        <p>集中处理客户工单并维护常见问题知识库。</p>
      </div>
      <a class="back-link" href="/">返回客户页面</a>
    </section>

    <div class="admin-tabs">
      <button :class="{ active: adminTab === 'tickets' }" @click="adminTab = 'tickets'">工单管理</button>
      <button :class="{ active: adminTab === 'faqs' }" @click="adminTab = 'faqs'">FAQ 管理</button>
    </div>

    <section v-if="adminTab === 'tickets'" class="panel admin-panel">
      <div class="panel-title"><h2>工单列表</h2><span>{{ tickets.length }} 条</span></div>
      <div class="table-wrap">
        <table>
          <thead><tr><th>编号</th><th>客户/矿井</th><th>类型</th><th>问题描述</th><th>状态</th><th>提交时间</th></tr></thead>
          <tbody>
            <tr v-for="ticket in tickets" :key="ticket.id">
              <td>#{{ ticket.id }}</td>
              <td>{{ ticket.customer_name || '-' }}<br><small>{{ ticket.mine_name || '-' }}</small></td>
              <td>{{ ticket.category || '-' }}</td>
              <td class="desc-cell">{{ ticket.description }}<div v-if="ticket.screenshot_url"><a :href="ticket.screenshot_url" target="_blank">查看截图</a></div></td>
              <td>
                <select :value="ticket.status" @change="updateTicketStatus(ticket, $event.target.value)">
                  <option value="pending">待处理</option>
                  <option value="processing">处理中</option>
                  <option value="resolved">已解决</option>
                </select>
                <span class="status" :class="ticket.status">{{ statusText(ticket.status) }}</span>
              </td>
              <td>{{ ticket.create_time || '-' }}</td>
            </tr>
            <tr v-if="tickets.length === 0"><td colspan="6" class="empty">暂无工单</td></tr>
          </tbody>
        </table>
      </div>
    </section>

    <section v-else class="admin-grid">
      <div class="panel admin-panel">
        <div class="panel-title"><h2>FAQ 列表</h2><span>{{ adminFaqs.length }} 条</span></div>
        <div class="faq-admin-list">
          <div v-for="item in adminFaqs" :key="item.id" class="faq-admin-item">
            <div><span class="category">{{ item.category }}</span><strong>{{ item.question }}</strong><small>{{ item.enabled ? '已启用' : '已停用' }}</small></div>
            <div class="row-actions"><button class="secondary" @click="editFaq(item)">编辑</button><button class="danger" @click="deleteFaq(item)">删除</button></div>
          </div>
        </div>
      </div>

      <div class="panel admin-panel faq-editor">
        <h2>{{ faqForm.id ? '编辑 FAQ' : '新增 FAQ' }}</h2>
        <label>分类<input v-model="faqForm.category" placeholder="例如：视频 / APP / 数据上传" /></label>
        <label>问题<input v-model="faqForm.question" placeholder="客户常见问题" /></label>
        <label>答案<textarea v-model="faqForm.answer" rows="8" placeholder="标准处理步骤"></textarea></label>
        <label>关键词<input v-model="faqForm.keywords" placeholder="多个关键词可用空格或逗号分隔" /></label>
        <label class="check-row"><input v-model="faqForm.enabled" type="checkbox" /> 启用</label>
        <div class="actions"><button class="primary" @click="saveFaq">保存</button><button v-if="faqForm.id" class="secondary" @click="resetFaqForm">取消编辑</button></div>
      </div>
    </section>
  </main>

  <main v-else class="page">
    <section class="hero">
      <div>
        <span class="badge">MYROBOOT SUPPORT</span>
        <h1>客户自助技术支持</h1>
        <p>先搜索常见问题。仍未解决时，按要求提交完整故障信息，减少来回沟通。</p>
      </div>
      <div class="search-box"><input v-model="keyword" @keyup.enter="loadFaq" placeholder="例如：APP 登录不上、视频黑屏、数据未上报" /><button @click="loadFaq">搜索</button></div>
    </section>

    <section class="content">
      <div class="panel list-panel">
        <div class="panel-title"><h2>常见问题</h2><span>{{ loading ? '搜索中...' : `${faqs.length} 条结果` }}</span></div>
        <button v-for="item in faqs" :key="item.id" class="faq-item" @click="openFaq(item)"><span class="category">{{ item.category }}</span><strong>{{ item.question }}</strong><span class="arrow">›</span></button>
        <div v-if="!loading && faqs.length === 0" class="empty">没找到对应问题。可以换个关键词，或直接提交问题反馈。</div>
      </div>

      <div class="panel detail-panel">
        <template v-if="selected">
          <span class="category">{{ selected.category }}</span><h2>{{ selected.question }}</h2><div class="answer">{{ selected.answer }}</div>
          <div class="resolved-box"><strong>这个回答解决了您的问题吗？</strong><div class="actions"><button class="success" @click="selected = null">已解决</button><button class="secondary" @click="unresolved">未解决，继续反馈</button></div></div>
        </template>
        <template v-else><div class="placeholder"><h2>先选择一个问题</h2><p>左侧选择常见问题后，这里会显示标准处理方法。</p><button class="secondary" @click="showTicket = true">直接提交问题</button></div></template>
      </div>
    </section>

    <section v-if="showTicket" class="panel ticket-panel">
      <div v-if="submittedTicketId" class="submitted"><h2>问题已提交</h2><p>工单编号：#{{ submittedTicketId }}</p><p>技术人员将根据你提交的信息继续排查。</p></div>
      <template v-else>
        <h2>提交完整故障信息</h2>
        <div class="form-grid"><label>客户名称<input v-model="form.customerName" placeholder="公司/单位名称" /></label><label>矿井名称<input v-model="form.mineName" placeholder="例如：XX煤矿" /></label><label>问题类型<input v-model="form.category" placeholder="APP / 视频 / 数据上传 / 服务器" /></label><label>截图地址<input v-model="form.screenshotUrl" placeholder="第一版先填写图片链接，可留空" /></label></div>
        <label class="full">问题描述<textarea v-model="form.description" rows="7" placeholder="请写清楚发生时间、报错信息、影响范围、已经尝试过什么"></textarea></label>
        <button class="primary" @click="submitTicket">提交问题</button>
      </template>
    </section>
  </main>
</template>
