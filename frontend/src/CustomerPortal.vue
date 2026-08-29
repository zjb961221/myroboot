<script setup>
import { onMounted, ref, watch } from 'vue'
import { apiErrorMessage, showToast } from './uiFeedback'

const apiBase = import.meta.env.VITE_API_BASE || '/api'
const token = localStorage.getItem('support_token') || ''
const username = localStorage.getItem('support_username') || ''
const profile = ref(JSON.parse(localStorage.getItem('support_profile') || '{}'))
const customerTab = ref('knowledge')
const keyword = ref('')
const faqs = ref([])
const suggestions = ref([])
const loading = ref(false)
const suggesting = ref(false)
const selected = ref(null)
const showTicket = ref(false)
const submittedTicketId = ref(null)
const myTickets = ref([])
const ticketUploading = ref(false)
const attachmentUploading = ref(false)
const similarChecking = ref(false)
const similarFaqs = ref([])
const previews = ref({})
const previewLoading = ref({})
const form = ref({ customerName: profile.value.companyName || profile.value.mineName || '', mineName: profile.value.mineName || '', category: '', description: '', screenshotUrl: '', attachments: [] })
let searchTimer = null

function headers(extra = {}) { return { ...extra, Authorization: `Bearer ${token}` } }
async function request(url, options = {}) {
  options.headers = headers(options.headers || {})
  const res = await fetch(url, options)
  if (res.status === 401) { localStorage.removeItem('support_token'); window.location.href = '/'; throw new Error('登录已失效，请重新登录') }
  if (!res.ok) throw new Error(await apiErrorMessage(res))
  return res.status === 204 ? null : res.json()
}
async function logout() { try { await request(`${apiBase}/auth/logout`, { method: 'POST' }) } catch {}; localStorage.clear(); window.location.href = '/' }
async function loadFaq() { loading.value = true; suggestions.value = []; try { const q = keyword.value.trim(); faqs.value = await request(q ? `${apiBase}/faq/search?q=${encodeURIComponent(q)}` : `${apiBase}/faq`) } catch (e) { showToast(e.message) } finally { loading.value = false } }
async function loadSuggestions() { const q = keyword.value.trim(); if (!q) { suggestions.value = []; return } suggesting.value = true; try { suggestions.value = await request(`${apiBase}/faq/suggest?q=${encodeURIComponent(q)}`) } catch { suggestions.value = [] } finally { suggesting.value = false } }
async function chooseSuggestion(item) { keyword.value = item.question; await loadFaq(); selected.value = faqs.value.find(x => x.id === item.id) || faqs.value[0] || null }
function openFaq(item) { selected.value = item; showTicket.value = false; similarFaqs.value = [] }
function unresolved() { form.value.category = selected.value?.category || ''; form.value.description = selected.value ? `参考问题：${selected.value.question}\n实际问题：` : ''; similarFaqs.value = []; showTicket.value = true; setTimeout(() => document.getElementById('ticket-form')?.scrollIntoView({ behavior: 'smooth' }), 50) }
async function chooseTicketImage(event) { const file = event.target.files?.[0]; if (!file) return; const fd = new FormData(); fd.append('file', file); ticketUploading.value = true; try { const data = await request(`${apiBase}/upload/image`, { method: 'POST', body: fd }); form.value.screenshotUrl = data.url; showToast('故障截图已上传', 'success') } catch (e) { showToast(e.message) } finally { ticketUploading.value = false; event.target.value = '' } }
async function chooseTicketAttachments(event) {
  const files = Array.from(event.target.files || []); if (!files.length) return
  if (form.value.attachments.length + files.length > 10) { showToast('一个工单最多上传 10 个附件'); event.target.value = ''; return }
  attachmentUploading.value = true; let success = 0
  try { for (const file of files) { const fd = new FormData(); fd.append('file', file); try { const data = await request(`${apiBase}/upload/attachment`, { method: 'POST', body: fd }); form.value.attachments.push(data); success++ } catch (e) { showToast(`${file.name}：${e.message}`) } } if (success) showToast(`已上传 ${success} 个附件`, 'success') } finally { attachmentUploading.value = false; event.target.value = '' }
}
function removeAttachment(index) { form.value.attachments.splice(index, 1) }
function fileSize(size) { const n = Number(size || 0); if (n < 1024) return `${n} B`; if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`; return `${(n / 1024 / 1024).toFixed(1)} MB` }
function fileName(file) { return file.original_name || file.name || '附件' }
function fileUrl(file) { return file.file_url || file.url || '' }
function ext(file) { const name=fileName(file).toLowerCase(); const i=name.lastIndexOf('.'); return i<0?'':name.slice(i+1) }
function isImage(file) { return String(file.content_type || file.contentType || '').startsWith('image/') || ['png','jpg','jpeg','gif','webp','bmp'].includes(ext(file)) }
function isVideo(file) { return String(file.content_type || file.contentType || '').startsWith('video/') || ['mp4','mov','avi','mkv','webm'].includes(ext(file)) }
function isPdf(file) { return ext(file)==='pdf' }
function canDataPreview(file) { return ['xls','xlsx','txt','log','json','csv'].includes(ext(file)) && Number.isFinite(Number(file.id)) }
async function loadPreview(file) {
  if (!canDataPreview(file) || previews.value[file.id]) return
  previewLoading.value[file.id] = true
  try { previews.value[file.id] = await request(`${apiBase}/faq/attachments/${file.id}/preview`) }
  catch (e) { showToast(e.message) }
  finally { previewLoading.value[file.id] = false }
}
function validateTicket() { if (!form.value.category.trim()) { showToast('请填写问题类型'); return false } if (!form.value.description.trim()) { showToast('请填写问题描述'); return false } if (form.value.description.trim().length < 5) { showToast('问题描述太短，请至少写清楚现象或报错信息'); return false } return true }
async function submitTicket(force = false) {
  if (!validateTicket()) return
  if (!force) { similarChecking.value = true; try { const query = `${form.value.category} ${form.value.description}`.trim(); similarFaqs.value = await request(`${apiBase}/ticket/similar?q=${encodeURIComponent(query)}`); if (similarFaqs.value.length) { setTimeout(() => document.getElementById('similar-box')?.scrollIntoView({ behavior: 'smooth', block: 'center' }), 50); return } } catch (e) { showToast(e.message); return } finally { similarChecking.value = false } }
  try { const data = await request(`${apiBase}/ticket`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(form.value) }); submittedTicketId.value = data.ticketId; similarFaqs.value = []; await loadMyTickets(); showToast(`工单 #${data.ticketId} 已提交`, 'success') } catch (e) { showToast(e.message) }
}
function reviewSimilar(item) { selected.value = item; showTicket.value = false; similarFaqs.value = []; setTimeout(() => document.querySelector('.detail-panel')?.scrollIntoView({ behavior: 'smooth' }), 50) }
async function loadMyTickets() { try { myTickets.value = await request(`${apiBase}/tickets/mine`) } catch (e) { showToast(e.message) } }
async function cancelTicket(ticket) {
  const reason = window.prompt(`确定撤销工单 #${ticket.id} 吗？\n可以填写撤销原因（可留空）：`, '')
  if (reason === null) return
  try {
    await request(`${apiBase}/tickets/${ticket.id}/cancel`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ reason: reason.trim() })
    })
    await loadMyTickets()
    showToast(`工单 #${ticket.id} 已撤销`, 'success')
  } catch (e) { showToast(e.message) }
}
function statusText(status) { return { pending: '待处理', processing: '处理中', resolved: '已解决', cancelled: '已撤销' }[status] || status }
function formatTime(value) { return value ? String(value).replace('T', ' ').slice(0, 19) : '-' }
watch(keyword, () => { clearTimeout(searchTimer); searchTimer = setTimeout(loadSuggestions, 250) })
watch(customerTab, value => { if (value === 'tickets') loadMyTickets() })
onMounted(async () => { await Promise.all([loadFaq(), loadMyTickets()]) })
</script>

