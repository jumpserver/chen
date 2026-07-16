import { post } from '@/request'
import { appApiUrl } from '@/utils/path'

export function getResourceTreeChildren(parent, force) {
  let url = appApiUrl('resources/children')
  if (force) {
    url += '?force=true'
  }
  return post(url, parent)
}

export function getActions(node) {
  return post(appApiUrl('resources/actions'), node)
}

export function doAction(node, action) {
  return post(appApiUrl('resources/actions/do'), { node: node, action: action })
}

export function submitResourceForm(form) {
  return post(appApiUrl('resources/forms'), form)
}

export function getHints(nodeKey, context) {
  return post(appApiUrl('resources/hints'), { nodeKey: nodeKey, context: context })
}
