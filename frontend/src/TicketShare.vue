<script setup>
import { onMounted, ref } from 'vue'
import { apiErrorMessage } from './uiFeedback'

const apiBase = import.meta.env.VITE_API_BASE || '/api'
const ticket = ref(null)
const loading = ref(true)
const error = ref('')

function time(v){return v?String(v).replace('T',' ').slice(0,19):'-'}
function size(v){const n=Number(v||0);if(n<1024)return `${n} B`;if(n<1048576)return `${(n/1024).toFixed(1)} KB`;return `${(n/1048576).toFixed(1)} MB`}
function isImage(file){return String(file.content_type||'').startsWith('image/')||/\.(png|jpg|jpeg|gif|webp|bmp)$/i.test(file.original_name||'')}
function isVideo(file){return String(file.content_type||'').startsWith('video/')||/\.(mp4|mov|avi|mkv|webm)$/i.test(file.original_name||'')}
function statusText(v){return {pending:'待处理',processing:'处理中',resolved:'已解决',cancelled:'已撤销'}[v]||v||'-'}

async function loadSharedTicket(){
  const response = await fetch(`${apiBase}/public/ticket-share`, { credentials:'same-origin', cache:'no-store' })
  if(!response.ok) throw new Error(await apiErrorMessage(response,'分享链接不存在、已过期或已被撤销'))
  ticket.value = await response.json()
}

async function open(){
  loading.value=true; error.value=''
  try{
    const hashToken = decodeURIComponent(location.hash.replace(/^#/,''))
    if(hashToken){
      const response = await fetch(`${apiBase}/public/ticket-share/open`,{
        method:'POST',
        headers:{'X-Share-Token':hashToken},
        credentials:'same-origin',
        cache:'no-store'
      })
      if(!response.ok) throw new Error(await apiErrorMessage(response,'分享链接不存在、已过期或已被撤销'))
      history.replaceState(null,'',location.pathname)
    }
    await loadSharedTicket()
  }catch(e){
    ticket.value=null
    error.value=e.message||'分享链接不存在、已过期或已被撤销'
  }finally{
    loading.value=false
  }
}

onMounted(open)
</script>

<template>
  <main class="share-page">
    <section class="share-card">
      <header>
        <div>
          <span>MYROBOOT SUPPORT · 安全分享</span>
          <h1 v-if="ticket">工单 #{{ticket.id}}</h1>
          <h1 v-else>工单问题分享</h1>
        </div>
        <small v-if="ticket">有效至 {{time(ticket.expires_time)}}</small>
      </header>

      <div v-if="loading" class="state">正在验证分享权限…</div>
      <div v-else-if="error" class="invalid">
        <b>无法查看该分享</b>
        <p>{{error}}</p>
        <small>请让分享人重新生成链接，或确认分享没有被撤销。</small>
      </div>

      <template v-else-if="ticket">
        <section class="identity block">
          <h2>工单来源</h2>
          <div class="identity-grid">
            <div><small>提出人</small><strong>{{ticket.submitter_name||'未知提交人'}}</strong></div>
            <div><small>客户 / 单位</small><strong>{{ticket.customer_name||'-'}}</strong></div>
            <div><small>矿井</small><strong>{{ticket.mine_name||'-'}}</strong></div>
            <div><small>处理人员</small><strong>{{ticket.processor_name||'暂未分配'}}</strong></div>
            <div><small>当前状态</small><strong>{{statusText(ticket.status)}}</strong></div>
            <div><small>提交时间</small><strong>{{time(ticket.create_time)}}</strong></div>
          </div>
        </section>

        <section class="problem">
          <div class="meta"><b>{{ticket.category||'技术问题'}}</b><span>工单 #{{ticket.id}}</span></div>
          <h2>问题描述</h2>
          <p>{{ticket.description}}</p>
        </section>

        <section v-if="ticket.has_screenshot" class="block">
          <h2>客户截图</h2>
          <a :href="`${apiBase}/public/ticket-share/screenshot`" target="_blank" rel="noopener">
            <img :src="`${apiBase}/public/ticket-share/screenshot`" alt="客户提交截图" />
          </a>
        </section>

        <section v-if="ticket.attachments?.length" class="block">
          <h2>客户提交附件</h2>
          <div class="files">
            <article v-for="file in ticket.attachments" :key="file.id">
              <img v-if="isImage(file)" :src="file.file_url" :alt="file.original_name" />
              <video v-else-if="isVideo(file)" :src="file.file_url" controls preload="metadata"></video>
              <a :href="file.file_url" target="_blank" rel="noopener">
                <span>{{file.original_name}}</span>
                <small>{{size(file.file_size)}}</small>
              </a>
            </article>
          </div>
        </section>

        <footer>
          此页面展示工单来源、当前处理人员、问题内容和客户原始附件。出于隐私和安全考虑，不公开手机号、邮箱、账号等联系方式，也不提供其他工单访问权限。
        </footer>
      </template>
    </section>
  </main>
</template>

<style scoped>
.share-page{min-height:100vh;background:#f3f6fa;padding:38px 18px;color:#26364a}.share-card{max-width:900px;margin:auto;background:#fff;border:1px solid #e0e7ef;border-radius:20px;overflow:hidden;box-shadow:0 18px 50px rgba(25,45,70,.07)}header{background:#10243e;color:#fff;padding:26px 30px;display:flex;justify-content:space-between;gap:20px;align-items:center}header span{font-size:12px;letter-spacing:1.2px;opacity:.7}header h1{margin:8px 0 0;font-size:28px}header small{opacity:.8}.state,.invalid{margin:28px;padding:22px;border-radius:12px}.state{background:#f6f8fb;color:#6d7c90}.invalid{background:#fff4f2;border:1px solid #fecdca;color:#b42318}.invalid p{margin:8px 0}.invalid small{color:#8f5b54}.problem,.block{padding:26px 30px;border-bottom:1px solid #edf1f5}.meta{display:flex;justify-content:space-between;gap:16px;color:#758398}.meta b{color:#245eea}.problem h2,.block h2{font-size:17px;margin:20px 0 12px}.identity h2{margin-top:0}.identity-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.identity-grid>div{display:grid;gap:5px;padding:13px 14px;background:#f8fafc;border:1px solid #e4eaf1;border-radius:10px}.identity-grid small{font-size:12px;color:#7d8b9d}.identity-grid strong{color:#253950;word-break:break-word}.problem p{white-space:pre-wrap;line-height:1.8;margin:0;color:#34465d}.block>a img{display:block;max-width:100%;max-height:520px;border-radius:12px;border:1px solid #e1e7ee}.files{display:grid;gap:12px}.files article{border:1px solid #e1e7ee;border-radius:12px;overflow:hidden;background:#fafcff}.files article>img,.files video{display:block;width:100%;max-height:480px;object-fit:contain;background:#111}.files article>img{background:#f5f7fa}.files a{display:flex;justify-content:space-between;gap:16px;padding:12px 14px;color:#2e5fac;text-decoration:none}.files a small{color:#8390a1;white-space:nowrap}footer{padding:18px 30px;background:#f8fafc;color:#7c8998;font-size:13px;line-height:1.7}@media(max-width:700px){.identity-grid{grid-template-columns:1fr 1fr}}@media(max-width:600px){.share-page{padding:0}.share-card{border:0;border-radius:0;min-height:100vh}header{display:grid;padding:22px}.problem,.block{padding:22px}.identity-grid{grid-template-columns:1fr}.meta,.files a{display:grid}footer{padding:18px 22px}}
</style>