<template>
  <main class="page">
    <section class="hero"><div><span class="badge">MYROBOOT SUPPORT</span><h1>客户自助技术支持</h1><p>先查问题库，标准方案无法解决时再提交工单。</p></div><div class="header-actions customer-actions"><span>{{ profile.displayName || username }}<small>{{ profile.mineName || profile.companyName }}</small></span><button class="secondary" @click="logout">退出</button></div></section>
    <section class="flow-panel"><div class="flow-step"><span>1</span><div><strong>搜索问题库</strong><small>实时匹配分类、标题、内容和关键词</small></div></div><div class="flow-arrow">→</div><div class="flow-step"><span>2</span><div><strong>按方案自助排查</strong><small>直接预览图片、视频、PDF 和 Excel</small></div></div><div class="flow-arrow">→</div><div class="flow-step"><span>3</span><div><strong>仍未解决再提工单</strong><small>可附带截图、日志和文档</small></div></div></section>
    <div class="customer-tabs"><button :class="{active:customerTab==='knowledge'}" @click="customerTab='knowledge'">问题库 / 提交问题</button><button :class="{active:customerTab==='tickets'}" @click="customerTab='tickets'">我的工单 <span v-if="myTickets.length">{{ myTickets.length }}</span></button></div>

    <template v-if="customerTab==='knowledge'">
      <section class="search-section panel"><div><h2>第一步：先从问题库查找</h2><p>输入报错、功能名称或现象，系统会实时给出候选问题。</p></div><div class="search-autocomplete"><div class="search-box light"><input v-model="keyword" @keyup.enter="loadFaq" placeholder="例如：视频黑屏、APP 登录不上、数据未上报" autocomplete="off" /><button @click="loadFaq">搜索</button></div><div v-if="keyword.trim() && (suggestions.length || suggesting)" class="suggestion-menu"><div v-if="suggesting" class="suggestion-loading">正在匹配...</div><button v-for="item in suggestions" :key="item.id" @click="chooseSuggestion(item)"><span class="category">{{ item.category }}</span><strong>{{ item.question }}</strong></button></div></div></section>
      <section class="content"><div class="panel list-panel"><div class="panel-title"><h2>匹配问题</h2><span>{{ loading ? '搜索中...' : `${faqs.length} 条` }}</span></div><button v-for="item in faqs" :key="item.id" class="faq-item" @click="openFaq(item)"><span class="category">{{ item.category }}</span><strong>{{ item.question }}</strong><span class="arrow">›</span></button><div v-if="!loading&&!faqs.length" class="empty">暂时没有匹配到问题，可以换个关键词，或提交工单。</div></div>
        <div class="panel detail-panel"><template v-if="selected"><span class="category">{{ selected.category }}</span><h2>{{ selected.question }}</h2><div class="answer rich-content" v-html="selected.answer"></div>
          <div v-if="selected.images?.length" class="knowledge-images"><a v-for="image in selected.images" :key="image" :href="image" target="_blank"><img :src="image" /></a></div>
          <div v-if="selected.attachments?.length" class="resource-preview"><div class="resource-title"><h3>附件与资料</h3><span>{{selected.attachments.length}} 个</span></div>
            <article v-for="file in selected.attachments" :key="file.id || fileUrl(file)" class="resource-card">
              <img v-if="isImage(file)" :src="fileUrl(file)" class="resource-image" />
              <video v-else-if="isVideo(file)" :src="fileUrl(file)" controls preload="metadata" class="resource-video"></video>
              <iframe v-else-if="isPdf(file)" :src="fileUrl(file)" class="resource-pdf"></iframe>
              <div v-else-if="canDataPreview(file)" class="data-preview">
                <button v-if="!previews[file.id]" class="secondary" :disabled="previewLoading[file.id]" @click="loadPreview(file)">{{previewLoading[file.id]?'正在读取...':`预览 ${ext(file).toUpperCase()}`}}</button>
                <template v-if="previews[file.id]?.type==='excel'"><div class="preview-table"><table><tbody><tr v-for="(row,ri) in previews[file.id].rows" :key="ri"><td v-for="(cell,ci) in row" :key="ci">{{cell}}</td></tr></tbody></table></div><small>{{previews[file.id].note}}</small></template>
                <pre v-else-if="previews[file.id]?.type==='text'">{{previews[file.id].content}}</pre>
              </div>
              <div class="resource-meta"><div><strong>{{fileName(file)}}</strong><small>{{fileSize(file.file_size || file.size)}}</small></div><a :href="fileUrl(file)" target="_blank" download>打开 / 下载</a></div>
            </article>
          </div>
          <div class="resolved-box"><strong>按上面的方案操作后，问题解决了吗？</strong><div class="actions"><button class="success" @click="selected=null">已经解决</button><button class="secondary" @click="unresolved">没有解决，提交工单</button></div></div></template><template v-else><div class="placeholder"><h2>先选择一个问题</h2><p>这里会显示完整解决方案和相关附件预览。</p><button class="secondary" @click="showTicket=true">确实找不到，提交问题</button></div></template></div></section>
      <section v-if="showTicket" id="ticket-form" class="panel ticket-panel"><div v-if="submittedTicketId" class="submitted"><h2>问题已提交</h2><p>工单编号：#{{ submittedTicketId }}</p><button class="primary" @click="customerTab='tickets'">查看我的工单</button></div><template v-else><h2>第二步：提交完整故障信息</h2><div class="form-grid"><label>客户名称<input v-model="form.customerName" /></label><label>矿井名称<input v-model="form.mineName" /></label><label>问题类型 <em class="required">*</em><input v-model="form.category" placeholder="APP / 视频 / 数据 / 网络" /></label><label>故障截图<input type="file" accept="image/*" @change="chooseTicketImage" /><small>{{ ticketUploading ? '上传中...' : '建议上传最关键的一张报错截图' }}</small></label></div><div v-if="form.screenshotUrl" class="ticket-preview"><img :src="form.screenshotUrl" /></div><label class="full">问题描述 <em class="required">*</em><textarea v-model="form.description" rows="7" placeholder="请写清发生时间、报错信息、影响范围、已经尝试过什么"></textarea></label><div class="attachment-box"><div class="attachment-head"><div><strong>提交附件</strong><small>支持图片、日志、Excel、Word、PPT、PDF、压缩包和视频；普通文件≤30MB，视频≤200MB，最多 10 个。</small></div><label class="attachment-button">{{ attachmentUploading ? '上传中...' : '选择附件' }}<input type="file" multiple accept=".png,.jpg,.jpeg,.gif,.webp,.bmp,.pdf,.txt,.log,.csv,.json,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.zip,.rar,.7z,.mp4,.mov,.avi,.mkv,.webm" @change="chooseTicketAttachments" /></label></div><div v-if="form.attachments.length" class="attachment-list"><div v-for="(file,index) in form.attachments" :key="file.url" class="attachment-item"><div><strong>{{ file.name }}</strong><small>{{ fileSize(file.size) }}</small></div><button type="button" @click="removeAttachment(index)">移除</button></div></div></div><button class="primary" :disabled="similarChecking || attachmentUploading" @click="submitTicket(false)">{{ similarChecking ? '正在检查相似问题...' : '检查并提交工单' }}</button><div v-if="similarFaqs.length" id="similar-box" class="similar-box"><div class="similar-head"><div><span>提交前提示</span><h3>问题库里可能已经有类似问题</h3><p>建议先看下面的方案，能解决的话就不用等待人工处理。</p></div></div><button v-for="item in similarFaqs" :key="item.id" class="similar-item" @click="reviewSimilar(item)"><span class="category">{{ item.category }}</span><strong>{{ item.question }}</strong><span>查看方案 →</span></button><div class="similar-actions"><button class="secondary" @click="similarFaqs=[]">返回修改描述</button><button class="primary" @click="submitTicket(true)">这些都不是，仍然提交</button></div></div></template></section>
    </template>

    <section v-else class="ticket-history"><div class="panel-title"><h2>我的工单</h2><span>{{ myTickets.length }} 条</span></div><div v-if="!myTickets.length" class="panel empty">暂时没有提交过工单。</div><article v-for="ticket in myTickets" :key="ticket.id" class="panel ticket-card"><div class="ticket-card-head"><div><strong>#{{ ticket.id }} {{ ticket.category || '技术问题' }}</strong><small>{{ formatTime(ticket.create_time) }}</small></div><span class="status-pill" :class="ticket.status">{{ statusText(ticket.status) }}</span></div><p class="ticket-description">{{ ticket.description }}</p><a v-if="ticket.screenshot_url" :href="ticket.screenshot_url" target="_blank"><img class="ticket-shot" :src="ticket.screenshot_url" /></a><div v-if="ticket.attachments?.length" class="ticket-attachments"><strong>附件</strong><a v-for="file in ticket.attachments" :key="file.id" :href="file.file_url" target="_blank">{{ file.original_name }} <small>{{ fileSize(file.file_size) }}</small></a></div><div v-if="ticket.status==='resolved'" class="receipt"><h3>处理回执</h3><dl><dt>问题具体原因</dt><dd>{{ ticket.resolution_reason || '-' }}</dd><dt>处理结果</dt><dd>{{ ticket.resolution_result || '-' }}</dd><dt>解决时间</dt><dd>{{ formatTime(ticket.resolved_time) }}</dd></dl><a class="receipt-detail-link" :href="`/ticket-detail?id=${ticket.id}`">查看处理过程和回执附件 →</a></div><div v-else-if="ticket.status==='cancelled'" class="cancelled-receipt"><h3>工单已撤销</h3><dl><dt>撤销原因</dt><dd>{{ ticket.cancel_reason || '客户主动撤销工单' }}</dd><dt>撤销时间</dt><dd>{{ formatTime(ticket.cancelled_time) }}</dd></dl><a class="receipt-detail-link" :href="`/ticket-detail?id=${ticket.id}`">查看工单时间线 →</a></div><div v-else class="waiting-receipt"><div>{{ ticket.status==='processing' ? '技术人员正在处理。' : '工单正在等待技术人员处理。' }} <a :href="`/ticket-detail?id=${ticket.id}`">查看处理进度 →</a></div><button class="danger small-btn" @click="cancelTicket(ticket)">撤销工单</button></div></article></section>
  </main>
</template>