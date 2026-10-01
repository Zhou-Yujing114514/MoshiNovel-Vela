/**
 * 摩柿小说 · 业务层（唯一网络入口）
 * 页面只调用本文件，不直接碰 bridge/store；对外接口契约见 architecture.md §10.3。
 *
 * 网络策略（见 spec/network-reference.md）：
 *  - fetch 直连优先（手环连小米运动健康后 @system.fetch 可用）；
 *  - 直连不可用 / 网络 none / 调用失败 → 自动回退 interconnect 桥接；
 *  - Cookie 手动管理：从 Set-Cookie 正则提取 session，后续手动拼 Cookie 头。
 */
import bridge from './bridge.js'
import store from './store.js'
import fetch from '@system.fetch'
import network from '@system.network'
import file from '@system.file'

// 服务配置（label 用于 UI 展示）
const SERVICES = {
  moshi: { key: 'moshi', label: '摩柿' }
}

// 摩柿后端地址
var MOSHI_BASE = 'https://morax.kdns.fr'
// 桌面 Firefox UA（防后端风控）
var DESKTOP_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:129.0) Gecko/20100101 Firefox/129.0'
// 直连下载正文上限（字节）：超过则回退桥接分片（低内存）
var MAX_DIRECT_BODY = 512 * 1024
// 直连落盘分片大小
var CHUNK_SIZE = 4096
// 无网提示
var NETWORK_NONE_MSG = '手机未连接网络，请保持小米运动健康连接'

// 下载进度回调列表（直连路径与桥接分片流共用）
var dlProgressCbs = []

/**
 * 把 bridge 的 resolve/reject 统一成 {ok,...}：
 * 传输层 reject 的对象也当作失败结果 resolve 出来，便于上层直接上屏提示。
 */
function normalize(p) {
  return p.then(
    function (res) { return res },
    function (err) {
      return err || { ok: false, message: '网络异常，请检查手机桥接' }
    }
  )
}

/** 合并请求头（后者覆盖前者） */
function mergeHeaders(base, extra) {
  var out = {}
  var k
  for (k in base) { if (base.hasOwnProperty(k)) out[k] = base[k] }
  if (extra) { for (k in extra) { if (extra.hasOwnProperty(k)) out[k] = extra[k] } }
  return out
}

/** 默认请求头：必带 Accept-Encoding 置空（防 gzip 无法解压）+ 桌面 UA */
function defaultHeaders() {
  return {
    'Accept': '*/*',
    'Accept-Encoding': '',
    'Accept-Language': 'zh-CN,zh;q=0.8',
    'User-Agent': DESKTOP_UA
  }
}

/** 大小写不敏感取响应头 */
function pickHeader(headers, name) {
  if (!headers) return ''
  var lower = name.toLowerCase()
  var keys = Object.keys(headers)
  for (var i = 0; i < keys.length; i++) {
    if (keys[i].toLowerCase() === lower) return headers[keys[i]]
  }
  return ''
}

/** 从 Set-Cookie（String|Array）正则提取 session=([^;]+) */
function extractSession(setCookieRaw) {
  if (!setCookieRaw) return ''
  var arr = setCookieRaw
  if (typeof arr === 'string') arr = arr.split(', ')
  if (!arr || !arr.length) return ''
  for (var i = 0; i < arr.length; i++) {
    var m = (arr[i] || '').match(/session=([^;]+)/)
    if (m) return m[1]
  }
  return ''
}

/** 由 session 拼 Cookie 头 */
function cookieHeader(session) {
  return session ? ('session=' + session) : ''
}

/** 运行时是否支持直连 fetch（不依赖 canIUse 静态表：
 *  AstroBox 网桥 FetchBridge 等插件恢复的 @system.fetch 可能不在 canIUse 白名单里，
 *  这里直接探测模块是否可用，可用即直连，失败回退桥接。 */
function canDirectFetch() {
  try {
    return !!(fetch && typeof fetch.fetch === 'function')
  } catch (e) { return false }
}

/**
 * 探测网络类型：type==='none' 视为无网；getType 不可用则不阻塞（resolve false）。
 * @returns {Promise<boolean>} true=无网
 */
