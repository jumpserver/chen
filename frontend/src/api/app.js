import { get, post } from '@/request'

export function auth(token, disableAutoHash) {
  const body = { token: token, disableAutoHash: disableAutoHash }
  return post(`/chen/api/auth`, body)
}

export function getProfile() {
  return get('/chen/api/profile')
}
