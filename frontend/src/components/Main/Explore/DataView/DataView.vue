<template>
  <div id="doc" v-loading="state.loading" class="data-view" @contextmenu.prevent="preventDefaultContextMenu">
    <RightMenu ref="rightMenu" :menus="menus" />
    <ExportDataDialog :visible.sync="exportDataDialogVisible" @submit="onExportSubmit" />
    <Toolbar :items="iToolBarItems" />
    <AgGridVue
      :rowData="rowData"
      :columnDefs="colDefs"
      class="ag-theme-balham-dark"
      style="height: 100%"
      :defaultColDef="defaultColDef"
      :gridOptions="gridOptions"
      @cell-context-menu="showContextMenu"
    />
  </div>
</template>

<script>
import store from '@/store'
import Toolbar from '@/framework/components/Toolbar/index.vue'
import { Subject } from 'rxjs'
import ExportDataDialog from '@/components/Main/Explore/DataView/ExportDataDialog.vue'
import { AgGridVue } from 'ag-grid-vue'
import RightMenu from '@/components/Main/Explore/DataView/RightMenu.vue'
import { SpecialCharacters, GeneralInsertSQL, GeneralUpdateSQL } from './const'

import 'ag-grid-community/styles/ag-grid.css'
import 'ag-grid-community/styles/ag-theme-balham.css'

