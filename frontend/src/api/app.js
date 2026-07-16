import { get, post } from '@/request'
import { appApiUrl } from '@/utils/path'

export function auth(token, disableAutoHash) {
  const body = { token: token, disableAutoHash: disableAutoHash }
  return post(appApiUrl('auth'), body)
}

export function getProfile() {
  return get(appApiUrl('profile'))
}
