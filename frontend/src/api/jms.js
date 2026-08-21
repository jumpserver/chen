import { get, post } from '@/request'
import { apiUrl } from '@/utils/path'

export function getSnippets(params) {
  return get(apiUrl('v1/ops/adhocs/'), params)
}

export function saveSnippet(item) {
  return post(apiUrl('v1/ops/adhocs/'), item)
}
