<template>
  <div
    v-loading="state.loading"
    class="container"
  >
    <Message :subject="messageSubject" />
    <SplitPane :default-percent="40" :min-percent="20" split="horizontal">
      <template slot="paneL">
        <DataView
          v-if="viewMeta"
          ref="dataView"
          :data="data"
          :editable="true"
          :meta="viewMeta"
          :preview-before-save="true"
          :row-edit-actions-enabled="true"
          :message-subject="messageSubject"
          :state-subject="stateSubject"
          :tool-bar-items="toolBarItems"
          @action="onAction"
        />
      </template>
      <template slot="paneR">
        <el-tabs v-model="activeTab" type="card">
          <el-tab-pane
            :closable="false"
            name="log"
          >
            <span slot="label">
              <i class="el-icon-tickets" />
              {{ $tc('LogOutput') }}
            </span>
            <Log :subject="logSubject" style="padding: 5px" />
          </el-tab-pane>
        </el-tabs>
      </template>
    </SplitPane>
  </div>
</template>

<script>
import store from '@/store'
import DataView from '@/components/Main/Explore/DataView/DataView.vue'
import Log from '@/components/Main/Explore/QueryConsole/Log.vue'
import { Subject } from 'rxjs'
import SplitPane from 'vue-splitpane'
import Message from '@/components/Main/Explore/Message.vue'
import { appWsUrl } from '@/utils/path'

export default {
  components: {
    Message,
    Log,
    DataView,
    SplitPane
  },
  props: {
    tab: {},
    nodeKey: {
      type: String,
      default: ''
    }
  },
  data() {
    return {
      heartBeatInterval: 0,
      state: {
        loading: false
      },
      viewMeta: null,
      data: null,
      activeTab: 'log',
      logSubject: new Subject(),
      messageSubject: new Subject(),
      stateSubject: new Subject(),
      toolBarItems: {
        // addRow: {
        //   split: true,
        //   type: 'button',
        //   icon: 'iconfont icon-chen-plus',
        //   onClick: () => {
        //   }
        // },
        // deleteRow: {
        //   type: 'button',
        //   icon: 'iconfont icon-chen-minus'
        // }
      }
    }
  },
  mounted() {
    this.initWs()
  },
  beforeDestroy() {
    clearInterval(this.heartBeatInterval)
    this.ws.close()
  },
  methods: {
    initWs() {
      const token = store.getters.token
      const ws = new WebSocket(appWsUrl('console'), token)
      ws.onmessage = (e) => {
        const msg = JSON.parse(e.data)
        this.handleWSMessage(msg)
      }
      ws.onopen = () => {
        const connect = {
          type: 'connect',
          data: {
            nodeKey: this.nodeKey,
            type: 'data_view'
          }
        }
        this.ws.send(JSON.stringify(connect))
      }
      this.ws = ws
    },
    handleWSMessage(pkt) {
      switch (pkt.type) {
        case 'pong':
          break
        case 'init':
          this.tab.title = pkt.data.title
          this.tab.loading = false
          this.startHeartBeat()
          break
        case 'log':
          this.logSubject.next(pkt.data)
          break
        case 'new_data_view':
          this.viewMeta = pkt.data
          break
        case 'update_data_view':
          if (!this.$refs.dataView || this.$refs.dataView.acceptDataResponse()) {
            this.data = pkt.data.data
          }
          break
        case 'save_changes_result':
          this.handleSaveChangesResult(pkt.data)
          break
        case 'save_changes_preview_result':
          this.handleSaveChangesPreviewResult(pkt.data)
          break
        case 'message':
          this.messageSubject.next(pkt.data)
          break
        case 'update_state':
          if (pkt.data.title === this.tab.title) {
            this.state = pkt.data
          } else {
            this.stateSubject.next(pkt.data)
          }
          break
        case 'active_console':
          this.$emit('changeTab', pkt.data)
          break
        case 'close':
          this.$emit('close', this.tab.name, true, false)
      }
    },
    startHeartBeat() {
      this.heartBeatInterval = setInterval(() => {
        this.ws.send(JSON.stringify({
          type: 'ping'
        }))
      }, 1000 * 10)
    },
    onAction(action) {
      if (this.shouldGuardDirty(action) && this.$refs.dataView && this.$refs.dataView.hasDirty()) {
        this.$confirm('There are unsaved changes. Discard them and continue?', 'Warning', {
          confirmButtonText: 'Confirm',
          cancelButtonText: 'Cancel',
          type: 'warning'
        }).then(() => {
          this.$refs.dataView.clearDirty()
          this.sendDataViewAction(action)
        }).catch(() => {
          this.$refs.dataView.cancelClientRequest(action.clientRequestSequence)
        })
        return
      }
      this.sendDataViewAction(action)
    },
    sendDataViewAction(action) {
      const request = { ...action }
      delete request.clientRequestSequence
      this.ws.send(JSON.stringify({ type: 'data_view_action', data: request }))
    },
    shouldGuardDirty(action) {
      return ['first_page', 'prev_page', 'next_page', 'last_page', 'refresh', 'change_limit'].includes(action.action)
    },
    hasDirty() {
      return !!(this.$refs.dataView && this.$refs.dataView.hasDirty())
    },
    clearDirty() {
      if (this.$refs.dataView && typeof this.$refs.dataView.clearDirty === 'function') {
        this.$refs.dataView.clearDirty()
      }
    },
    handleSaveChangesResult(result) {
      if (this.$refs.dataView && typeof this.$refs.dataView.handleSaveChangesResult === 'function') {
        this.$refs.dataView.handleSaveChangesResult(result)
      }
    },
    handleSaveChangesPreviewResult(result) {
      if (this.$refs.dataView && typeof this.$refs.dataView.handleSaveChangesPreviewResult === 'function') {
        this.$refs.dataView.handleSaveChangesPreviewResult(result)
      }
    }
  }
}
</script>

<style scoped>
.container {
  text-align: left;
  height: calc(100vh - 30px);
}

.content {
  height: 100%;
}

.message {
  height: 22px;
}
</style>
