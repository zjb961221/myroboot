<script setup>
import { ref } from 'vue'
import { apiErrorMessage } from './uiFeedback'
const apiBase = import.meta.env.VITE_API_BASE || '/api'
const form = ref({ username:'', email:'', code:'', password:'', confirmPassword:'', displayName:'', companyName:'', mineName:'', phone:'' })
const message = ref(''); const error = ref(''); const sending = ref(false); const registering = ref(false); const countdown = ref(0)
function startCountdown(){ countdown.value=60; const timer=setInterval(()=>{countdown.value--; if(countdown.value<=0) clearInterval(timer)},1000) }
async function sendCode(){
  error.value=''; message.value=''; if(!form.value.email.trim()) return error.value='请先填写邮箱'
  sending.value=true
  try{ const res=await fetch(`${apiBase}/auth/register/code`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({email:form.value.email.trim()})}); if(!res.ok) throw new Error(await apiErrorMessage(res,'验证码发送失败，请稍后重试')); message.value='验证码已发送，请检查邮箱'; startCountdown() }
  catch(e){ error.value=e.message || '验证码发送失败，请稍后重试' } finally{ sending.value=false }
}
async function register(){
  error.value=''; message.value='';
  if(!form.value.username.trim()) return error.value='请填写用户名'
  if(!form.value.displayName.trim()) return error.value='请填写姓名'
  if(!form.value.email.trim()) return error.value='请填写邮箱'
  if(!form.value.code.trim()) return error.value='请填写邮箱验证码'
  if(!form.value.companyName.trim()) return error.value='请填写单位'
  if(!form.value.mineName.trim()) return error.value='请填写矿井'
  if(!form.value.phone.trim()) return error.value='请填写手机号'
  if(!form.value.password) return error.value='请填写密码'
  if(form.value.password.length<8) return error.value='密码至少需要 8 位'
  if(!form.value.confirmPassword) return error.value='请再次输入密码'
  if(form.value.password!==form.value.confirmPassword) return error.value='两次输入的密码不一致'
  registering.value=true
  try{
    const body={...form.value}; delete body.confirmPassword
    const res=await fetch(`${apiBase}/auth/register`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}); if(!res.ok) throw new Error(await apiErrorMessage(res,'注册失败，请检查填写内容'))
    message.value='注册成功，正在返回登录页'; setTimeout(()=>window.location.href='/',900)
  }catch(e){ error.value=e.message || '注册失败，请检查填写内容' } finally{ registering.value=false }
}
</script>
<template>
  <main class="register-page"><section class="register-card">
    <div class="top"><div><span class="badge">MYROBOOT SUPPORT</span><h1>创建客户账号</h1><p>所有信息均为必填项。使用邮箱验证码完成注册后，可查询问题库、提交工单并查看处理回执。</p></div><a href="/">返回登录</a></div>
    <div class="grid">
      <label>用户名 <em>*</em><input v-model="form.username" required placeholder="3-50 个字符" /></label>
      <label>姓名 <em>*</em><input v-model="form.displayName" required placeholder="联系人姓名" /></label>
      <label>邮箱 <em>*</em><div class="code-row"><input v-model="form.email" required type="email" placeholder="name@example.com" /><button :disabled="sending||countdown>0" @click="sendCode">{{ countdown>0?`${countdown}s`:sending?'发送中':'发送验证码' }}</button></div></label>
      <label>验证码 <em>*</em><input v-model="form.code" required maxlength="6" placeholder="6 位验证码" /></label>
      <label>单位 <em>*</em><input v-model="form.companyName" required placeholder="公司/单位名称" /></label>
      <label>矿井 <em>*</em><input v-model="form.mineName" required placeholder="矿井名称" /></label>
      <label>手机号 <em>*</em><input v-model="form.phone" required inputmode="tel" placeholder="联系电话" /></label><span></span>
      <label>密码 <em>*</em><input v-model="form.password" required type="password" placeholder="至少 8 位" /></label>
      <label>确认密码 <em>*</em><input v-model="form.confirmPassword" required type="password" placeholder="再次输入密码" /></label>
    </div>
    <div v-if="error" class="error"><strong>无法完成注册</strong><span>{{ error }}</span></div><div v-if="message" class="message">{{ message }}</div>
    <button class="submit" :disabled="registering" @click="register">{{ registering?'注册中...':'注册并进入平台' }}</button>
  </section></main>
</template>
<style scoped>
.register-page{min-height:100vh;padding:42px 20px;background:#f4f7fb}.register-card{max-width:900px;margin:auto;background:#fff;border:1px solid #e1e8f0;border-radius:22px;padding:34px;box-shadow:0 20px 60px rgba(20,40,70,.07)}.top{display:flex;justify-content:space-between;gap:20px;align-items:flex-start;margin-bottom:28px}.badge{font-size:12px;letter-spacing:1.4px;color:#245eea}.top h1{font-size:32px;margin:10px 0}.top p{color:#718096;line-height:1.7;max-width:650px}.top a{color:#245eea;text-decoration:none;white-space:nowrap}.grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}.grid label{display:grid;gap:8px;font-weight:600;color:#34455d}.grid label em{color:#d92d20;font-style:normal}.grid input{width:100%;border:1px solid #d7dfeb;border-radius:10px;padding:12px;outline:none}.grid input:focus{border-color:#245eea}.code-row{display:flex;gap:8px}.code-row input{flex:1}.code-row button,.submit{border:0;border-radius:10px;background:#245eea;color:#fff;padding:0 16px;white-space:nowrap}.code-row button:disabled,.submit:disabled{opacity:.55}.submit{margin-top:22px;padding:13px 22px}.error,.message{margin-top:16px;padding:12px 14px;border-radius:10px}.error{background:#fff4f2;border:1px solid #fecdca;color:#b42318;display:grid;gap:3px}.error span{font-size:14px}.message{background:#edf9f2;color:#18794e}@media(max-width:720px){.grid{grid-template-columns:1fr}.grid>span{display:none}.top{display:block}.top a{display:inline-block;margin-top:10px}.register-card{padding:24px}.code-row{align-items:stretch}.code-row button{padding:0 12px}}
</style>