function networkIsNone() {
  return new Promise(function (resolve) {
    try {
      network.getType({
        success: function (data) { resolve(!!(data && data.type === 'none')) },
        fail: function () { resolve(false) }
      })
    } catch (e) { resolve(false) }
  })
}

/** 派发下载进度（直连路径用） */
function emitDlProgress(ev) {
  dlProgressCbs.forEach(function (cb) {
    try { cb(ev) } catch (e) { console.warn('onDownloadProgress 回调异常', e) }
  })
}

/* ----------------------- 底层 HTTP：两分支统一 ----------------------- */

/**
 * 直连 @system.fetch（Promise 包装）。
 * @returns {Promise<{ok,code,status,data,headers,message}>} 失败/超时 reject
 */
function directFetchRaw(url, method, data, header, responseType) {
  return new Promise(function (resolve, reject) {
    var settled = false
    var timer = setTimeout(function () {
      if (settled) return
      settled = true
      reject(new Error('fetch timeout'))
    }, 20000)

    function finish(err, res) {
      if (settled) return
      settled = true
      clearTimeout(timer)
      if (err) { reject(err); return }
      var status = res.statusCode || res.code || res.status || 0
      resolve({
        ok: status >= 200 && status < 300,
        code: 0,
        status: status,
        data: res.data,
        headers: res.headers || {},
        message: ''
      })
    }

    try {
      fetch.fetch({
        url: url,
        method: method,
        header: header,
        data: data,
        responseType: responseType,
        success: function (res) { finish(null, res) },
        fail: function (err) { finish(err || new Error('fetch fail')) }
      })
    } catch (e) { finish(e) }
  })
}

/** 桥接 'http' 动作兜底（phone 执行 HTTP）。 */
function bridgeHttpRaw(url, method, data, header) {
  return normalize(
    bridge.request('http', {
      method: method,
      url: url,
      headers: header,
      body: data == null ? '' : data
    }, { service: '' })
  ).then(function (res) {
    if (res && res.ok) {
      var p = res.payload || {}
      return {
        ok: true, code: 0, status: p.status || 0,
        data: p.body, headers: p.headers || {}, message: ''
      }
    }
    return {
      ok: false, code: (res && res.code) || -1, status: 0,
      data: '', headers: {}, message: (res && res.message) || '桥接请求失败'
    }
  })
}

/**
 * 统一传输函数：fetch 直连优先，失败/无网回退桥接。
 * @param {{url:string,method?:string,data?:string,header?:object,responseType?:string}} opts
 * @returns {Promise<{ok:boolean,code:number,status:number,data:any,headers:object,message:string}>}
 */
function httpFetch(opts) {
  var url = opts.url
  var method = (opts.method || 'GET').toUpperCase()
  var data = opts.data == null ? '' : opts.data
  var responseType = opts.responseType || 'text'
  var header = mergeHeaders(defaultHeaders(), opts.header)

  return networkIsNone().then(function (none) {
    if (canDirectFetch() && !none) {
      // 直连可用且有网 → 直连；任何失败回退桥接
      return directFetchRaw(url, method, data, header, responseType)
        .catch(function () { return bridgeHttpRaw(url, method, data, header) })
    }
    // 无网/无直连能力 → 直接桥接
    return bridgeHttpRaw(url, method, data, header).then(function (r) {
      // 无网时桥接若也失败，上屏无网中文提示
      if (none && !r.ok) r.message = NETWORK_NONE_MSG
      return r
    })
  })
}

/* --------------------------- 高层操作 --------------------------- */

/**
 * 登录：优先直连 POST /api/login（JSON），从 Set-Cookie 提取 session；
 * 任何失败（无直连/无网/超时/非200/无 session）回退 bridge 'login'。
 * @returns {Promise<{ok:boolean,session:string,message:string}>}
 */
function login(svc, username, password) {
  var body = JSON.stringify({ username: username, password: password })
  return httpFetch({
    url: MOSHI_BASE + '/api/login',
    method: 'POST',
    data: body,
    header: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
    responseType: 'text'
  }).then(function (r) {
    if (r.ok && r.status >= 200 && r.status < 300) {
      var session = extractSession(pickHeader(r.headers, 'Set-Cookie'))
      if (session) {
        return store.setSession(svc, session)
          .then(function () { return store.setActiveService(svc) })
          .then(function () { return { ok: true, session: session, message: '' } })
      }
    }
    // 直连未拿到 session → 桥接兜底
    return bridgeLogin(svc, username, password)
  }).catch(function () {
    return bridgeLogin(svc, username, password)
  })
}

