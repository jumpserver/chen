function cloneValue(value) {
  if (value instanceof Date) {
    return new Date(value.getTime())
  }
  if (Array.isArray(value)) {
    return value.map((item) => cloneValue(item))
  }
  if (value && typeof value === 'object') {
    return Object.keys(value).reduce((result, key) => {
      result[key] = cloneValue(value[key])
      return result
    }, {})
  }
  return value
}

function freezeValue(value) {
  if (!value || typeof value !== 'object' || Object.isFrozen(value)) {
    return value
  }
  Object.keys(value).forEach((key) => freezeValue(value[key]))
  return Object.freeze(value)
}

class DataViewRequestState {
  constructor() {
    this.dirtyVersion = 0
    this.requestSequence = 0
    this.activeRequest = null
  }

  markDirty() {
    this.dirtyVersion += 1
    return this.dirtyVersion
  }

  begin(kind, payload) {
    if (this.activeRequest) {
      return null
    }
    this.requestSequence += 1
    this.activeRequest = {
      sequence: this.requestSequence,
      kind,
      dirtyVersion: this.dirtyVersion,
      payload: payload === undefined ? null : freezeValue(cloneValue(payload))
    }
    return this.activeRequest
  }

  transition(sequence, expectedKind, nextKind) {
    if (!this.isCurrent(sequence, expectedKind) || !this.hasCurrentDirtyVersion(sequence)) {
      return null
    }
    this.requestSequence += 1
    this.activeRequest = {
      ...this.activeRequest,
      sequence: this.requestSequence,
      kind: nextKind
    }
    return this.activeRequest
  }

  setKind(sequence, expectedKind, nextKind) {
    if (!this.isCurrent(sequence, expectedKind)) {
      return null
    }
    this.activeRequest = {
      ...this.activeRequest,
      kind: nextKind
    }
    return this.activeRequest
  }

  isCurrent(sequence, kind) {
    return !!this.activeRequest &&
      this.activeRequest.sequence === sequence &&
      (!kind || this.activeRequest.kind === kind)
  }

  hasCurrentDirtyVersion(sequence) {
    return !!this.activeRequest &&
      this.activeRequest.sequence === sequence &&
      this.activeRequest.dirtyVersion === this.dirtyVersion
  }

  finish(sequence, kind) {
    if (!this.isCurrent(sequence, kind)) {
      return false
    }
    this.activeRequest = null
    return true
  }
}

module.exports = {
  DataViewRequestState,
  cloneValue,
  freezeValue
}
