import { createApp } from 'vue'
import App from './App.vue'
import Login from './Login.vue'
import Register from './Register.vue'
import AdminUsers from './AdminUsers.vue'
import TicketTimeline from './TicketTimeline.vue'
import './style.css'

const path = window.location.pathname
const token = localStorage.getItem('support_token')
const role = localStorage.getItem('support_role')

if (path === '/register') {
  createApp(Register).mount('#app')
} else if (!token) {
  createApp(Login).mount('#app')
} else if (path === '/admin/users/manage' && role === 'admin') {
  createApp(AdminUsers).mount('#app')
} else if (path === '/ticket-detail' || path === '/admin/ticket-detail') {
  createApp(TicketTimeline).mount('#app')
} else {
  createApp(App).mount('#app')
}
