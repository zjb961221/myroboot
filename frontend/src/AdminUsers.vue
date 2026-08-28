<script setup>
import { onMounted, ref } from 'vue'
import { apiErrorMessage, showToast } from './uiFeedback'

const apiBase = import.meta.env.VITE_API_BASE || '/api'
const token = localStorage.getItem('support_token') || ''
const users = ref([])
const editing = ref(null)
const importing = ref(false)
const importResult = ref(null)
const form = ref({ username:'', email:'', password:'', displayName:'', mineName:'', phone:'', role:'customer', enabled:true })
const error = ref('')

function headers() {
  return { 'Content-Type':'application/json', Authorization:`Bearer ${token}` }
}

async function load() {
  const r = await fetch(`${apiBase}/admin/users`, { headers:{ Authorization:`Bearer ${token}` } })
  if (!r.ok) throw new Error(await apiErrorMessage(r, '用户列表加载失败'))
  users.value = await r.json()
}

function reset() {
  editing.value = null
  form.value = { username:'', email:'', password:'', displayName:'', mineName:'', phone:'', role:'customer', enabled:true }
  error.value = ''
}

function edit(u) {
  editing.value = u.id
  form.value = {
    username:u.username,
    email:u.email || '',
    password:'',
    displayName:u.display_name || '',
    mineName:u.mine_name || '',
    phone:u.phone || '',
    role:u.role || 'customer',
    enabled:u.enabled === 1 || u.enabled === true
  }
}

function validate() {
  const f = form.value
  if (!f.mineName.trim()) return '请填写煤矿名称'
  if (!f.displayName.trim()) return '请填写姓名'
  if (!f.phone.trim()) return '请填写手机'
  if (!f.email.trim()) return '请填写邮箱'
  if (!f.username.trim()) return '请填写账号'
  if (!editing.value && !f.password) return '请填写初始密码'
  return ''
}

async function save() {
  error.value = ''
  const msg = validate()
  if (msg) { error.value = msg; return }
  try {
    const r = await fetch(editing.value ? `${apiBase}/admin/users/${editing.value}` : `${apiBase}/admin/users`, {
      method: editing.value ? 'PUT' : 'POST',
      headers: headers(),
      body: JSON.stringify(form.value)
    })
    if (!r.ok) throw new Error(await apiErrorMessage(r, '用户保存失败'))
    showToast(editing.value ? '用户资料已更新' : '用户已创建', 'success')
    reset()
    await load()
  } catch (e) {
    error.value = e.message || '用户保存失败'
  }
}

async function importUsers(event) {
  const file = event.target.files?.[0]
  if (!file) return
  importing.value = true
  importResult.value = null
  try {
    const fd = new FormData()
    fd.append('file', file)
    const r = await fetch(`${apiBase}/admin/users/import`, {
      method: 'POST',
      headers: { Authorization:`Bearer ${token}` },
      body: fd
    })
    if (!r.ok) throw new Error(await apiErrorMessage(r, '用户导入失败'))
    importResult.value = await r.json()
    if (importResult.value.errors?.length) {
      showToast('部分数据未导入，请查看错误明细', 'warning')
    } else {
      showToast(`导入完成：新增 ${importResult.value.created}，更新 ${importResult.value.updated}`, 'success')
    }
    await load()
  } catch (e) {
    showToast(e.message || '用户导入失败')
  } finally {
    importing.value = false
    event.target.value = ''
  }
}

onMounted(() => load().catch(e => showToast(e.message)))
</script>