/** bridge 'login' 兜底分支 */
function bridgeLogin(svc, username, password) {
  return normalize(
    bridge.request('login', { username: username, password: password }, { service: svc })
  ).then(function (res) {
    if (res && res.ok) {
      var session = (res.payload && res.payload.session) || ''
      return Promise.resolve()
        .then(function () { return store.setSession(svc, session) })
        .then(function () { return store.setActiveService(svc) })
        .then(function () { return { ok: true, session: session, message: res.message || '' } })
    }
    return { ok: false, session: '', message: (res && res.message) || '登录失败' }
  })
}

/** 书架条目字段映射为统一契约 {title,author,downloadUrl,taskId} */
function mapShelfItem(it) {
  it = it || {}
  return {
    title: it.title || '',
    author: it.author || '',
    downloadUrl: it.downloadUrl || it.download_url || it.url || '',
    taskId: it.taskId != null ? it.taskId : (it.id != null ? it.id : '')
  }
}

/**
 * 获取书架：优先直连 GET /api/bookshelf（带 Cookie）；失败回退 bridge 'shelf'。
 * @returns {Promise<{ok:boolean,items:Array,message:string}>}
 */
function fetchShelf(svc) {
  return store.getSession(svc).then(function (session) {
    var h = { 'Accept': 'application/json' }
    var ck = cookieHeader(session)
    if (ck) h['Cookie'] = ck
    return httpFetch({
      url: MOSHI_BASE + '/api/bookshelf',
      method: 'GET',
      header: h,
      responseType: 'text'
    }).then(function (r) {
      if (r.ok && r.status >= 200 && r.status < 300 && typeof r.data === 'string') {
        var parsed = null
        try { parsed = JSON.parse(r.data) } catch (e) { parsed = null }
        if (parsed && Array.isArray(parsed.items)) {
          return { ok: true, items: parsed.items.map(mapShelfItem), message: '' }
        }
      }
      return bridgeShelf(svc)
    }).catch(function () { return bridgeShelf(svc) })
  })
}

/** bridge 'shelf' 兜底分支 */
function bridgeShelf(svc) {
  return normalize(bridge.request('shelf', {}, { service: svc })).then(function (res) {
    if (res && res.ok) {
      return { ok: true, items: (res.payload && res.payload.items) || [], message: res.message || '' }
    }
    return { ok: false, items: [], message: (res && res.message) || '获取书架失败' }
  })
}

/**
 * 正文按 4KB 顺序写盘：首片覆盖写、后续 append:true。
 * @param {string} uri
 * @param {string} text
 * @returns {Promise<void>}
 */
function writeTextChunks(uri, text) {
  var i = 0
  function step() {
    if (i >= text.length) return Promise.resolve()
    var slice = text.substr(i, CHUNK_SIZE)
    var first = (i === 0)
    i += CHUNK_SIZE
    return new Promise(function (resolve, reject) {
      file.writeText({
        uri: uri,
        text: slice,
        append: !first,
        success: function () { resolve() },
        fail: function () { reject(new Error('write fail')) }
      })
    }).then(step)
  }
  return step()
}

/**
 * 直连下载尝试：GET {downloadUrl}（带 Cookie）。
 * 成功且正文 ≤512KB 则分片写盘；否则返回 {ok:false} 交由上层回退桥接。
 */
function tryDirectDownload(bookKey, uri, url, header) {
  emitDlProgress({ bookKey: bookKey, percent: 0, bytes: 0, total: 0 })
  return networkIsNone().then(function (none) {
    if (!canDirectFetch() || none) return { ok: false, reason: 'nocap' }
    return directFetchRaw(url, 'GET', '', header, 'text')
  }).then(function (r) {
    if (!r || !r.ok) return { ok: false, reason: 'fetch' }
    var body = typeof r.data === 'string' ? r.data : ''
    if (body.length > MAX_DIRECT_BODY) return { ok: false, reason: 'too_large' }
    return writeTextChunks(uri, body).then(function () {
      emitDlProgress({ bookKey: bookKey, percent: 100, bytes: body.length, total: body.length })
      return { ok: true, size: body.length }
    })
  }).catch(function () { return { ok: false, reason: 'error' } })
}

