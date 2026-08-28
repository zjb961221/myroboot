import { createApp } from 'vue'
import App from './App.vue'
import Login from './Login.vue'
import Register from './Register.vue'
import AdminUsers from './AdminUsers.vue'
import AdminKnowledge from './AdminKnowledge.vue'
import TicketTimeline from './TicketTimeline.vue'
import CustomerPortal from './CustomerPortal.vue'
import { installGlobalFeedback } from './uiFeedback'
import './style.css'
import './ui-extra.css'

installGlobalFeedback()

const path = window.location.pathname
const token = localStorage.getItem('support_token')
const role = localStorage.getItem('support_role')

if (path === '/register') {
  createApp(Register).mount('#app')
} else if (!token) {
  createApp(Login).mount('#app')
} else if (path === '/admin/users/manage' && role === 'admin') {
  createApp(AdminUsers).mount('#app')
} else if (path === '/admin/knowledge' && role === 'admin') {
  createApp(AdminKnowledge).mount('#app')
} else if (path === '/ticket-detail' || path === '/admin/ticket-detail') {
  createApp(TicketTimeline).mount('#app')
} else if (path.startsWith('/admin')) {
  createApp(App).mount('#app')
  if (role === 'admin') {
    const link = document.createElement('a')
    link.href = '/admin/knowledge'
    link.textContent = '高级问题库管理'
    link.style.cssText = 'position:fixed;right:22px;bottom:22px;z-index:9998;background:#245eea;color:#fff;text-decoration:none;padding:11px 15px;border-radius:10px;box-shadow:0 12px 32px rgba(36,94,234,.25);font:14px system-ui'
    document.body.appendChild(link)
  }
} else {
  createApp(CustomerPortal).mount('#app')
}
