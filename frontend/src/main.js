import { createApp } from 'vue'
import App from './App.vue'
import Login from './Login.vue'
import Register from './Register.vue'
import './style.css'

const path = window.location.pathname
const token = localStorage.getItem('support_token')

if (path === '/register') {
  createApp(Register).mount('#app')
} else if (!token) {
  createApp(Login).mount('#app')
} else {
  createApp(App).mount('#app')
}