/** bridge 'download' 分片流兜底分支 */
function bridgeDownload(svc, book, bookKey, uri) {
  return normalize(
    bridge.request('download', {
      title: book.title,
      downloadUrl: book.downloadUrl,
      bookKey: bookKey
    }, { service: svc })
  ).then(function (res) {
    if (res && res.ok) {
      return store.setBookMeta(svc, book.taskId, {
        title: book.title,
        author: book.author,
        uri: uri,
        size: 0
      }).then(function () { return { ok: true, uri: uri, message: '' } })
    }
    return { ok: false, uri: '', message: (res && res.message) || '下载失败' }
  })
}

/**
 * 下载一本书：优先直连 GET {downloadUrl} 分片写盘；正文 >512KB 或失败回退 bridge 分片流。
 * @returns {Promise<{ok:boolean,uri:string,message:string}>}
 */
function downloadBook(svc, book) {
  var bookKey = svc + '_' + book.taskId
  var uri = 'internal://files/books/' + bookKey + '.txt'
  return store.getSession(svc).then(function (session) {
    var h = {}
    var ck = cookieHeader(session)
    if (ck) h['Cookie'] = ck
    return tryDirectDownload(bookKey, uri, book.downloadUrl, h)
  }).then(function (direct) {
    if (direct.ok) {
      return store.setBookMeta(svc, book.taskId, {
        title: book.title,
        author: book.author,
        uri: uri,
        size: direct.size || 0
      }).then(function () { return { ok: true, uri: uri, message: '' } })
    }
    // 直连失败/过大 → 桥接分片（低内存）
    return bridgeDownload(svc, book, bookKey, uri)
  })
}

/**
 * 注册下载进度回调（直连 0/100 与桥接分片流都经此派发）。
 * @param {Function} cb cb({bookKey, percent, bytes, total})
 */
function onDownloadProgress(cb) {
  dlProgressCbs.push(cb)
  bridge.onProgress(cb)
}

/**
 * 探测桥接是否可用（8s 超时）
 * @returns {Promise<{ok:boolean,message:string}>}
 */
function pingBridge() {
  return normalize(
    bridge.request('ping', {}, { service: '', timeoutMs: 8000 })
  ).then(function (res) {
    return {
      ok: !!(res && res.ok),
      message: (res && res.message) || ''
    }
  })
}

/**
 * 启动轻探活：只走 fetch 直连（AstroBox 网桥/原生 fetch），
 * 不触发信封桥接，失败静默——让网桥在打开 App 时就能识别到本应用。
 * @returns {Promise<boolean>} true=直连通路可用
 */
function lightProbe() {
  return new Promise(function (resolve) {
    if (!canDirectFetch()) { resolve(false); return }
    var done = false
    function finish(ok) { if (!done) { done = true; resolve(ok) } }
    try {
      fetch.fetch({
        url: MOSHI_BASE + '/',
        method: 'GET',
        header: defaultHeaders(),
        responseType: 'text',
        timeout: 5000,
        success: function () { finish(true) },
        fail: function () { finish(false) }
      })
    } catch (e) { finish(false) }
  })
}

/**
 * 通用 HTTP 请求（fetch 等价通道，两分支统一）。
 * @returns {Promise<{ok:boolean,status:number,headers:object,body:string,message:string}>}
 */
function httpRequest(method, url, headers, body) {
  return httpFetch({
    url: url,
    method: method,
    data: body || '',
    header: headers || {},
    responseType: 'text'
  }).then(function (r) {
    return {
      ok: r.ok,
      status: r.status,
      headers: r.headers,
      body: typeof r.data === 'string' ? r.data : (r.data == null ? '' : JSON.stringify(r.data)),
      message: r.message || ''
    }
  })
}

/* ------------------------ 导出 ------------------------ */

export default {
  SERVICES: SERVICES,
  login: login,
  fetchShelf: fetchShelf,
  downloadBook: downloadBook,
  onDownloadProgress: onDownloadProgress,
  pingBridge: pingBridge,
  lightProbe: lightProbe,
  httpRequest: httpRequest
}
