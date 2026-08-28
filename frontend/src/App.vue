<script setup>
import { ref, onMounted } from 'vue'

const apiBase = import.meta.env.VITE_API_BASE || 'http://localhost:8080/api'
const keyword = ref('')
const faqs = ref([])
const loading = ref(false)
const selected = ref(null)
const showTicket = ref(false)
const submittedTicketId = ref(null)
const form = ref({
  customerName: '',
  mineName: '',
  category: '',
  description: '',
  screenshotUrl: ''
})

async function loadFaq() {
  loading.value = true
  try {
    const url = keyword.value.trim()
      ? `${apiBase}/faq/search?q=${encodeURIComponent(keyword.value.trim())}`
      : `${apiBase}/faq`
    const res = await fetch(url)
    faqs.value = await res.json()
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
  form.value.description = selected.value
    ? `参考问题：${selected.value.question}\n实际问题：`
    : ''
  showTicket.value = true
}

async function submitTicket() {
  const res = await fetch(`${apiBase}/ticket`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(form.value)
  })
  if (!res.ok) {
    alert('提交失败，请检查问题描述是否填写完整')
    return
  }
  const data = await res.json()
  submittedTicketId.value = data.ticketId
}

onMounted(loadFaq)
</script>

<template>
  <main class="page">
    <section class="hero">
      <div>
        <span class="badge">MYROBOOT SUPPORT</span>
        <h1>客户自助技术支持</h1>
        <p>先搜索常见问题。仍未解决时，按要求提交完整故障信息，减少来回沟通。</p>
      </div>
      <div class="search-box">
        <input v-model="keyword" @keyup.enter="loadFaq" placeholder="例如：APP 登录不上、视频黑屏、数据未上报" />
        <button @click="loadFaq">搜索</button>
      </div>
    </section>

    <section class="content">
      <div class="panel list-panel">
        <div class="panel-title">
          <h2>常见问题</h2>
          <span>{{ loading ? '搜索中...' : `${faqs.length} 条结果` }}</span>
        </div>
        <button v-for="item in faqs" :key="item.id" class="faq-item" @click="openFaq(item)">
          <span class="category">{{ item.category }}</span>
          <strong>{{ item.question }}</strong>
          <span class="arrow">›</span>
        </button>
        <div v-if="!loading && faqs.length === 0" class="empty">
          没找到对应问题。可以换个关键词，或直接提交问题反馈。
        </div>
      </div>

      <div class="panel detail-panel">
        <template v-if="selected">
          <span class="category">{{ selected.category }}</span>
          <h2>{{ selected.question }}</h2>
          <div class="answer">{{ selected.answer }}</div>
          <div class="resolved-box">
            <strong>这个回答解决了您的问题吗？</strong>
            <div class="actions">
              <button class="success" @click="selected = null">已解决</button>
              <button class="secondary" @click="unresolved">未解决，继续反馈</button>
            </div>
          </div>
        </template>
        <template v-else>
          <div class="placeholder">
            <h2>先选择一个问题</h2>
            <p>左侧选择常见问题后，这里会显示标准处理方法。</p>
            <button class="secondary" @click="showTicket = true">直接提交问题</button>
          </div>
        </template>
      </div>
    </section>

    <section v-if="showTicket" class="panel ticket-panel">
      <div v-if="submittedTicketId" class="submitted">
        <h2>问题已提交</h2>
        <p>工单编号：#{{ submittedTicketId }}</p>
        <p>技术人员将根据你提交的信息继续排查。</p>
      </div>
      <template v-else>
        <h2>提交完整故障信息</h2>
        <div class="form-grid">
          <label>客户名称<input v-model="form.customerName" placeholder="公司/单位名称" /></label>
          <label>矿井名称<input v-model="form.mineName" placeholder="例如：XX煤矿" /></label>
          <label>问题类型<input v-model="form.category" placeholder="APP / 视频 / 数据上传 / 服务器" /></label>
          <label>截图地址<input v-model="form.screenshotUrl" placeholder="第一版先填写图片链接，可留空" /></label>
        </div>
        <label class="full">问题描述<textarea v-model="form.description" rows="7" placeholder="请写清楚发生时间、报错信息、影响范围、已经尝试过什么"></textarea></label>
        <button class="primary" @click="submitTicket">提交问题</button>
      </template>
    </section>
  </main>
</template>