export default {
  name: 'DataView',
  components: { ExportDataDialog, Toolbar, AgGridVue, RightMenu },
  props: {
    meta: {
      type: Object,
      default: () => ({})
    },
    data: {
      type: Object,
      default: () => ({})
    },
    messageSubject: {
      type: Subject,
      default: () => new Subject()
    },
    stateSubject: {
      type: Subject,
      default: () => new Subject()
    },
    updateSubject: {
      type: Subject,
      default: () => new Subject()
    },
    toolBarItems: {
      type: Object,
      default: () => ({})
    }
  },
  data() {
    return {
      rowData: [],
      colDefs: [],

      exportDataDialogVisible: false,
      state: {
        limit: 0,
        total: 0,
        pinned: false,
        loading: false,
        paged: false
      },
      defaultToolBarItems: {
        first: {
          type: 'button',
          icon: 'iconfont icon-chen-first_page',
          onClick: this.onFirstPage,
          hidden: () => {
            return !this.state.paged
          }
        },
        prev: {
          type: 'button',
          icon: 'iconfont icon-chen-icon_paging_left',
          onClick: this.onPrevPage,
          hidden: () => {
            return !this.state.paged
          }
        },
        total: {
          type: 'text',
          hidden: () => {
            return this.state.paged
          },
          value: () => {
            return '共 ' + this.$t('NumRow', { num: this.state.total })
          }
        },
        pagination: {
          type: 'dropdown',
          trigger: 'click',
          hidden: () => {
            return !this.state.paged
          },
          options: [
            {
              label: this.$t('NumRow', { num: 50 }),
              value: 50
            },
            {
              label: this.$t('NumRow', { num: 100 }),
              value: 100
            },
            {
              label: this.$t('NumRow', { num: 200 }),
              value: 200
            },
            {
              label: this.$t('NumRow', { num: 500 }),
              value: 500
            }
          ],
          onCommand: (command) => {
            this.$emit('action', { action: 'change_limit', data: command })
          },
          customDisplayContent: () => {
            let content = ''
            if (this.isStatePaged) {
              content += this.$t('NumRow', { num: this.state.limit }) + ' | ' + content
            }
            content += this.$tc('Total') + this.$t('NumRow', { num: this.state.total })
            return content
          }
        },
        next: {
          type: 'button',
          icon: 'iconfont icon-chen-icon_paging_right',
          onClick: this.onNextPage,
          hidden: () => {
            return !this.state.paged
          }
        },
        last: {
          type: 'button',
          icon: 'iconfont icon-chen-last-page',
          onClick: this.onLastPage,
          hidden: () => {
            return !this.state.paged
          }
        },
        refresh: {
          split: true,
          type: 'button',
          icon: 'iconfont icon-chen-reload1',
          onClick: this.onRefresh
        },
        export: {
          split: true,
          type: 'button',
          icon: 'iconfont icon-chen-arrow-to-bottom',
          onClick: this.onExport
        }
      },
      defaultColDef: {
        resizable: true,
        sortable: false,
        filter: false
      },
      gridOptions: {
        suppressMovableColumnsHints: true,
        suppressSortingHints: true
      },
      init: false,
      currentRow: null,
      menus: [
        {
          name: 'copy',
          title: this.$t('Copy'),
          icon: 'el-icon-document-copy',
          hidden: () => { return !store.getters.profile.canCopy },
          children: [
            {
              name: 'copy-insert',
              title: this.$t('InsertStatement'),
              icon: 'el-icon-document-copy',
              callback: () => this.handleCopy('insert')
            },
            {
              name: 'copy-update',
              title: this.$t('UpdateStatement'),
              icon: 'el-icon-document-copy',
              callback: () => this.handleCopy('update')
            }
          ]
        }
      ]
    }
  },
  computed: {
    isStatePaged() {
      return this.state.paged
    },
    iToolBarItems() {
      return Object.assign(this.defaultToolBarItems, this.toolBarItems)
    }
  },
  watch: {
    data() {
      if (!this.init) {
        this.initTable()
      } else {
        this.reloadTable()
      }
    }
  },
  mounted() {
    this.stateSubject.subscribe((state) => {
      if (state.title === this.meta.title) {
        this.state = state
      }
    })
  },
  methods: {
    getState() {
      return this.state
    },
    reloadTable() {
      this.rowData = this.data.data
    },
    initTable() {
      const headers = this.data.fields.map((item) => {
        return {
          field: item.name
        }
      })

      this.colDefs = headers

      this.reloadTable()
      this.init = true
    },
    onNextPage() {
      this.$emit('action', { action: 'next_page' })
    },
    onPrevPage() {
      this.$emit('action', { action: 'prev_page' })
    },
    onFirstPage() {
      this.$emit('action', { action: 'first_page' })
    },
    onLastPage() {
      this.$emit('action', { action: 'last_page' })
    },
    onRefresh() {
      this.$emit('action', { action: 'refresh' })
    },
    onExport() {
      this.exportDataDialogVisible = true
    },
    onExportSubmit(scope) {
      this.exportDataDialogVisible = false
      this.$emit('action', { action: 'export', data: scope })
    },
    showContextMenu(params) {
      this.currentRow = params.data
      this.$refs.rightMenu.show(params.event)
    },
    preventDefaultContextMenu(event) {
      event.preventDefault()
    },
    wrap(str, specChar) {
      const result = str ? str.trim() : ''
      return `${specChar}${result}${specChar}`
    },
    handleCopy(action) {
      let sql = ''
      let fields = ''
      let values = ''
      let updated_attrs = ''
      let conditional_attrs = ''
      let hasPrimary = false
      const dbType = store.getters.profile.dbType
      const { schema, table } = this.meta
      const char = SpecialCharacters[dbType]
      const tableName = `${this.wrap(schema, char)}.${this.wrap(table, char)}`
      const primaryKeys = ['id']
      for (let i = 0; i < this.colDefs.length; i++) {
        const fieldName = this.colDefs[i].field
        const fieldValue = `'${(this.currentRow[fieldName] || '')}'`
        if (action === 'insert') {
          fields += (i > 0 ? ', ' : '') + this.wrap(fieldName, char)
          values += (i > 0 ? ', ' : '') + `${fieldValue}`
          sql = GeneralInsertSQL
            .replace('{table_name}', tableName)
            .replace('{fields}', fields)
            .replace('{values}', values)
        } else {
          if (primaryKeys.includes(fieldName)) {
            hasPrimary = true
          } else {
            updated_attrs += (i > 0 ? ', ' : '') + `${this.wrap(fieldName, char)} = ${fieldValue}`
          }
          if (hasPrimary) {
            conditional_attrs = `${this.wrap('id', char)} = '${this.currentRow['id']}'`
          } else {
            conditional_attrs = `${updated_attrs} LIMIT 1`
          }
          sql = GeneralUpdateSQL
            .replace('{table_name}', tableName)
            .replace('{updated_attrs}', updated_attrs)
            .replace('{conditional_attrs}', conditional_attrs)
        }
      }
      if (!navigator.clipboard) {
        this.$message.error(`${this.$t('NoPermissionError')}: clipboard`)
        return
      }
      navigator.clipboard.writeText(sql).then(() => {
        this.$message.success(this.$t('CopySucceeded'))
      }).catch((error) => {
        this.$message.error(`${this.$t('CopyFailed')}: ${error}`)
      })
    }
  }
}
</script>

<style lang="scss" scoped>
.data-view {
  height: 100%;
  box-sizing: border-box;
  padding-bottom: 26px;
  background: #2B2B2B;
}
</style>
