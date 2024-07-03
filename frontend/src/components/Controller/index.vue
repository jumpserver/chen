<template>
  <el-dialog
      ref="dialog"
      :title="dialogOptions.title"
      :show-close="dialogOptions.showClose"
      :close-on-click-modal="false"
      :visible.sync="dialogVisible"
  >
    <div
        v-if="dialogOptions.body && dialogOptions.bodyType==='html'"
        v-html="dialogOptions.body"
    />
    <div v-else v-text="dialogOptions.body"/>

    <span v-if="dialogOptions && dialogOptions.buttons" slot="footer" class="dialog-footer" style="text-align: center">
      <el-button
          v-for="(item,index) in dialogOptions.buttons"
          :key="index"
          @click="onDialogEvent(item.event)"
      >{{ item.label }}</el-button>
    </span>
  </el-dialog>
</template>

<script>
import { getUrlParams } from '@/utils/field'
import { LunaEvent, MESSAGES } from '@/utils/luna'
import { index } from '@/request'

export default {
  name: 'Controller',
  data() {
    return {
      heartBeatInterval: 0,
      lunaEvent: new LunaEvent(),
      dialogVisible: false,
      ws: null,
      dialogOptions: {
        title: '',
        showClose: false,
        width: '30%',
        bodyType: 'text',
        body: ''
      }
    }
  },
  mounted() {
    this.lunaEvent.init()
    this.auth()
    this.handleRenewLunaSession()
  },
  methods: {
    handleRenewLunaSession() {
      const lunaEvent = this.lunaEvent
      document.body.addEventListener('keydown', function() {
        lunaEvent.sendEventToLuna(MESSAGES.KEYBOARDEVENT)
      })
      document.body.addEventListener('keyup', function() {
        lunaEvent.sendEventToLuna(MESSAGES.KEYBOARDEVENT)
      })
      document.body.addEventListener('click', function() {
        lunaEvent.sendEventToLuna(MESSAGES.MOUSEEVENT)
      })
      document.body.addEventListener('dblclick', function() {
        lunaEvent.sendEventToLuna(MESSAGES.MOUSEEVENT)
      })
    },

    auth() {
      const params = getUrlParams(window.location.href)
      if (!Object.hasOwn(params, 'token')) {
        // 提示没有 token
        this.dialogOptions.title = 'Error'
        this.dialogOptions.body = 'No token found in url'
        this.dialogOptions.showClose = false
        this.dialogVisible = true
        return
      }
      this.$store.dispatch('app/auth', params).then((token) => {
        this.initWs(token)
      })
    },
    initWs(token) {
      const scheme = document.location.protocol === 'https:' ? 'wss' : 'ws'
      const ws = new WebSocket(`${scheme}://${window.location.host}/chen/ws/session`, token)
      ws.onmessage = (msg) => {
        const m = JSON.parse(msg.data)
        this.handlePacket(m)
      }
      ws.onopen = () => {
      }
      ws.onclose = () => {
        clearInterval(this.heartBeatInterval)
        this.onCloseSession()
      }
      this.ws = ws
    },
    showDialog() {
      this.dialogVisible = true
    },
    handlePacket(pkt) {
      switch (pkt.type) {
        case 'show_dialog':
          this.dialogOptions = pkt.data
          this.showDialog()
          break
        case 'show_message':
          this.$message({
            message: pkt.data.message,
            type: pkt.data.level.toLowerCase(),
            duration: pkt.data.duration * 1000,
            center: false
          })
          break
        case 'set_ready':
          this.onReady()
          break
        case 'close_dialog':
          this.dialogVisible = false
          break
        case 'close_session':
          this.onCloseSession()
          break
        case 'download':
          this.onDownloadFile(pkt.data)
          break
      }
    },
    async onDownloadFile(fileName) {
      const response = await index.get(`/chen/api/console/export/${fileName}`, {
        responseType: 'blob'
      })
      const blob = new Blob([response.data], { type: response.headers['content-type'] })

      const downloadUrl = window.URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = downloadUrl
      link.setAttribute('download', fileName)
      document.body.appendChild(link)
      link.click()
      link.remove()

      window.URL.revokeObjectURL(downloadUrl)
    },

    onDialogEvent(event) {
      this.sendPacket('dialog_event', event)
    },
    sendPacket(type, data) {
      const msg = { type: type, data: data }
      this.ws.send(JSON.stringify(msg))
    },
    onReady() {
      this.$store.dispatch('app/loadProfile').then(() => {
        this.status = 'connected'
        this.dialogVisible = false
        this.startHeartBeat()
        this.$emit('ready', true)
      })
    },
    startHeartBeat() {
      this.heartBeatInterval = setInterval(() => {
        this.ws.send(JSON.stringify({
          type: 'ping'
        }))
      }, 1000 * 10)
    },
    onCloseSession() {
      this.$emit('close')
      this.lunaEvent.sendEventToLuna(MESSAGES.CLOSE)
    }
  }
}
</script>

<style scoped>
::v-deep .el-dialog__header {
  padding: 10px 20px 10px;
  border-bottom: #7f7f7f 1px solid;
}

::v-deep .el-dialog__body {
  padding: 30px 10px 16px;
  color: #BBBBBB;
  font-size: 14px;
  word-break: break-all;
}

::v-deep .el-dialog .el-dialog__body {
  padding: 30px 10px 16px;
}

::v-deep .el-dialog__footer {
  padding: 10px 20px 20px;
  text-align: center;
  box-sizing: border-box;
}

</style>
