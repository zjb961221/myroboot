<script setup>
import { nextTick, onMounted, ref } from 'vue'
import Quill from 'quill'
import 'quill/dist/quill.snow.css'
import { apiErrorMessage, showToast } from './uiFeedback'

const apiBase = import.meta.env.VITE_API_BASE || '/api'
const token = localStorage.getItem('support_token') || ''
const faqs = ref([])
const loading = ref(false)
const saving = ref(false)
const uploading = ref(false)
const editorEl = ref(null)
let quill = null

const form = ref({
  category: '',
  question: '',
  answer: '',
  keywords: '',
  enabled: true,
  images: [],
  attachments: []
})

function headers(extra={}) { return { ...extra, Authorization:`Bearer ${token}` } }
async function request(url, options={}) {
  options.headers = headers(options.headers || {})
  const res = await fetch(url, options)
  if (res.status === 401) { localStorage.clear(); location.href='/'; throw new Error('登录已失效，请重新登录') }
  if (!res.ok) throw new Error(await apiErrorMessage(res))
  return res.status === 204 ? null : res.json()
}
function fileSize(v){const n=Number(v||0);if(n<1024)return `${n} B`;if(n<1048576)return `${(n/1024).toFixed(1)} KB`;return `${(n/1048576).toFixed(1)} MB`}
function initEditor(){nextTick(()=>{if(!editorEl.value)return;if(!quill){quill=new Quill(editorEl.value,{theme:'snow',placeholder:'写清楚现象、原因、处理步骤、注意事项……',modules:{toolbar:[[{header:[1,2,3,false]}],['bold','italic','underline'],[{list:'ordered'},{list:'bullet'}],['blockquote','code-block'],['link'],['clean']]}});quill.on('text-change',()=>{form.value.answer=quill.root.innerHTML})}})}
async function loadFaqs(){loading.value=true;try{faqs.value=await request(`${apiBase}/faq`)}catch(e){showToast(e.message)}finally{loading.value=false}}
async function uploadFiles(event){const files=Array.from(event.target.files||[]);if(!files.length)return;if(form.value.attachments.length+files.length>20){showToast('一个问题最多上传 20 个附件');event.target.value='';return}uploading.value=true;try{for(const file of files){const fd=new FormData();fd.append('file',file);try{const data=await request(`${apiBase}/upload/attachment`,{method:'POST',body:fd});form.value.attachments.push(data)}catch(e){showToast(`${file.name}：${e.message}`)}}}finally{uploading.value=false;event.target.value=''}}
function removeFile(index){form.value.attachments.splice(index,1)}
function reset(){form.value={category:'',question:'',answer:'',keywords:'',enabled:true,images:[],attachments:[]};if(quill)quill.setText('')}
async function save(){form.value.answer=quill?.root.innerHTML||form.value.answer;if(!form.value.category.trim())return showToast('请填写分类');if(!form.value.question.trim())return showToast('请填写问题标题');if(!form.value.answer||form.value.answer==='<p><br></p>')return showToast('请填写解决方案');saving.value=true;try{const data=await request(`${apiBase}/processor/faqs`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(form.value)});showToast(`问题 #${data.id} 已录入问题库`,'success');reset();await loadFaqs()}catch(e){showToast(e.message)}finally{saving.value=false}}
onMounted(async()=>{initEditor();await loadFaqs()})
</script>

<template>
  <section class="knowledge-layout">
    <div class="panel knowledge-list">
      <div class="title"><div><h2>问题库</h2><p>查看现有标准方案，避免重复录入。</p></div><span>{{faqs.length}} 条</span></div>
      <div v-if="loading" class="empty">正在加载…</div>
      <article v-for="item in faqs" :key="item.id">
        <span>{{item.category}}</span>
        <div><b>{{item.question}}</b><small>{{item.keywords||'暂无关键词'}}</small></div>
      </article>
    </div>

    <div class="panel editor-panel">
      <div class="title"><div><h2>录入问题库</h2><p>把已验证、可复用的处理经验沉淀为标准方案。</p></div></div>
      <label>分类 <em>*</em><input v-model="form.category" placeholder="视频 / APP / 数据上传 / 网络" /></label>
      <label>问题标题 <em>*</em><input v-model="form.question" placeholder="客户会怎么描述这个问题？" /></label>
      <label>解决方案 <em>*</em></label>
      <div ref="editorEl" class="editor"></div>
      <label>搜索关键词<input v-model="form.keywords" placeholder="多个关键词可用空格分隔" /></label>
      <div class="attachment-box">
        <div><strong>附件</strong><small>可上传截图、日志、Excel、Word、PDF、压缩包和视频。</small></div>
        <label class="upload">{{uploading?'上传中…':'选择附件'}}<input type="file" multiple @change="uploadFiles" /></label>
      </div>
      <div v-if="form.attachments.length" class="files"><div v-for="(f,i) in form.attachments" :key="f.url"><span>{{f.name}} <small>{{fileSize(f.size)}}</small></span><button @click="removeFile(i)">移除</button></div></div>
      <div class="actions"><button class="primary" :disabled="saving||uploading" @click="save">{{saving?'保存中…':'录入问题库'}}</button><button @click="reset">清空</button></div>
      <p class="governance">处理人员可以新增问题库内容；已有问题的修改和删除仍由管理员负责，避免误覆盖标准方案。</p>
    </div>
  </section>
</template>

<style scoped>
.knowledge-layout{display:grid;grid-template-columns:.85fr 1.15fr;gap:16px}.panel{background:#fff;border:1px solid #e0e7ef;border-radius:18px;padding:22px}.title{display:flex;justify-content:space-between;gap:16px;align-items:flex-start}.title h2{margin:0}.title p{margin:5px 0 0;color:#7c8999}.title>span{color:#7c8999}.knowledge-list article{display:grid;grid-template-columns:auto 1fr;gap:12px;padding:15px 0;border-top:1px solid #edf1f5}.knowledge-list article>span{background:#edf3ff;color:#245eea;border-radius:999px;padding:5px 9px;font-size:12px;height:max-content}.knowledge-list article div{display:grid;gap:5px}.knowledge-list article small{color:#8794a5}.editor-panel{display:grid;gap:14px}label{display:grid;gap:7px;font-weight:600}em{color:#d92d20;font-style:normal}input{border:1px solid #d7dfeb;border-radius:9px;padding:11px}.editor{min-height:260px;background:#fff}.attachment-box{display:flex;justify-content:space-between;gap:16px;align-items:center;border:1px dashed #cbd6e4;border-radius:12px;padding:14px;background:#fafcff}.attachment-box>div{display:grid;gap:4px}.attachment-box small,.governance{color:#8190a3}.upload{background:#245eea;color:#fff;padding:9px 12px;border-radius:9px;cursor:pointer}.upload input{display:none}.files{display:grid;gap:8px}.files>div{display:flex;justify-content:space-between;gap:12px;padding:9px 11px;border:1px solid #e2e8f0;border-radius:9px}.files button{border:0;background:#fff0f0;color:#b42318;border-radius:8px;padding:6px 9px}.actions{display:flex;gap:9px}.actions button{border:0;border-radius:9px;padding:10px 14px}.primary{background:#245eea;color:#fff}.empty{padding:24px 0;color:#8794a5}@media(max-width:900px){.knowledge-layout{grid-template-columns:1fr}.attachment-box{display:grid}}
</style>
