import axios from 'axios'
import store from '@/store'
import { Message } from 'element-ui'
// import { Message } from 'element-ui'

const instance = axios.create({
  withCredentials: true,
  timeout: 60000 // request timeout, default 1 min
})

export const index = instance

index.interceptors.request.use((config) => {
  if (store.getters.authenticated) {
    config.headers['token'] = store.getters.token
  }
  return config
})

index.interceptors.response.use((resp) => {
  return resp
}, (error) => {
  const reader = new FileReader()
  reader.onload = function(event) {
    const text = event.target.result
    Message.error(text)
  }
  reader.readAsText(error.response.data)
  return Promise.reject(error)
})

const promise = (request, loading = {}) => {
  return new Promise((resolve, reject) => {
    loading.status = true
    request.then(response => {
      resolve(response.data)
      loading.status = false
    }).catch(error => {
      reject(error)
      loading.status = false
    })
  })
}

export const get = (url, data, loading) => {
  return promise(index({ url: url, method: 'get', params: data }), loading)
}

export const post = (url, data, loading) => {
  return promise(index({ url: url, method: 'post', data }), loading)
}

export const put = (url, data, loading) => {
  return promise(index({ url: url, method: 'put', data }), loading)
}

export const del = (url, loading) => {
  return promise(index({ url: url, method: 'delete' }), loading)
}

export const patch = (url, data, headers, loading) => {
  if (headers) {
    return promise(index({ url: url, headers: headers, method: 'patch', data }),
      loading)
  }
  return promise(index({ url: url, method: 'patch', data }), loading)
}

export default {
  install(Vue) {
    Vue.prototype.$get = get
    Vue.prototype.$post = post
    Vue.prototype.$put = put
    Vue.prototype.$delete = del
    Vue.prototype.$request = index
  }
}
