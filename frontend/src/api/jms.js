import { get, post } from '@/request'

export function getSnippets() {
  return get(`/api/v1/ops/adhocs/`)
}

export function saveSnippet(item) {
  return post(`/api/v1/ops/adhocs/`, item)
}
