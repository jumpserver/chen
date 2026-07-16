import { get, post } from '@/request'
import { apiUrl } from '@/utils/path'

export function getSnippets() {
  return get(apiUrl('v1/ops/adhocs/'))
}

export function saveSnippet(item) {
  return post(apiUrl('v1/ops/adhocs/'), item)
}
