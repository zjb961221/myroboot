<script setup>
import { computed,onMounted,ref } from 'vue'
import { apiErrorMessage, showToast } from './uiFeedback'
import { copyText } from './clipboard'

const apiBase=import.meta.env.VITE_API_BASE||'/api'
const token=localStorage.getItem('support_token')||''
const role=localStorage.getItem('support_role')||''
const id=new URLSearchParams(location.search).get('id')
const admin=computed(()=>role==='admin')
const processor=computed(()=>role==='processor')
const staff=computed(()=>admin.value||processor.value)
const ticket=ref(null),history=ref([]),note=ref(''),visible=ref(true),reason=ref(''),result=ref('')
const progressFiles=ref([]),receiptFiles=ref([]),uploading=ref(false),error=ref('')
const shares=ref([]),shareHours=ref(24),shareUrl=ref(''),sharing=ref(false)
const processors=ref([]),processorUserId=ref(''),assigning=ref(false)

function auth(extra={}){return {...extra,Authorization:`Bearer ${token}`}}
async function json(url,options={}){options.headers=auth(options.headers||{});const r=await fetch(url,options);if(r.status===401){localStorage.clear();location.href='/';throw new Error('登录已失效，请重新登录')}if(!r.ok)throw new Error(await apiErrorMessage(r));const t=await r.text();return t?JSON.parse(t):null}
function listUrl(){return admin.value?`${apiBase}/admin/tickets`:processor.value?`${apiBase}/processor/tickets`:`${apiBase}/tickets/mine`}
function historyUrl(){return processor.value?`${apiBase}/processor/tickets/${id}/history`:`${apiBase}/tickets/${id}/history`}
function staffPrefix(){return processor.value?'processor':'admin'}
async function loadShares(){if(processor.value)return;shares.value=await json(`${apiBase}/tickets/${id}/shares`)}
async function loadProcessors(){if(admin.value)processors.value=await json(`${apiBase}/admin/processors`)}
async function load(){error.value='';try{const list=await json(listUrl());ticket.value=list.find(x=>String(x.id)===String(id))||null;if(!ticket.value)throw new Error('工单不存在或无权查看');history.value=await json(historyUrl());reason.value=ticket.value.resolution_reason||'';result.value=ticket.value.resolution_result||'';await Promise.all([loadShares(),loadProcessors()])}catch(e){error.value=e.message}}
function targetRef(name){return name==='progress'?progressFiles:receiptFiles}
async function uploadFiles(event,targetName){const target=targetRef(targetName),files=Array.from(event.target.files||[]);if(!files.length)return;if(target.value.length+files.length>10){showToast('一次最多保留 10 个附件');event.target.value='';return}uploading.value=true;try{for(const file of files){const fd=new FormData();fd.append('file',file);try{target.value.push(await json(`${apiBase}/upload/attachment`,{method:'POST',body:fd}))}catch(e){showToast(`${file.name}：${e.message}`)}}}finally{uploading.value=false;event.target.value=''}}
function removeFile(name,index){targetRef(name).value.splice(index,1)}
async function add(){if(!note.value.trim())return showToast('请先填写处理记录');try{await json(`${apiBase}/${staffPrefix()}/tickets/${id}/history`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({content:note.value,visibleToCustomer:visible.value,attachments:progressFiles.value})});note.value='';progressFiles.value=[];await load();showToast('处理记录已保存','success')}catch(e){showToast(e.message)}}
async function resolve(){if(!reason.value.trim()||!result.value.trim())return showToast('请填写原因和处理结果');try{await json(`${apiBase}/${staffPrefix()}/tickets/${id}/resolve`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({resolutionReason:reason.value,resolutionResult:result.value,attachments:receiptFiles.value})});receiptFiles.value=[];await load();showToast('解决回执已保存','success')}catch(e){showToast(e.message)}}
async function assign(){if(!processorUserId.value)return showToast('请选择处理人员');assigning.value=true;try{const data=await json(`${apiBase}/admin/tickets/${id}/assignment`,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({processorUserId:Number(processorUserId.value)})});await load();showToast(data.mailSent?`已分配给 ${data.processorName}，通知邮件已发送`:`已分配给 ${data.processorName}，但邮件发送失败，请检查 SMTP 配置`,data.mailSent?'success':'warning')}catch(e){showToast(e.message)}finally{assigning.value=false}}
async function createShare(){sharing.value=true;shareUrl.value='';try{const data=await json(`${apiBase}/tickets/${id}/shares`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({hours:Number(shareHours.value)})});shareUrl.value=`${location.origin}/share/ticket#${data.token}`;await loadShares();try{await copyText(shareUrl.value);showToast('安全分享链接已生成并复制','success')}catch{showToast('分享链接已生成，请手动复制','success')}}catch(e){showToast(e.message)}finally{sharing.value=false}}
async function copyShare(){if(!shareUrl.value)return;try{await copyText(shareUrl.value);showToast('分享链接已复制','success')}catch{showToast('复制失败，请长按或选中链接手动复制')}}
async function revokeShare(item){if(!confirm('撤销后，已经发出去的这个分享链接会立即失效。确定撤销吗？'))return;try{await json(`${apiBase}/tickets/${id}/shares/${item.id}`,{method:'DELETE'});await loadShares();showToast('分享链接已撤销','success')}catch(e){showToast(e.message)}}
function actionText(v){return {progress:'处理记录',resolved:'已解决',created:'已提交',assigned:'已分配',cancelled:'已撤销',deleted:'已删除'}[v]||v}
function statusText(v){return {pending:'待处理',processing:'处理中',resolved:'已解决',cancelled:'已撤销'}[v]||v}
function time(v){return v?String(v).replace('T',' ').slice(0,19):'-'}
function size(v){const n=Number(v||0);if(n<1024)return `${n} B`;if(n<1048576)return `${(n/1024).toFixed(1)} KB`;return `${(n/1048576).toFixed(1)} MB`}
function isVideo(file){return String(file.content_type||'').startsWith('video/')||/\.(mp4|mov|avi|mkv|webm)$/i.test(file.original_name||file.name||'')}
function activeShare(item){if(Number(item.revoked)===1||item.revoked===true)return false;const d=new Date(String(item.expires_time||'').replace(' ','T'));return !Number.isNaN(d.getTime())&&d.getTime()>Date.now()}
function backUrl(){return admin.value?'/admin':processor.value?'/processor':'/'}
onMounted(load)
</script>