<template>
  <main class="page">
    <header>
      <div><span>MYROBOOT ADMIN</span><h1>用户维护</h1><p>单个新增、批量导入或修改账号。煤矿名称、姓名、手机、邮箱、账号、密码为模板必填项。</p></div>
      <a href="/admin">返回管理后台</a>
    </header>

    <section class="import-panel panel">
      <div>
        <h2>Excel 批量导入</h2>
        <p>模板固定为：煤矿名称、姓名、手机、邮箱、账号、密码。六列全部必填。</p>
      </div>
      <div class="import-actions">
        <a class="download" href="/api/templates/mine-users.xlsx" download="煤矿用户导入模板.xlsx">下载 Excel 模板</a>
        <label class="upload">{{ importing ? '导入中...' : '选择 Excel 导入' }}<input type="file" accept=".xlsx,.xls" :disabled="importing" @change="importUsers" /></label>
      </div>
      <div v-if="importResult" class="result">
        <strong>导入结果：新增 {{ importResult.created }}，更新 {{ importResult.updated }}</strong>
        <div v-if="importResult.errors?.length" class="errors"><div v-for="item in importResult.errors" :key="item">{{ item }}</div></div>
      </div>
    </section>

    <section class="layout">
      <div class="panel">
        <h2>{{ editing ? '编辑用户' : '新增用户' }}</h2>
        <div class="grid">
          <label>煤矿名称 <em>*</em><input v-model="form.mineName" /></label>
          <label>姓名 <em>*</em><input v-model="form.displayName" /></label>
          <label>手机 <em>*</em><input v-model="form.phone" /></label>
          <label>邮箱 <em>*</em><input v-model="form.email" type="email" /></label>
          <label>账号 <em>*</em><input v-model="form.username" /></label>
          <label>{{ editing ? '新密码（留空不修改）' : '密码 *' }}<input v-model="form.password" type="password" /></label>
          <label>角色<select v-model="form.role"><option value="customer">客户</option><option value="admin">管理员</option></select></label>
        </div>
        <label class="check"><input v-model="form.enabled" type="checkbox" /> 启用账号</label>
        <div v-if="error" class="error"><strong>请检查填写内容</strong><span>{{ error }}</span></div>
        <div class="actions"><button class="primary" @click="save">保存</button><button v-if="editing" @click="reset">取消</button></div>
      </div>

      <div class="panel list">
        <h2>现有用户</h2>
        <table>
          <thead><tr><th>账号</th><th>邮箱</th><th>姓名</th><th>煤矿</th><th>手机</th><th>角色</th><th></th></tr></thead>
          <tbody><tr v-for="u in users" :key="u.id"><td>{{u.username}}</td><td>{{u.email||'-'}}</td><td>{{u.display_name||'-'}}</td><td>{{u.mine_name||'-'}}</td><td>{{u.phone||'-'}}</td><td>{{u.role}}</td><td><button @click="edit(u)">编辑</button></td></tr></tbody>
        </table>
      </div>
    </section>
  </main>
</template>

<style scoped>
.page{max-width:1200px;margin:auto;padding:36px 22px;background:#f4f7fb;min-height:100vh}header{background:#10243e;color:#fff;border-radius:22px;padding:30px;display:flex;justify-content:space-between;align-items:center}header span{font-size:12px;letter-spacing:1.4px;opacity:.7}header h1{margin:8px 0}header p{color:#c8d4e3}header a{color:#fff;text-decoration:none;border:1px solid #ffffff40;padding:10px 14px;border-radius:9px}.panel{background:#fff;border:1px solid #e3e9f1;border-radius:16px;padding:22px;overflow:auto}.import-panel{margin-top:18px;display:grid;grid-template-columns:1fr auto;gap:18px;align-items:center}.import-panel h2{margin:0 0 6px}.import-panel p{margin:0;color:#718096}.import-actions{display:flex;gap:10px;align-items:center}.download,.upload{border-radius:9px;padding:10px 14px;text-decoration:none;font-weight:700;cursor:pointer}.download{background:#245eea;color:#fff}.upload{background:#eef3ff;color:#245eea}.upload input{display:none}.result{grid-column:1/-1;background:#f7f9fc;border-radius:10px;padding:12px}.errors{margin-top:8px;color:#b42318;display:grid;gap:4px;font-size:14px}.layout{display:grid;grid-template-columns:.8fr 1.2fr;gap:18px;margin-top:18px}.grid{display:grid;grid-template-columns:1fr 1fr;gap:14px}.grid label{display:grid;gap:7px;font-weight:600}.grid label em{color:#d92d20;font-style:normal}.grid input,.grid select{border:1px solid #d8e0eb;border-radius:9px;padding:10px}.check{display:flex;gap:8px;margin-top:15px}.actions{display:flex;gap:9px;margin-top:18px}.actions button,td button{border:0;border-radius:8px;padding:9px 13px}.primary{background:#245eea;color:#fff}.error{margin-top:12px;color:#b42318;background:#fff4f2;border:1px solid #fecdca;padding:11px 12px;border-radius:9px;display:grid;gap:3px}.error span{font-size:14px}table{width:100%;border-collapse:collapse;min-width:700px}th,td{text-align:left;padding:11px;border-top:1px solid #edf1f5}th{color:#718096;font-size:13px}@media(max-width:800px){.layout,.grid,.import-panel{grid-template-columns:1fr}.import-actions{align-items:stretch;flex-direction:column}.download,.upload{text-align:center}header{display:block}header a{display:inline-block;margin-top:10px}}
</style>
