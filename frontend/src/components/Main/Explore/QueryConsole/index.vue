<template>
  <div v-loading="state.loading" class="container">
    <div class="content">
      <SplitPane :default-percent="40" :min-percent="20" split="horizontal">
        <template slot="paneL">
          <CodeEditor
            :node-key="nodeKey"
            :state="state"
            :subjects="subjects"
            @action="onEditorAction"
          />
        </template>
        <template slot="paneR">
          <div class="">
            <div class="message">
              <Message :subject="subjects.messageSubject" />
            </div>
            <ResultBar
              ref="resultBar"
              :subjects="subjects"
              @closeDataView="onCloseDataView"
              @dataViewAction="onDataViewAction"
              @limitChange="onLimitChange"
            />
          </div>
        </template>
      </SplitPane>
    </div>
  </div>
</template>

<script>
import CodeEditor from '@/components/Main/Explore/QueryConsole/CodeEditor.vue'
import ResultBar from '@/components/Main/Explore/QueryConsole/ResultBar.vue'
import store from '@/store'
import { Subject } from 'rxjs'
import Message from '@/components/Main/Explore/Message.vue'
import SplitPane from 'vue-splitpane'
import { appWsUrl } from '@/utils/path'

export default {
  components: { Message, ResultBar, CodeEditor, SplitPane },
  props: {
    tab: {
      type: Object,
      default: () => {}
    },
    nodeKey: {
      type: String,
      default: ''
    },
    globalMessageSubject: {
      type: Subject,
      default: () => new Subject()
    }
  },
  data() {
    return {
      heartBeatInterval: 0,
      ws: null,
      pendingEditorActions: [],
      editorDirtyGuardOpen: false,
      state: {
        loading: false,
        inQuery: false,
        currentContext: '',
        contexts: [],
        timeout: 0
      },
      subjects: {
        messageSubject: new Subject(),
        logSubject: new Subject(),
        newResultSubject: new Subject(),
        updateResultSubject: new Subject(),
        deleteResultSubject: new Subject(),
        saveChangesPreviewResultSubject: new Subject(),
        saveChangesResultSubject: new Subject(),
        eventSubject: new Subject(),
        stateSubject: new Subject()
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
            type: 'query'
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
          this.subjects.logSubject.next(pkt.data)
          break
        case 'new_data_view':
          this.subjects.newResultSubject.next(pkt.data)
          break
        case 'update_data_view':
          this.subjects.updateResultSubject.next(pkt.data)
          break
        case 'close_data_view':
          this.subjects.deleteResultSubject.next(pkt.data)
          break
        case 'save_changes_result':
          this.subjects.saveChangesResultSubject.next(pkt.data)
          break
        case 'save_changes_preview_result':
          this.subjects.saveChangesPreviewResultSubject.next(pkt.data)
          break
        case 'message':
          this.subjects.messageSubject.next(pkt.data)
          break
        case 'query_console_action':
          this.subjects.eventSubject.next(pkt.data)
          break
        case 'update_state':
          if (pkt.data.title === this.tab.title) {
            this.state = pkt.data
          } else {
            this.subjects.stateSubject.next(pkt.data)
          }
          break
      }
    },
    startHeartBeat() {
      this.heartBeatInterval = setInterval(() => {
        this.ws.send(JSON.stringify({
          type: 'ping'
        }))
      }, 1000 * 10)
    },
    onEditorAction(action) {
      if (!this.isSqlExecutionAction(action)) {
        this.sendEditorAction(action)
        return
      }
      if (this.editorDirtyGuardOpen) {
        return
      }
      if (this.pendingEditorActions.length > 0) {
        this.pendingEditorActions.push(action)
        if (action.action === 'run_sql_complete') {
          this.confirmPendingEditorActions()
        }
        return
      }
      if (!this.hasDirty()) {
        this.sendEditorAction(action)
        return
      }

      this.pendingEditorActions.push(action)
      if (action.action !== 'run_sql_chunk') {
        this.confirmPendingEditorActions()
      }
    },
    isSqlExecutionAction(action) {
      return action && ['run_sql', 'run_sql_chunk', 'run_sql_complete', 'run_sql_file'].includes(action.action)
    },
    confirmPendingEditorActions() {
      this.editorDirtyGuardOpen = true
      this.$confirm('There are unsaved changes. Discard them and run new SQL?', 'Warning', {
        confirmButtonText: 'Confirm',
        cancelButtonText: 'Cancel',
        type: 'warning'
      }).then(() => {
        const actions = this.pendingEditorActions.splice(0)
        this.clearDirty()
        this.editorDirtyGuardOpen = false
        actions.forEach(action => this.sendEditorAction(action))
      }).catch(() => {
        this.pendingEditorActions = []
        this.editorDirtyGuardOpen = false
        this.state.inQuery = false
      })
    },
    sendEditorAction(action) {
      this.ws.send(JSON.stringify({ type: 'query_console_action', data: action }))
    },
    onDataViewAction(action) {
      this.ws.send(JSON.stringify({ type: 'data_view_action', data: action }))
    },
    onCloseDataView(name) {
      this.ws.send(JSON.stringify({ type: 'close_data_view', data: name }))
    },
    onLimitChange(limit) {
      this.ws.send(JSON.stringify({ type: 'limit', data: limit }))
    },
    hasDirty() {
      return !!(this.$refs.resultBar && this.$refs.resultBar.hasDirty())
    },
    clearDirty() {
      if (this.$refs.resultBar && typeof this.$refs.resultBar.clearDirty === 'function') {
        this.$refs.resultBar.clearDirty()
      }
    }
  }
}
</script>

<style lang="scss" scoped>
.container {
  text-align: left;
  height: calc(100vh - 30px);

  .content {
    height: 100%;
  }

  .message {
    height: 22px;
  }
}
</style>