<template>
<main class="page">
  <header><div><span>{{processor?'MYROBOOT PROCESSOR':'MYROBOOT SUPPORT'}}</span><h1>工单 #{{id}} 处理详情</h1></div><a :href="backUrl()">返回</a></header>
  <div v-if="error" class="error">{{error}}</div>
  <section v-if="ticket" class="panel">
    <div class="head"><div><b>{{ticket.category||'技术问题'}}</b><small>{{time(ticket.create_time)}}</small></div><strong>{{statusText(ticket.status)}}</strong></div>
    <p class="desc">{{ticket.description}}</p>
    <div v-if="ticket.status==='cancelled'" class="cancelled-box"><b>该工单已撤销</b><span>{{ticket.cancel_reason||'客户主动撤销工单'}} · {{time(ticket.cancelled_time)}}</span></div>
    <a v-if="ticket.screenshot_url" :href="ticket.screenshot_url" target="_blank"><img :src="ticket.screenshot_url" class="shot"/></a>
    <div v-if="ticket.attachments?.length" class="attachments"><h3>客户提交附件</h3><a v-for="file in ticket.attachments" :key="file.id" :href="file.file_url" target="_blank"><span>{{file.original_name}}</span><small>{{size(file.file_size)}}</small></a></div>
  </section>

  <section v-if="admin&&ticket" class="panel assignment">
    <div><h2>工单分配</h2><p>选择处理人员后，系统立即记录分配动作，并向其账号邮箱发送待办通知。</p></div>
    <div class="assign-row"><select v-model="processorUserId"><option value="">请选择处理人员</option><option v-for="p in processors" :key="p.id" :value="p.id">{{p.display_name||p.username}} · {{p.email}}</option></select><button class="primary" :disabled="assigning||ticket.status==='resolved'||ticket.status==='cancelled'" @click="assign">{{assigning?'分配中…':'分配并发送邮件'}}</button></div>
    <small v-if="!processors.length">还没有启用的处理人员账号，请先到“用户管理”中创建角色为“处理人员”的账号并填写真实邮箱。</small>
  </section>

  <section v-if="ticket&&!processor" class="panel share-panel"><div class="share-title"><div><h2>安全分享</h2><p>生成临时外链，仅展示问题内容、客户截图和客户原始附件。链接可随时撤销。</p></div><div class="share-create"><select v-model="shareHours"><option :value="1">1 小时</option><option :value="24">24 小时</option><option :value="72">3 天</option><option :value="168">7 天</option><option :value="720">30 天</option></select><button class="primary" :disabled="sharing" @click="createShare">{{sharing?'生成中…':'生成分享链接'}}</button></div></div><div v-if="shareUrl" class="new-share"><b>新分享链接（只显示这一次）</b><div><input :value="shareUrl" readonly @focus="$event.target.select()"/><button @click="copyShare">复制</button></div></div><div v-if="shares.length" class="share-list"><div v-for="item in shares" :key="item.id" class="share-item"><div><b>{{activeShare(item)?'有效':'已失效'}}</b><small>到期 {{time(item.expires_time)}} · 已访问 {{item.access_count||0}} 次</small></div><button v-if="activeShare(item)" @click="revokeShare(item)">撤销</button></div></div></section>

  <section v-if="ticket" class="panel"><h2>处理时间线</h2><div v-if="!history.length" class="empty">暂无处理记录</div><div v-for="item in history" :key="item.id" class="timeline"><i></i><div><div class="meta"><b>{{actionText(item.action_type)}}</b><span>{{item.operator_name||'系统'}} · {{time(item.create_time)}}</span></div><p>{{item.content}}</p><div v-if="item.attachments?.length" class="history-files"><template v-for="f in item.attachments" :key="f.id"><video v-if="isVideo(f)" :src="f.file_url" controls preload="metadata"></video><a :href="f.file_url" target="_blank">{{f.original_name}} <small>{{size(f.file_size)}}</small></a></template></div><small v-if="staff&&item.visible_to_customer===0">内部记录，仅客户不可见</small></div></div></section>

  <template v-if="staff&&ticket">
    <section v-if="ticket.status==='pending'||ticket.status==='processing'" class="panel"><h2>新增处理记录</h2><textarea v-model="note" rows="5" placeholder="记录排查过程、客户配合事项、当前进展"></textarea><label class="check"><input v-model="visible" type="checkbox"/> 对客户可见</label><label class="upload">处理附件<input type="file" multiple @change="uploadFiles($event,'progress')"/><small>支持图片、Office、PDF、压缩包、日志和视频</small></label><div v-if="progressFiles.length" class="pending-files"><div v-for="(f,i) in progressFiles" :key="f.url"><span>{{f.name}}</span><button @click="removeFile('progress',i)">移除</button></div></div><button :disabled="uploading" @click="add">保存处理记录</button></section>
    <section v-if="ticket.status!=='cancelled'&&(admin||ticket.status!=='resolved')" class="panel"><h2>{{admin&&ticket.status==='resolved'?'更新解决回执':'解决回执'}}</h2><label>具体原因<textarea v-model="reason" rows="4"/></label><label>处理结果<textarea v-model="result" rows="5"/></label><label class="upload">回执附件<input type="file" multiple @change="uploadFiles($event,'receipt')"/><small>可上传验收截图、Excel、PDF、Word、压缩包、操作视频等</small></label><div v-if="receiptFiles.length" class="pending-files"><div v-for="(f,i) in receiptFiles" :key="f.url"><span>{{f.name}}</span><button @click="removeFile('receipt',i)">移除</button></div></div><button class="primary" :disabled="uploading" @click="resolve">{{admin&&ticket.status==='resolved'?'更新回执':'保存并标记已解决'}}</button></section>
  </template>
