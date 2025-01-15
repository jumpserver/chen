<template>
  <div v-loading="state.editorLoading " style="background-color: #383a3c;">

    <SelectSnippetDialog
      v-if="selectSnippetDialogVisible"
      :visible.sync="selectSnippetDialogVisible"
      @select="onSelectSnippets"
    />

    <SaveSnippetDialog
      v-if="saveSnippetDialogVisible"
      :content="statement"
      :visible.sync="saveSnippetDialogVisible"
    />
    <Toolbar
      :items="toolbarItems"
      :right-items="rightToolbarItems"
      style="margin-left: 5px"
    />

    <el-upload
      style="display: none"
      action="/chen/api/console/upload"
      :multiple="false"
      :with-credentials="true"
      :show-file-list="false"
      :headers="{token: store.getters.token}"
      :on-success="onUploadSuccess"
      :on-error="onUploadError"
      :before-upload="onBeforeUpload"
      accept=".sql"
    >
      <a ref="upload" slot="trigger">upload</a>
    </el-upload>

    <codemirror
      ref="cmEditor"
      v-model="statement"
      v-loading="state.inQuery"
      :options="options"
      @ready="onCmReady"
      @changes="onCmChange"
    />
  </div>
</template>

<script>

import { format } from 'sql-formatter'
import store from '@/store'
import Toolbar from '@/framework/components/Toolbar/index.vue'
import { CodeMirror } from 'vue-codemirror'

import 'codemirror/mode/sql/sql.js'
import 'codemirror/theme/eclipse.css'
import 'codemirror/theme/3024-night.css'
import 'codemirror/addon/display/autorefresh'
import 'codemirror/addon/hint/show-hint.css'
import 'codemirror/addon/hint/show-hint'
import 'codemirror/addon/hint/sql-hint'
import 'codemirror/addon/lint/lint'
import 'codemirror/addon/edit/closebrackets.js'
import 'codemirror/addon/edit/matchbrackets.js'
import { getHints } from '@/api/resource'
import SelectSnippetDialog from '@/components/Main/Explore/QueryConsole/SelectSnippetDialog.vue'
import SaveSnippetDialog from '@/components/Main/Explore/QueryConsole/SaveSnippetDialog.vue'

const formatterMap = {
  'clickhouse': 'sql',
  'mariadb': 'mariadb',
  'mysql': 'mysql',
  'postgresql': 'postgresql',
  'oracle': 'plsql',
  'sqlserver': 'tsql',
  'db2': 'db2',
  'dameng': 'dameng'
}
const modeMap = {
  'clickhouse': 'text/x-sql',
  'mariadb': 'text/x-mariadb',
  'mysql': 'text/x-mysql',
  'postgresql': 'text/x-pgsql',
  'oracle': 'text/x-plsql',
  'sqlserver': 'text/x-mssql',
  'db2': 'text/x-sql',
  'dameng': 'text/x-sql'
}

