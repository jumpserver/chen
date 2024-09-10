<template>
  <div v-loading="state.loading" class="data-view" id="doc">
    <ExportDataDialog :visible.sync="exportDataDialogVisible" @submit="onExportSubmit"/>
    <Toolbar :items="iToolBarItems"/>
    <AgGridVue
      :rowData="rowData"
      :columnDefs="colDefs"
      class="ag-theme-balham-dark"
      style="height: 100%"
      :defaultColDef="defaultColDef"
      :gridOptions="gridOptions"
    />
  </div>
</template>

<script>
import Toolbar from '@/framework/components/Toolbar/index.vue'
import { Subject } from 'rxjs'
import ExportDataDialog from '@/components/Main/Explore/DataView/ExportDataDialog.vue'
import { AgGridVue } from 'ag-grid-vue'

import 'ag-grid-community/styles/ag-grid.css'
import 'ag-grid-community/styles/ag-theme-balham.css'

export default {
  name: 'DataView',
  components: { ExportDataDialog, Toolbar, AgGridVue },
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
      init: false
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
