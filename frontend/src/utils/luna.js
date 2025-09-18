export const MESSAGES = {
  PONG: 'PONG',
  PING: 'PING',
  CLOSE: 'CLOSE',
  CONNECTED: 'CONNECTED',
  KEYEVENT: 'KEYEVENT',
  MOUSEEVENT: 'MOUSEEVENT',
  INPUT_ACTIVE: 'INPUT_ACTIVE'
}

export class LunaEvent {
  init() {
    window.addEventListener('message',
      this.handleEventFromLuna.bind(this),
      false)
  }

  handleEventFromLuna(event) {
    const msg = event.data
    switch (msg.name) {
      case MESSAGES.PING:
        if (this.lunaId != null) {
          return
        }
        this.lunaId = msg.id
        this.origin = event.origin
        this.sendEventToLuna(MESSAGES.PONG)
        break
      case MESSAGES.INPUT_ACTIVE:
        this.inputActiveHandler && this.inputActiveHandler()
        break
    }
  }

  setInputActiveHandler(func) {
    this.inputActiveHandler = func
  }

  sendEventToLuna(name, data) {
    data == null && (data = '')
    if (this.lunaId != null) {
      const msg = { name: name, id: this.lunaId, data: data }
      window.parent.postMessage(msg, this.origin)
      console.log('Chen send post message: ', msg)
    }
  }
}
