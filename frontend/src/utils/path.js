function stripLeadingSlash(path) {
  return `${path || ''}`.replace(/^\/+/, '')
}

const UI_MARKER = '/chen/'

function normalizeBasePath(path) {
  const normalized = `/${stripLeadingSlash(path)}`.replace(/\/+$/, '')
  return normalized === '/' ? '' : normalized
}

function normalizeUiBase(path) {
  const normalized = `/${stripLeadingSlash(path)}`.replace(/\/+$/, '')
  return normalized === '/' ? '/' : `${normalized}/`
}

function joinPath(basePath, path) {
  const normalizedBasePath = `${basePath || ''}`.replace(/\/+$/, '')
  const normalizedPath = stripLeadingSlash(path)

  if (!normalizedPath) {
    return normalizedBasePath || '/'
  }
  if (!normalizedBasePath) {
    return `/${normalizedPath}`
  }
  return `${normalizedBasePath}/${normalizedPath}`
}

function buildRuntimePathInfo(pathname) {
  const normalizedPathname = `${pathname || '/'}`.replace(/\/+$/, '') + '/'
  const markerIndex = normalizedPathname.indexOf(UI_MARKER)

  if (markerIndex < 0) {
    return { basePath: '', uiBase: UI_MARKER }
  }

  const basePath = markerIndex > 0
    ? normalizedPathname.slice(0, markerIndex).replace(/\/+$/, '')
    : ''

  return {
    basePath,
    uiBase: `${basePath}${UI_MARKER}`
  }
}

function getRuntimePathInfo() {
  if (typeof window === 'undefined') {
    return { basePath: '', uiBase: '/' }
  }

  const basePath = normalizeBasePath(window.__BASE_PATH__)
  const uiBase = normalizeUiBase(window.__CHEN_BASE__)

  if (basePath || uiBase !== '/') {
    return { basePath, uiBase }
  }
  return buildRuntimePathInfo(window.location.pathname)
}

export function getBasePath() {
  return getRuntimePathInfo().basePath
}

export function getAppBase() {
  return getRuntimePathInfo().uiBase
}

export function baseUrl(path = '') {
  return joinPath(getBasePath(), path)
}

export function appUrl(path = '') {
  return joinPath(getAppBase(), path)
}

export function apiUrl(path = '') {
  return baseUrl(`api/${stripLeadingSlash(path)}`)
}

export function appApiUrl(path = '') {
  return appUrl(`api/${stripLeadingSlash(path)}`)
}

export function wsUrl(path = '') {
  const scheme = document.location.protocol === 'https:' ? 'wss' : 'ws'
  return `${scheme}://${window.location.host}${appUrl(path)}`
}

export function appWsUrl(path = '') {
  return wsUrl(`ws/${stripLeadingSlash(path)}`)
}