export default {
  name: 'CodeEditor',
  components: {
    SaveSnippetDialog,
    SelectSnippetDialog,
    Toolbar
  },
  props: {
    nodeKey: {
      type: String,
      default: ''
    },
    state: {
      type: Object,
      default: () => ({})
    },
    subjects: {
      type: Object,
      default: () => {
      }
    }
  },
  data() {
    return {
      currentContext: '',
      selectSnippetDialogVisible: false,
      saveSnippetDialogVisible: false,
      options: {
        autoRefresh: true,
        indentWithTabs: true,
        smartIndent: true,
        mode: modeMap[store.getters.profile?.dbType],
        theme: '3024-night',
        lineNumbers: true,
        line: true,
        matchBrackets: true,
        autoCloseBrackets: true,
        hintOptions: {
          completeSingle: false
        },
        extraKeys: {
          'Ctrl-L': (cm) => {
            this.onFormat()
          },
          'Ctrl-Enter': (cm) => {
            this.onRun()
          },
          'Ctrl-C': (cm) => {
            this.onStop()
          },
          'Ctrl-S': (cm) => {
            this.saveSnippetDialogVisible = true
          },
          'Ctrl-R': (cm) => {
            this.selectSnippetDialogVisible = true
          }
        }
      },
      cm: null,
      statement: '',
      toolbarItems: {
        run: {
          name: () => {
            if (this.cmInstance) {
              if (this.selectionValue.length > 0) {
                return this.$t('RunSelected')
              } else {
                return this.$t('Run')
              }
            }
          },
          type: 'button',
          icon: 'iconfont icon-chen-play text-primary',
          tip: this.$t('RunHotKey'),
          disabled: () => this.state.inQuery,
          loading: () => {
            return this.state.inQuery
          },
          onClick: () => this.onRun()
        },
        stop: {
          split: true,
          type: 'button',
          icon: 'iconfont icon-chen-stop',
          tip: this.$t('StopHotKey'),
          onClick: () => this.onStop(),
          disabled: () => !this.state.canCancel
        },
        format: {
          type: 'button',
          icon: 'iconfont icon-chen-m-geshihuawenzi',
          tip: this.$t('FormatHotKey'),
          onClick: () => this.onFormat(),
          disabled: () => this.state.inQuery
        },
        open: {
          split: true,
          type: 'button',
          icon: 'iconfont icon-chen-file-open',
          tip: this.$t('Open'),
          onClick: () => {
            this.selectSnippetDialogVisible = true
          },
          disabled: () => this.state.inQuery
        },
        save: {
          type: 'button',
          icon: 'iconfont icon-chen-save',
          tip: this.$t('Save'),
          onClick: () => {
            this.saveSnippetDialogVisible = true
          },
          disabled: () => this.state.inQuery
        }
      },
      rightToolbarItems: {
        selectContext: {
          type: 'dropdown',
          trigger: 'click',
          options: [],
          onCommand: (command) => {
            this.$emit('action', { action: 'change_current_context', data: command })
          },
          customDisplayContent: () => {
            return this.$tc('Current') + ' Context: ' + this.state.currentContext
          }
        }
      }
    }
  },
  computed: {
    store() {
      return store
    },
    cmInstance() {
      return this.cm
    },
    selectionValue() {
      return this.cmInstance.getSelection()
    },
    autoComplete() {
      return !store.getters.disableautohash
    }

  },
  watch: {
    state(val) {
      if (val.currentContext && val.currentContext !== this.currentContext) {
        this.refreshHints(val.currentContext)
        this.currentContext = val.currentContext
      }
      this.rightToolbarItems.selectContext.options = val.contexts?.map((context) => {
        const label = context === val.selectContext ? '✔️' + context : context
        return { label: label, value: context }
      })
    }
  },
  methods: {
    onSelectSnippets(snippet) {
      if (this.statement.length > 0) {
        this.statement += '\n'
      }
      this.statement += snippet
      this.selectSnippetDialogVisible = false
    },
    onRun() {
      const sql = this.selectionValue || this.statement
      const CHUNK_SIZE = 4096

      if (sql.length <= CHUNK_SIZE) {
        this.$emit('action', { action: 'run_sql', data: sql })
      } else {
        const totalChunks = Math.ceil(sql.length / CHUNK_SIZE)
        for (let i = 0; i < totalChunks; i++) {
          const chunk = sql.slice(i * CHUNK_SIZE, (i + 1) * CHUNK_SIZE)
          this.$emit('action', {
            action: 'run_sql_chunk',
            data: { chunk, index: i, total: totalChunks }
          })
        }
        this.$emit('action', {
          action: 'run_sql_complete',
          data: { total: totalChunks }
        })
      }
    },
    onStop() {
      this.$emit('action', { action: 'cancel' })
    },
    onFormat() {
      const lang = formatterMap[store.getters.profile?.dbType]
      this.statement = format(this.statement, { language: lang })
    },
    onCmReady(cm) {
      this.cm = cm
    },
    onCmChange(cm, change) {
      const { text, origin } = change[0]
      if (origin === '+input' && text[0].trim()) {
        if (this.autoComplete) {
          cm.showHint({
            hint: CodeMirror.hint.sql,
            completeSingle: false
          })
        }
      }
    },
    refreshHints(context) {
      getHints(this.nodeKey, context).then((res) => {
        this.options.hintOptions.tables = res
      })
    },
    onBeforeUpload(file) {
      this.state.inQuery = true
    },
    onUploadSuccess(resp, file, fileList) {
      this.$emit('action', { action: 'run_sql_file', data: resp.path })
    },
    onUploadError(err, file, fileList) {
      console.log(err)
    }
  }
}

</script>

<style lang="scss">
.vue-codemirror {
  height: 100%;
}

.cm-s-3024-night.CodeMirror {
  height: calc(100% - 28px);
  font-size: 14px;
  background: #2B2B2B !important;
  border-bottom: 1px solid #5F5F5F !important;
}

.CodeMirror-linenumbers {
  width: 52px !important;
  background: #313335 !important;
}

.cm-s-3024-night .CodeMirror-linenumber {
  color: #BBBBBB !important;
  text-align: left !important;
}
</style>