</main>
</template>

<style scoped>
.page{max-width:900px;margin:auto;padding:34px 20px 70px;background:#f4f7fb;min-height:100vh;color:#26364a}header{background:#10243e;color:#fff;border-radius:20px;padding:28px;display:flex;justify-content:space-between;align-items:center}header span{font-size:12px;letter-spacing:1.4px;opacity:.7}header h1{margin:8px 0}header a{color:#fff;text-decoration:none;border:1px solid #ffffff40;padding:9px 13px;border-radius:8px}.panel{background:#fff;border:1px solid #e2e8f0;border-radius:16px;padding:22px;margin-top:16px}.head{display:flex;justify-content:space-between}.head small{display:block;color:#8391a3;margin-top:5px}.desc{white-space:pre-wrap;line-height:1.7}.cancelled-box{display:grid;gap:5px;margin:14px 0;padding:13px 15px;border-radius:10px;background:#f4f5f7;border:1px solid #e2e5e9;color:#596579}.shot{max-width:320px;max-height:240px;border-radius:10px;border:1px solid #e5eaf0}.attachments,.history-files{display:grid;gap:8px;margin-top:16px}.attachments h3{margin:0 0 3px}.attachments a,.history-files a{display:flex;justify-content:space-between;gap:15px;text-decoration:none;border:1px solid #e2e8f0;background:#f9fbfd;border-radius:9px;padding:10px 12px;color:#33465f}.history-files video{width:100%;max-height:360px;border-radius:10px;background:#111}.assignment{display:grid;gap:14px}.assignment h2{margin:0 0 5px}.assignment p,.assignment>small{margin:0;color:#7c899a;line-height:1.6}.assign-row{display:flex;gap:9px}.assign-row select{min-width:0;flex:1;border:1px solid #d7dfeb;border-radius:9px;padding:10px;background:#fff}.share-title{display:flex;justify-content:space-between;gap:18px}.share-title h2{margin:0 0 5px}.share-title p{margin:0;color:#7c899a;font-size:14px}.share-create,.new-share>div{display:flex;gap:8px}.share-create select,.new-share input{border:1px solid #d7dfeb;border-radius:9px;padding:9px;background:#fff}.new-share{margin-top:16px;padding:14px;border:1px solid #bcd0f6;background:#f5f8ff;border-radius:11px;display:grid;gap:8px}.new-share input{min-width:0;flex:1}.share-list{display:grid;gap:8px;margin-top:16px}.share-item{display:flex;justify-content:space-between;align-items:center;gap:15px;padding:11px 12px;border:1px solid #e3e8ef;border-radius:10px}.share-item>div{display:grid;gap:4px}.share-item small{color:#8491a2}.timeline{display:grid;grid-template-columns:18px 1fr;gap:12px;padding:12px 0}.timeline i{width:10px;height:10px;border-radius:50%;background:#245eea;margin-top:6px;box-shadow:0 0 0 5px #edf3ff}.timeline>div{border-bottom:1px solid #eef2f6;padding-bottom:14px}.meta{display:flex;justify-content:space-between;gap:15px}.meta span{color:#8795a7;font-size:13px}.timeline p{white-space:pre-wrap;line-height:1.7}.timeline>div>small{color:#a16a00}textarea{width:100%;border:1px solid #d7dfeb;border-radius:9px;padding:11px;box-sizing:border-box;margin:8px 0 12px}label{display:grid;gap:6px;margin-top:12px}.check{display:flex;align-items:center;gap:8px}.upload input{border:1px dashed #bccbe0;border-radius:10px;padding:12px;background:#f8faff}.upload small{color:#8593a6}.pending-files{display:grid;gap:8px;margin:12px 0}.pending-files>div{display:flex;justify-content:space-between;gap:12px;align-items:center;border:1px solid #e2e8f0;background:#f9fbfd;border-radius:9px;padding:9px 11px}.pending-files button{background:#fff0f0;color:#b42318;padding:6px 9px}button{border:0;border-radius:9px;padding:10px 15px;background:#eef2f7}.primary{background:#245eea;color:#fff}.error{margin-top:16px;padding:12px;background:#fff1f0;color:#b42318;border-radius:9px}.empty{color:#8795a7}@media(max-width:600px){header,.head,.meta,.share-title,.share-create,.new-share>div,.share-item,.assign-row{display:grid}.assign-row select,.assign-row button{width:100%}.attachments a,.history-files a{display:grid}}
</style>
