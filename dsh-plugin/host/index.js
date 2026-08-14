// DSH IDE Bridge - host half (permanent mount).
// Receives code context from the IntelliJ plugin via POST /ide/context,
// holds it as a draft (FIFO + TTL), and hands it to the browser client
// through GET /ide/peek polling. GET /ide/health reports liveness.
// Wire protocol: see the project DESIGN.md (section 5).

const MAX_BODY = 512 * 1024
const MAX_CODE = 120 * 1024
const TTL_MS = 10 * 60 * 1000
const TOKEN = null // set a string to require the X-DSH-IDE-Token header on POST

export const name = 'dsh-ide-bridge'
export const inject = ['webServer', 'timer']

export function apply(ctx) {
  const drafts = []
  let seq = 0

  function send(res, status, payload) {
    const body = JSON.stringify(payload)
    res.writeHead(status, {
      'Content-Type': 'application/json',
      'Content-Length': new TextEncoder().encode(body).length,
    })
    res.end(body)
  }

  function isLoopback(req) {
    const addr = req.socket && req.socket.remoteAddress
    return addr === '127.0.0.1' || addr === '::1' || addr === '::ffff:127.0.0.1'
  }

  function readBody(req, onDone, onTooLarge) {
    const chunks = []
    let size = 0
    let finished = false
    const finish = (fn) => { if (!finished) { finished = true; fn() } }
    req.on('data', (chunk) => {
      if (finished) return
      size += chunk.length
      if (size > MAX_BODY) finish(onTooLarge)
      else chunks.push(chunk)
    })
    req.on('end', () => {
      if (finished) return
      finish(() => {
        let total = 0
        for (const c of chunks) total += c.length
        const merged = new Uint8Array(total)
        let offset = 0
        for (const c of chunks) { merged.set(c, offset); offset += c.length }
        onDone(new TextDecoder().decode(merged))
      })
    })
    req.on('error', () => finish(() => onDone(null)))
  }

  function validate(payload) {
    if (payload === null || typeof payload !== 'object' || Array.isArray(payload)) return { code: 'invalid-payload', message: 'payload must be an object' }
    if (payload.version !== 1) return { code: 'invalid-payload', message: 'unsupported version' }
    const sel = payload.selection
    if (sel === null || typeof sel !== 'object' || Array.isArray(sel)) return { code: 'invalid-payload', message: 'selection is required' }
    const KINDS = ['selection', 'class', 'method', 'file', 'declaration']
    if (typeof sel.kind !== 'string' || KINDS.indexOf(sel.kind) === -1) return { code: 'invalid-payload', message: 'unsupported selection kind' }
    if (typeof sel.startLine === 'number' && (!Number.isSafeInteger(sel.startLine) || sel.startLine < 1)) return { code: 'invalid-payload', message: 'invalid startLine' }
    if (typeof sel.endLine === 'number' && (!Number.isSafeInteger(sel.endLine) || sel.endLine < sel.startLine)) return { code: 'invalid-payload', message: 'invalid endLine' }
    if (typeof payload.code !== 'string') return { code: 'invalid-payload', message: 'code must be a string' }
    if (payload.code.length > MAX_CODE) return { code: 'payload-too-large', message: 'code exceeds 120 KB limit' }
    if (sel.kind === 'selection' && payload.code.trim().length === 0) return { code: 'invalid-payload', message: 'selection kind requires non-empty code' }
    if (payload.question !== undefined && typeof payload.question !== 'string') return { code: 'invalid-payload', message: 'question must be a string' }
    return null
  }

  function joinPath(base, rel) {
    if (typeof rel !== 'string' || rel === '') return base
    if (/^[a-zA-Z]:[\\/]/.test(rel) || rel.indexOf('/') === 0 || rel.indexOf('\\') === 0) return rel
    const sep = base.indexOf('\\') !== -1 && base.indexOf('/') === -1 ? '\\' : '/'
    return base.replace(/[\\/]+$/, '') + sep + rel.replace(/^[\\/]+/, '')
  }

  function buildDraft(payload) {
    const project = (payload.project !== null && typeof payload.project === 'object' && !Array.isArray(payload.project)) ? payload.project : {}
    const file = (payload.file !== null && typeof payload.file === 'object' && !Array.isArray(payload.file)) ? payload.file : {}
    const sel = payload.selection
    const sym = (payload.symbol !== null && typeof payload.symbol === 'object' && !Array.isArray(payload.symbol)) ? payload.symbol : null
    const meta = []
    if (typeof project.name === 'string' && project.name !== '') meta.push(project.name)
    const base = typeof project.basePath === 'string' ? project.basePath : ''
    const rel = typeof file.path === 'string' ? file.path : ''
    const displayPath = base !== '' ? joinPath(base, rel) : rel
    if (displayPath !== '') meta.push(displayPath)
    if (typeof file.language === 'string' && file.language !== '') meta.push(file.language)
    if (typeof sel.startLine === 'number') meta.push('第 ' + sel.startLine + '–' + (typeof sel.endLine === 'number' ? sel.endLine : sel.startLine) + ' 行')
    if (sym !== null && typeof sym.name === 'string') {
      const kind = typeof sym.kind === 'string' ? sym.kind : ''
      meta.push((kind === '' ? '' : kind + ' ') + sym.name)
    }
    const header = '[来自 IntelliJ IDEA]' + (meta.length > 0 ? ' ' + meta.join(' · ') : '')
    let text = header
    const hasCode = typeof payload.code === 'string' && payload.code.trim() !== ''
    if (hasCode) {
      const lang = typeof file.language === 'string' && file.language !== '' ? file.language.toLowerCase() : ''
      text += '\n\n```' + lang + '\n' + payload.code + '\n```'
    }
    if (typeof payload.question === 'string' && payload.question.trim() !== '') {
      text += '\n\n问题：' + payload.question.trim()
    }
    return text
  }

  function takeDraft(sessionId) {
    const now = Date.now()
    while (drafts.length > 0) {
      const draft = drafts.shift()
      if (draft.expiresAt > now) {
        console.log('dsh-ide-bridge: delivered draft ' + draft.id + ' to session ' + String(sessionId ?? '?'))
        return draft
      }
    }
    return null
  }

  ctx.effect(() => ctx.webServer.register({
    kind: 'exact',
    path: '/ide/context',
    handler: (req, res) => {
      if (req.method !== 'POST') { send(res, 405, { ok: false, code: 'method-not-allowed', message: 'POST only' }); return }
      if (!isLoopback(req)) { send(res, 403, { ok: false, code: 'forbidden', message: 'loopback only' }); return }
      if (TOKEN !== null && req.headers['x-dsh-ide-token'] !== TOKEN) { send(res, 401, { ok: false, code: 'bad-token', message: 'invalid token' }); return }
      readBody(req, (body) => {
        if (body === null) { send(res, 400, { ok: false, code: 'invalid-payload', message: 'unreadable body' }); return }
        let payload = null
        try { payload = JSON.parse(body) } catch (_) { send(res, 400, { ok: false, code: 'invalid-payload', message: 'body is not JSON' }); return }
        const problem = validate(payload)
        if (problem !== null) { send(res, problem.code === 'payload-too-large' ? 413 : 400, { ok: false, code: problem.code, message: problem.message }); return }
        seq += 1
        const id = 'ide-' + Date.now() + '-' + seq
        drafts.push({ id, text: buildDraft(payload), expiresAt: Date.now() + TTL_MS })
        console.log('dsh-ide-bridge: accepted draft ' + id + ' (' + payload.code.length + ' chars)')
        send(res, 200, { ok: true, status: 'accepted', draftId: id })
      }, () => send(res, 413, { ok: false, code: 'payload-too-large', message: 'request body exceeds 512 KB' }))
    },
  }), 'dsh-ide-bridge /ide/context route')

  ctx.effect(() => ctx.webServer.register({
    kind: 'exact',
    path: '/ide/peek',
    handler: (req, res) => {
      if (req.method !== 'GET' && req.method !== 'HEAD') { send(res, 405, { ok: false, code: 'method-not-allowed', message: 'GET only' }); return }
      if (!isLoopback(req)) { send(res, 403, { ok: false, code: 'forbidden', message: 'loopback only' }); return }
      const url = new URL(req.url ?? '/', 'http://x')
      const draft = takeDraft(url.searchParams.get('sessionId') ?? undefined)
      send(res, 200, draft === null ? null : { draftId: draft.id, text: draft.text })
    },
  }), 'dsh-ide-bridge /ide/peek route')

  ctx.effect(() => ctx.webServer.register({
    kind: 'exact',
    path: '/ide/health',
    handler: (req, res) => {
      if (!isLoopback(req)) { send(res, 403, { ok: false, code: 'forbidden', message: 'loopback only' }); return }
      send(res, 200, { ok: true, plugin: 'dsh-ide-bridge', version: '0.2.0', pendingDrafts: drafts.length })
    },
  }), 'dsh-ide-bridge /ide/health route')

  ctx.interval(() => {
    const now = Date.now()
    for (let i = drafts.length - 1; i >= 0; i -= 1) {
      if (drafts[i].expiresAt <= now) drafts.splice(i, 1)
    }
  }, 60000)
}
