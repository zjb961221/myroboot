export async function copyText(text) {
  const value = String(text || '')
  if (!value) throw new Error('没有可复制的内容')

  if (window.isSecureContext && navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(value)
      return true
    } catch {
      // Fall through for browsers or permission policies that block Clipboard API.
    }
  }

  const textarea = document.createElement('textarea')
  textarea.value = value
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.left = '-9999px'
  textarea.style.top = '0'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)
  textarea.focus()
  textarea.select()
  textarea.setSelectionRange(0, value.length)
  const copied = document.execCommand('copy')
  textarea.remove()
  if (!copied) throw new Error('浏览器禁止复制')
  return true
}
