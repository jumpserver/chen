<template>
  <el-dialog
    :title="formMeta.title"
    :visible.sync="iVisible"
    :width="formMeta.width"
    style="text-align: left"
  >
    <el-form>
      <el-form :model="form" label-width="120px">
        <el-form-item v-for="(item,index) in formMeta.formItems" :key="index" :label="item.label">
          <el-input v-if="item.type === 'input'" v-model="form[item.name]"/>
          <el-select v-if="item.type === 'select'" v-model="form[item.name]" auto-complete="true">
            <el-option
              v-for="(option, i) in item.options"
              :key="i"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
          <el-checkbox
            v-if="item.type === 'checkbox'"
            v-model="form[item.name]"
            :true-label="item.value"
            :false-label="''"
          />
        </el-form-item>
        <el-form-item v-if="hasSQLTemplate" :label="$tc('form.sql_preview')">
          <codemirror
            ref="cmEditor"
            v-model="sqlTemplate"
            :options="cmOptions"
            disabled
            @ready="onCmReady"
          />
        </el-form-item>
      </el-form>
    </el-form>
    <span slot="footer" class="dialog-footer">
      <el-button @click="iVisible = false">
        {{ $tc('Cancel') }}
      </el-button>
      <el-button type="primary" @click="onSubmit">
        {{ $tc('Confirm') }}
      </el-button>
    </span>
  </el-dialog>
</template>

<script>
import { submitResourceForm } from '@/api/resource'
import { compileSQL } from '@/utils/sql'
import 'codemirror/theme/3024-night.css'
import 'codemirror/mode/sql/sql.js'
import store from '@/store'
import { format } from 'sql-formatter'

const formatterMap = {
  null: 'sql',
  'mariadb': 'mariadb',
  'mysql': 'mysql',
  'postgresql': 'postgresql',
  'oracle': 'plsql',
  'db2': 'db2',
  'dameng': 'dameng'
}

const modeMap = {
  null: 'text/x-sql',
  'mariadb': 'text/x-mariadb',
  'mysql': 'text/x-mysql',
  'postgresql': 'text/x-pgsql',
  'oracle': 'text/x-plsql',
  'sqlserver': 'text/x-mssql',
  'db2': 'text/x-sql',
  'dameng': 'text/x-sql'
}

export default {
  props: {
    nodeKey: {
      type: String,
      default: ''
    },
    formOptions: {
      type: Object,
      default: () => {
        return {}
      }
    },
    visible: {
      type: Boolean,
      default: false
    }
  },
  data() {
    return {
      form: {},
      formMeta: {},
      currentNode: {},
      cmOptions: {
        theme: '3024-night',
        lineNumbers: true,
        line: true,
        readOnly: true,
        mode: modeMap[store.getters.profile?.dbType],
        cursorBlinkRate: -1
      }
    }
  },
  computed: {
    sqlParams() {
      return Object.assign({}, this.form, {
        node_name: this.currentNode.label
      })
    },
    iVisible: {
      set(val) {
        this.$emit('update:visible', val)
      },
      get() {
        return this.visible
      }
    },
    hasSQLTemplate() {
      return this.formMeta && this.formMeta.sqlTemplate
    },
    sqlTemplate: {
      get: function() {
        if (!this.hasSQLTemplate) {
          return
        }
        const sql = compileSQL(
          this.formMeta.sqlTemplate, this.sqlParams
        )
        const lang = formatterMap[store.getters.profile?.dbType]
        return format(sql, { language: lang })
      },
      set() {}
    }

  },
  mounted() {
    this.formMeta = this.formOptions.data
    this.currentNode = this.formOptions.node
  },
  methods: {
    onCmReady(cm) {
      cm.setSize('auto', '100px')
    },
    onSubmit() {
      submitResourceForm({
        nodeKey: this.formMeta.nodeKey,
        resource: this.formMeta.resource,
        method: this.formMeta.method,
        data: this.form
      }).then(data => {
        this.$bus.$emit(data.event, data.data)
      }).finally(() => {
        this.iVisible = false
      })
    }
  }
}
</script>

<style scoped>
.CodeMirror {
  height: 100px;
}

::v-deep .el-form .el-form-item__content {
  width: 80%;
}
</style>
