import { post } from '@/request'

export function getResourceTreeChildren(parent, force) {
  let url = `/chen/api/resources/children`
  if (force) {
    url += '?force=true'
  }
  return post(url, parent)
}

export function getActions(node) {
  return post(`/chen/api/resources/actions`, node)
}

export function doAction(node, action) {
  return post(`/chen/api/resources/actions/do`, { node: node, action: action })
}

export function submitResourceForm(form) {
  return post(`/chen/api/resources/forms`, form)
}

export function getHints(nodeKey, context) {
  return post(`/chen/api/resources/hints`, { nodeKey: nodeKey, context: context })
}
