export function friendlyMessage(input, fallback = '操作失败，请稍后重试') {
  if (!input) return fallback
  let text = typeof input === 'string' ? input : input.message || String(input)
  text = text.trim()
  if (!text) return fallback
  try {
    const parsed = JSON.parse(text)
    if (parsed?.message) return parsed.message
    if (parsed?.error) return parsed.error
  } catch {}
  if (text.includes('Failed to fetch')) return '无法连接服务器，请检查网络后重试'
  if (text.includes('NetworkError')) return '网络连接异常，请稍后重试'
  if (text.length > 240 || text.includes('Exception') || text.includes('org.springframework')) return fallback
  return text
}

export function showToast(message, type = 'error') {
  const text = friendlyMessage(message)
  let host = document.getElementById('app-toast-host')
  if (!host) {
    host = document.createElement('div')
    host.id = 'app-toast-host'
    host.className = 'app-toast-host'
    document.body.appendChild(host)
  }
  const item = document.createElement('div')
  item.className = `app-toast ${type}`
  item.innerHTML = `<span class="app-toast-icon">${type === 'success' ? '✓' : type === 'warning' ? '!' : '×'}</span><div><strong>${type === 'success' ? '操作成功' : type === 'warning' ? '请注意' : '操作未完成'}</strong><p></p></div>`
  item.querySelector('p').textContent = text
  host.appendChild(item)
  requestAnimationFrame(() => item.classList.add('show'))
  setTimeout(() => {
    item.classList.remove('show')
    setTimeout(() => item.remove(), 220)
  }, type === 'error' ? 5200 : 3600)
}

export async function apiErrorMessage(response, fallback = '操作失败，请稍后重试') {
  try {
    const data = await response.clone().json()
    const message = friendlyMessage(data?.message || data?.error, fallback)
    const requestId = data?.requestId || response.headers.get('X-Request-Id')
    return requestId && requestId !== '-' && !message.includes(requestId)
      ? `${message}（请求编号：${requestId}）`
      : message
  } catch {
    try {
      const message = friendlyMessage(await response.text(), fallback)
      const requestId = response.headers.get('X-Request-Id')
      return requestId ? `${message}（请求编号：${requestId}）` : message
    } catch {
      return fallback
    }
  }
}

export function installGlobalFeedback() {
  window.alert = (message) => showToast(message, 'error')
}
