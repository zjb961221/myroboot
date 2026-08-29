<script setup>
import { onMounted, ref } from 'vue'
import { apiErrorMessage, showToast } from './uiFeedback'

const apiBase = import.meta.env.VITE_API_BASE || '/api'
const token = localStorage.getItem('support_token') || ''
const username = localStorage.getItem('support_username') || ''
const profile = JSON.parse(localStorage.getItem('support_profile') || '{}')
const tickets = ref([])
const loading = ref(false)

function headers() { return { Authorization:`Bearer ${token}` } }
function statusText(v){return {pending:'待处理',processing:'处理中',resolved:'已解决',cancelled:'已撤销'}[v]||v}
function time(v){return v?String(v).replace('T',' ').slice(0,19):'-'}
async function load(){loading.value=true;try{const r=await fetch(`${apiBase}/processor/tickets`,{headers:headers()});if(r.status===401){localStorage.clear();location.href='/';return}if(!r.ok)throw new Error(await apiErrorMessage(r));tickets.value=await r.json()}catch(e){showToast(e.message)}finally{loading.value=false}}
async function logout(){try{await fetch(`${apiBase}/auth/logout`,{method:'POST',headers:headers()})}catch{}localStorage.clear();location.href='/'}
onMounted(load)
</script>

<template>
  <main class="page">
    <header>
      <div><span>MYROBOOT PROCESSOR</span><h1>工单处理工作台</h1><p>这里只展示管理员分配给你的工单。</p></div>
      <div class="user"><div><b>{{profile.displayName||username}}</b><small>处理人员</small></div><button @click="logout">退出</button></div>
    </header>
    <section class="summary">
      <div><b>{{tickets.filter(x=>x.status==='processing'||x.status==='pending').length}}</b><span>待处理</span></div>
      <div><b>{{tickets.filter(x=>x.status==='resolved').length}}</b><span>已解决</span></div>
      <button @click="load">{{loading?'刷新中…':'刷新工单'}}</button>
    </section>
    <section class="panel">
      <div class="title"><div><h2>分配给我的工单</h2><p>按处理中、待处理优先排序。</p></div><span>{{tickets.length}} 条</span></div>
      <div v-if="!tickets.length&&!loading" class="empty">当前没有分配给你的工单。</div>
      <article v-for="t in tickets" :key="t.id">
        <div class="main"><div class="top"><span class="status" :class="t.status">{{statusText(t.status)}}</span><b>#{{t.id}} · {{t.category}}</b></div><p>{{t.description}}</p><small>{{t.customer_name||'-'}} / {{t.mine_name||'-'}} · 分配时间 {{time(t.assigned_time)}}</small></div>
        <a :href="`/processor/ticket-detail?id=${t.id}`">{{t.status==='resolved'?'查看详情':'进入处理'}}</a>
      </article>
    </section>
  </main>
</template>

<style scoped>
.page{max-width:1100px;margin:auto;padding:34px 22px 70px;background:#f4f7fb;min-height:100vh;color:#26364a}header{background:#10243e;color:#fff;border-radius:22px;padding:30px;display:flex;justify-content:space-between;align-items:center;gap:20px}header span{font-size:12px;letter-spacing:1.4px;opacity:.7}header h1{margin:8px 0}header p{margin:0;color:#c6d2e1}.user{display:flex;gap:14px;align-items:center}.user>div{display:grid;text-align:right}.user small{opacity:.65}.user button,.summary button{border:0;border-radius:9px;padding:10px 14px}.summary{display:grid;grid-template-columns:180px 180px 1fr;gap:12px;margin-top:16px}.summary>div,.summary button{background:#fff;border:1px solid #e0e7ef;border-radius:14px;padding:16px}.summary>div{display:grid}.summary b{font-size:26px;color:#245eea}.summary span{color:#7a889a}.summary button{justify-self:end;align-self:center}.panel{margin-top:16px;background:#fff;border:1px solid #e0e7ef;border-radius:18px;padding:22px}.title{display:flex;justify-content:space-between;gap:16px;align-items:center}.title h2{margin:0}.title p{color:#7c8999;margin:5px 0 0}.title>span{color:#7c8999}.panel article{display:flex;justify-content:space-between;gap:18px;align-items:center;padding:18px 0;border-top:1px solid #edf1f5}.main{min-width:0}.top{display:flex;gap:9px;align-items:center}.main p{margin:9px 0;color:#47586d;display:-webkit-box;-webkit-box-orient:vertical;-webkit-line-clamp:2;overflow:hidden}.main small{color:#8794a5}.status{font-size:12px;border-radius:999px;padding:4px 8px;background:#eef2f7}.status.processing{background:#eaf2ff;color:#245eea}.status.resolved{background:#eaf8ef;color:#147a3d}.status.cancelled{background:#f1f2f4;color:#657080}.panel article>a{flex:0 0 auto;background:#245eea;color:#fff;text-decoration:none;padding:10px 14px;border-radius:9px}.empty{padding:35px 0;text-align:center;color:#8794a5}@media(max-width:700px){header,.panel article,.title{display:grid}.user{justify-content:space-between}.user>div{text-align:left}.summary{grid-template-columns:1fr 1fr}.summary button{grid-column:1/-1;justify-self:stretch}.panel article>a{text-align:center}}
</style>
