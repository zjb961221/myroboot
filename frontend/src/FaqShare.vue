<script setup>
import { onMounted, ref } from 'vue'
import { apiErrorMessage } from './uiFeedback'

const apiBase = import.meta.env.VITE_API_BASE || '/api'
const faq = ref(null)
const loading = ref(true)
const error = ref('')

function time(v){return v?String(v).replace('T',' ').slice(0,19):'-'}
function size(v){const n=Number(v||0);if(n<1024)return `${n} B`;if(n<1048576)return `${(n/1024).toFixed(1)} KB`;return `${(n/1048576).toFixed(1)} MB`}
function ext(file){const name=String(file.original_name||'').toLowerCase();const i=name.lastIndexOf('.');return i<0?'':name.slice(i+1)}
function isImage(file){return String(file.content_type||'').startsWith('image/')||['png','jpg','jpeg','gif','webp','bmp'].includes(ext(file))}
function isVideo(file){return String(file.content_type||'').startsWith('video/')||['mp4','mov','avi','mkv','webm'].includes(ext(file))}
function isPdf(file){return ext(file)==='pdf'}

async function loadSharedFaq(){
  const response = await fetch(`${apiBase}/public/faq-share`, { credentials:'same-origin', cache:'no-store' })
  if(!response.ok) throw new Error(await apiErrorMessage(response,'分享链接不存在、已过期或已被撤销'))
  faq.value = await response.json()
}

async function open(){
  loading.value=true; error.value=''
  try{
    const hashToken = decodeURIComponent(location.hash.replace(/^#/,''))
    if(hashToken){
      const response = await fetch(`${apiBase}/public/faq-share/open`,{
        method:'POST', headers:{'X-Share-Token':hashToken}, credentials:'same-origin', cache:'no-store'
      })
      if(!response.ok) throw new Error(await apiErrorMessage(response,'分享链接不存在、已过期或已被撤销'))
      history.replaceState(null,'',location.pathname)
    }
    await loadSharedFaq()
  }catch(e){
    faq.value=null
    error.value=e.message||'分享链接不存在、已过期或已被撤销'
  }finally{loading.value=false}
}

onMounted(open)
</script>

<template>
  <main class="share-page">
    <section class="share-card">
      <header>
        <div><span>MYROBOOT SUPPORT · 问题库分享</span><h1 v-if="faq">{{faq.question}}</h1><h1 v-else>标准问题解决方案</h1></div>
        <small v-if="faq">有效至 {{time(faq.expires_time)}}</small>
      </header>
      <div v-if="loading" class="state">正在验证分享权限…</div>
      <div v-else-if="error" class="invalid"><b>无法查看该分享</b><p>{{error}}</p><small>请让分享人重新生成链接，或确认分享没有被撤销。</small></div>
      <template v-else-if="faq">
        <section class="solution">
          <div class="meta"><b>{{faq.category||'标准问题'}}</b><span>更新时间 {{time(faq.update_time||faq.create_time)}}</span></div>
          <h2>{{faq.question}}</h2>
          <div class="answer" v-html="faq.answer"></div>
        </section>
        <section v-if="faq.images?.length" class="block">
          <h2>相关图片</h2>
          <div class="images"><a v-for="image in faq.images" :key="image.id" :href="image.url" target="_blank" rel="noopener"><img :src="image.url" alt="问题库图片"/></a></div>
        </section>
        <section v-if="faq.attachments?.length" class="block">
          <h2>附件与资料</h2>
          <div class="files">
            <article v-for="file in faq.attachments" :key="file.id">
              <img v-if="isImage(file)" :src="file.file_url" :alt="file.original_name"/>
              <video v-else-if="isVideo(file)" :src="file.file_url" controls preload="metadata"></video>
              <iframe v-else-if="isPdf(file)" :src="file.file_url"></iframe>
              <a :href="file.file_url" target="_blank" rel="noopener"><span>{{file.original_name}}</span><small>{{size(file.file_size)}}</small></a>
            </article>
          </div>
        </section>
        <footer>此页面仅展示分享人授权的标准问题解决方案及其附件，不需要登录，也不会授予后台或其他数据访问权限。</footer>
      </template>
    </section>
  </main>
</template>

<style scoped>
.share-page{min-height:100vh;background:#f3f6fa;padding:38px 18px;color:#26364a}.share-card{max-width:960px;margin:auto;background:#fff;border:1px solid #e0e7ef;border-radius:20px;overflow:hidden;box-shadow:0 18px 50px rgba(25,45,70,.07)}header{background:#10243e;color:#fff;padding:26px 30px;display:flex;justify-content:space-between;gap:22px;align-items:flex-start}header>div{min-width:0}header span{font-size:12px;letter-spacing:1.2px;opacity:.7}header h1{margin:8px 0 0;font-size:26px;line-height:1.35;overflow-wrap:anywhere}header small{opacity:.8;white-space:nowrap}.state,.invalid{margin:28px;padding:22px;border-radius:12px}.state{background:#f6f8fb;color:#6d7c90}.invalid{background:#fff4f2;border:1px solid #fecdca;color:#b42318}.invalid p{margin:8px 0}.invalid small{color:#8f5b54}.solution,.block{padding:26px 30px;border-bottom:1px solid #edf1f5}.meta{display:flex;justify-content:space-between;gap:16px;color:#758398}.meta b{color:#245eea}.solution h2,.block h2{font-size:20px;margin:20px 0 14px;overflow-wrap:anywhere}.answer{line-height:1.8;color:#34465d;overflow-wrap:anywhere}.answer :deep(img){max-width:100%;height:auto}.answer :deep(pre){white-space:pre-wrap;overflow-wrap:anywhere}.images{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px}.images img{display:block;width:100%;height:180px;object-fit:contain;background:#f5f7fa;border:1px solid #e1e7ee;border-radius:12px}.files{display:grid;gap:12px}.files article{border:1px solid #e1e7ee;border-radius:12px;overflow:hidden;background:#fafcff}.files article>img,.files video,.files iframe{display:block;width:100%;max-height:520px;border:0;object-fit:contain;background:#111}.files article>img{background:#f5f7fa}.files iframe{height:520px;background:#fff}.files a{display:flex;justify-content:space-between;gap:16px;padding:12px 14px;color:#2e5fac;text-decoration:none}.files a span{min-width:0;overflow-wrap:anywhere}.files a small{color:#8390a1;white-space:nowrap}footer{padding:18px 30px;background:#f8fafc;color:#7c8998;font-size:13px;line-height:1.7}@media(max-width:600px){.share-page{padding:0}.share-card{border:0;border-radius:0;min-height:100vh}header{display:grid;padding:22px}.solution,.block{padding:22px}.meta,.files a{display:grid}.files iframe{height:420px}footer{padding:18px 22px}}
</style>
