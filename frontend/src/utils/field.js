export function parseFieldType(t) {
  // if (t.indexOf("char") !== -1) {
  //     return ""
  // }
  if (t.indexOf('int') !== -1) {
    return 'numeric'
  }
  // if (t.indexOf("datetime") !== -1) {
  //     return "time"
  // }
  return 'text'
}

export function getUrlParams(url) {
  if (url.indexOf('?') === -1) {
    return {}
  }
  const urlStr = url.split('?')[1]
  const obj = {}
  const paramsArr = urlStr.split('&')
  for (let i = 0, len = paramsArr.length; i < len; i++) {
    const arr = paramsArr[i].split('=')
    obj[arr[0]] = arr[1]
  }
  return obj
}

