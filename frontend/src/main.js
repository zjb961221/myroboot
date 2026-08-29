import { createApp } from 'vue'
import App from './App.vue'
import Login from './Login.vue'
import Register from './Register.vue'
import AdminUsers from './AdminUsers.vue'
import AdminKnowledge from './AdminKnowledge.vue'
import TicketTimeline from './TicketTimeline.vue'
import TicketShare from './TicketShare.vue'
import FaqShare from './FaqShare.vue'
import CustomerPortal from './CustomerPortal.vue'
import { installGlobalFeedback } from './uiFeedback'
import './style.css'
import './ui-extra.css'

installGlobalFeedback()

const path = window.location.pathname
const token = localStorage.getItem('support_token')
const role = localStorage.getItem('support_role')

if (path === '/share/ticket') {
  createApp(TicketShare).mount('#app')
} else if (path === '/share/faq') {
  createApp(FaqShare).mount('#app')
} else if (path === '/register') {
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
} else {
  createApp(CustomerPortal).mount('#app')
}
