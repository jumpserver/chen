export const MESSAGES = {
  PONG: 'PONG',
  PING: 'PING',
  CLOSE: 'CLOSE',
  CONNECTED: 'CONNECTED',
  KEYBOARDEVENT: 'KEYBOARDEVENT',
  MOUSEEVENT: 'MOUSEEVENT'
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
    }
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
