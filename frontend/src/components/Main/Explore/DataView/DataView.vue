<template>
  <div
    id="doc"
    v-loading="state.loading"
    class="data-view"
    style="z-index: 99;position: relative"
    @contextmenu.prevent="preventDefaultContextMenu"
  >
    <RightMenu ref="rightMenu" :menus="menus" />
    <ExportDataDialog :visible.sync="exportDataDialogVisible" @submit="onExportSubmit" />
    <Toolbar :key="toolbarKey" :items="iToolBarItems" />
    <ResultGrid
      ref="resultGrid"
      :row-data="rowData"
      :column-defs="colDefs"
      :editable="resultEditable"
      @cell-value-changed="onCellValueChanged"
      @cell-clicked="onCellClicked"
      @cell-context-menu="showContextMenu"
    />
  </div>
</template>

<script>
import store from '@/store'
import Toolbar from '@/framework/components/Toolbar/index.vue'
import { Subject } from 'rxjs'
import ExportDataDialog from '@/components/Main/Explore/DataView/ExportDataDialog.vue'

import RightMenu from '@/components/Main/Explore/DataView/RightMenu.vue'
import { SpecialCharacters, GeneralInsertSQL, GeneralUpdateSQL } from './const'

import ResultGrid from '@/components/Main/Explore/DataView/ResultGrid.vue'

export default {
  name: 'DataView',
  components: { ExportDataDialog, Toolbar, ResultGrid, RightMenu },
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
    },
    editable: {
      type: Boolean,
      default: false
    },
    rowEditActionsEnabled: {
      type: Boolean,
      default: false
    },
    previewBeforeSave: {
      type: Boolean,
      default: false
    }
  },
  data() {
    return {
      rowData: [],
      colDefs: [],
      dirtyCells: {},
      insertRows: [],
      deletedRows: {},
      dirtyVersion: 0,
      nextInsertRowId: 1,
      pendingSavePayload: null,

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
        addRow: {
          split: true,
          type: 'button',
          icon: 'iconfont icon-chen-plus',
          name: () => 'Add',
          hidden: () => {
            return !this.resultEditable || !this.rowEditActionsEnabled
          },
          onClick: this.onAddRow
        },
        deleteRow: {
          type: 'button',
          icon: 'iconfont icon-chen-minus',
          name: () => 'Delete',
          hidden: () => {
            return !this.resultEditable || !this.rowEditActionsEnabled
          },
          disabled: () => {
            return !this.currentRow
          },
          onClick: this.onDeleteRows
        },
        saveChanges: {
          split: true,
          type: 'button',
          icon: 'iconfont icon-chen-save',
          name: () => 'Save',
          hidden: () => {
            return !this.resultEditable
          },
          disabled: () => {
            return !this.hasDirty()
          },
          onClick: this.onSaveChanges
        },
        cancelChanges: {
          type: 'button',
          icon: 'el-icon-close',
          name: () => 'Cancel',
          hidden: () => {
            return !this.resultEditable
          },
          disabled: () => {
            return !this.hasDirty()
          },
          onClick: this.onCancelChanges
        },
        export: {
          split: true,
          type: 'button',
          icon: 'iconfont icon-chen-arrow-to-bottom',
          onClick: this.onExport
        }
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
    resultEditable() {
      const fields = this.data && Array.isArray(this.data.fields) ? this.data.fields : []
      return this.editable &&
        this.data &&
        this.data.editable === true &&
        fields.some((field) => field && field.editable === true)
    },
    isStatePaged() {
      return this.state.paged
    },
    iToolBarItems() {
      return Object.assign(this.defaultToolBarItems, this.toolBarItems)
    },
    toolbarKey() {
      return `toolbar-${this.dirtyVersion}`
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
      const rows = this.data && this.data.data ? this.data.data.map((row) => this.normalizeRowData(row)) : []
      rows.forEach((row) => {
        if (this.isExistingRowDeleted(row)) {
          row.__chenDeleted = true
        }
      })
      this.rowData = rows.concat(this.insertRows.map((row) => row.data))
    },
    initTable() {
      const headers = this.data.fields.map((item) => {
        return {
          field: item.name,
          fieldMeta: item,
          editable: (params) => this.isCellEditable(params, item),
          valueParser: (params) => this.parseCellValue(params.newValue, item),
          cellClassRules: {
            'chen-dirty-cell': (params) => this.isDirtyCell(params),
            'chen-insert-row': (params) => this.isInsertRow(params.data),
            'chen-delete-row': (params) => this.isDeleteRow(params.data)
          }
        }
      })

      this.colDefs = headers

      this.reloadTable()
      this.init = true
    },
    getPrimaryKeyField() {
      if (!this.data || !this.data.fields) {
        return null
      }
      return this.data.fields.find((field) => field.primaryKey === true || field.isPrimaryKey === true)
    },
    getValueIsNull(value) {
      return value === null || value === undefined
    },
    valuesEqual(left, right) {
      return left === right
    },
    buildDirtyKey(pkValue, sourceColumn) {
      return `${JSON.stringify(pkValue)}::${sourceColumn}`
    },
    buildDeleteKey(pkValue) {
      return JSON.stringify(pkValue)
    },
    buildInsertKey(id, sourceColumn) {
      return `insert:${id}::${sourceColumn}`
    },
    hasDirty() {
      return Object.keys(this.dirtyCells).length > 0 ||
        this.insertRows.length > 0 ||
        Object.keys(this.deletedRows).length > 0
    },
    clearDirty() {
      this.dirtyCells = {}
      this.insertRows = []
      this.deletedRows = {}
      this.pendingSavePayload = null
      this.dirtyVersion += 1
      this.reloadTable()
      this.refreshDirtyCells()
    },
    setObjectValue(targetName, key, value) {
      this[targetName] = {
        ...this[targetName],
        [key]: value
      }
    },
    deleteObjectValue(targetName, key) {
      const nextValue = { ...this[targetName] }
      delete nextValue[key]
      this[targetName] = nextValue
    },
    isDirtyCell(params) {
      if (!params || !params.data || !params.colDef || !params.colDef.fieldMeta) {
        return false
      }
      if (this.isInsertRow(params.data)) {
        const sourceColumn = params.colDef.fieldMeta.sourceColumn
        return !!(sourceColumn && params.data.__chenValues && params.data.__chenValues[sourceColumn])
      }
      const primaryKeyField = this.getPrimaryKeyField()
      const sourceColumn = params.colDef.fieldMeta.sourceColumn
      if (!primaryKeyField || !sourceColumn) {
        return false
      }
      const pkValue = params.data[primaryKeyField.name]
      if (this.getValueIsNull(pkValue)) {
        return false
      }
      return !!this.dirtyCells[this.buildDirtyKey(pkValue, sourceColumn)]
    },
    isInsertRow(row) {
      return !!(row && row.__chenInsertId)
    },
    isDeleteRow(row) {
      return !!(row && row.__chenDeleted)
    },
    isExistingRowDeleted(row) {
      const primaryKeyField = this.getPrimaryKeyField()
      if (!row || !primaryKeyField) {
        return false
      }
      const pkValue = row[primaryKeyField.name]
      return !!this.deletedRows[this.buildDeleteKey(pkValue)]
    },
    isCellEditable(params, fieldMeta) {
      if (!this.resultEditable || !fieldMeta || !fieldMeta.sourceColumn) {
        return false
      }
      const row = params ? params.data : null
      if (this.isDeleteRow(row)) {
        return false
      }
      if (this.isInsertRow(row)) {
        return fieldMeta.insertable === true
      }
      return fieldMeta.editable === true
    },
    refreshDirtyCells() {
      const grid = this.$refs.resultGrid
      if (grid && grid.gridApi && typeof grid.gridApi.refreshCells === 'function') {
        grid.gridApi.refreshCells({ force: true })
      }
    },
    onCellValueChanged(params) {
      if (!this.resultEditable || !params || !params.colDef || !params.colDef.fieldMeta || !params.data) {
        return
      }

      const fieldMeta = params.colDef.fieldMeta
      if (!this.isCellEditable(params, fieldMeta)) {
        return
      }
      if (this.isInsertRow(params.data)) {
        this.onInsertCellValueChanged(params, fieldMeta)
        return
      }

      const primaryKeyField = this.getPrimaryKeyField()
      if (!primaryKeyField || !primaryKeyField.sourceColumn || !fieldMeta.sourceColumn) {
        return
      }

      const pkValue = params.data[primaryKeyField.name]
      if (this.getValueIsNull(pkValue)) {
        this.$message.warning('Primary key value is empty, cannot edit this row')
        return
      }

      const sourceColumn = fieldMeta.sourceColumn
      const key = this.buildDirtyKey(pkValue, sourceColumn)
      const fallbackOldValue = Object.prototype.hasOwnProperty.call(params, 'oldValue')
        ? params.oldValue
        : params.data[params.colDef.field]
      const oldValue = this.dirtyCells[key]
        ? this.dirtyCells[key].oldValue
        : this.normalizeCellValue(fallbackOldValue, fieldMeta)
      const oldValueIsNull = this.dirtyCells[key]
        ? this.dirtyCells[key].oldValueIsNull
        : this.getValueIsNull(fallbackOldValue)
      const newValue = this.normalizeCellValue(params.newValue, fieldMeta)
      const newValueIsNull = this.getValueIsNull(newValue)

      if (oldValueIsNull === newValueIsNull && this.valuesEqual(oldValue, newValue)) {
        this.deleteObjectValue('dirtyCells', key)
        this.dirtyVersion += 1
        this.refreshDirtyCells()
        return
      }

      this.setObjectValue('dirtyCells', key, {
        pkColumn: primaryKeyField.sourceColumn,
        pkValue,
        pkValueIsNull: this.getValueIsNull(pkValue),
        sourceColumn,
        oldValue,
        oldValueIsNull,
        newValue,
        newValueIsNull
      })
      this.dirtyVersion += 1
      this.refreshDirtyCells()
    },
    onInsertCellValueChanged(params, fieldMeta) {
      const sourceColumn = fieldMeta.sourceColumn
      const row = this.insertRows.find((item) => item.id === params.data.__chenInsertId)
      if (!row || !sourceColumn) {
        return
      }
      if (params.newValue === undefined) {
        this.$delete(row.values, sourceColumn)
        this.$delete(row.data.__chenValues, sourceColumn)
      } else {
        const newValue = this.normalizeCellValue(params.newValue, fieldMeta)
        this.$set(row.values, sourceColumn, {
          value: newValue,
          valueIsNull: this.getValueIsNull(newValue)
        })
        this.$set(row.data.__chenValues, sourceColumn, true)
      }
      this.dirtyVersion += 1
      this.refreshDirtyCells()
    },
    normalizeRowData(row) {
      const normalized = { ...row }
      const fields = this.data && Array.isArray(this.data.fields) ? this.data.fields : []
      fields.forEach((field) => {
        if (field && Object.prototype.hasOwnProperty.call(normalized, field.name)) {
          normalized[field.name] = this.normalizeCellValue(normalized[field.name], field)
        }
      })
      return normalized
    },
    parseCellValue(value, fieldMeta) {
      return this.normalizeCellValue(value, fieldMeta)
    },
    normalizeCellValue(value, fieldMeta) {
      if (value === null || value === undefined || !fieldMeta) {
        return value
      }
      const type = this.normalizeFieldType(fieldMeta.type)
      if (this.isDateType(type)) {
        return this.normalizeDateValue(value)
      }
      if (this.isTimeType(type)) {
        return this.normalizeTimeValue(value)
      }
      if (this.isTimestampType(type)) {
        return this.normalizeTimestampValue(value, this.isOffsetTimestampType(type))
      }
      return value
    },
    normalizeFieldType(type) {
      if (!type) {
        return ''
      }
      let normalized = String(type).trim().toLowerCase()
      let changed = true
      while (changed) {
        changed = false
        if (normalized.startsWith('nullable(') && normalized.endsWith(')')) {
          normalized = normalized.substring('nullable('.length, normalized.length - 1).trim()
          changed = true
        }
        if (normalized.startsWith('lowcardinality(') && normalized.endsWith(')')) {
          normalized = normalized.substring('lowcardinality('.length, normalized.length - 1).trim()
          changed = true
        }
      }
      const bracketIndex = normalized.indexOf('(')
      if (bracketIndex > -1) {
        normalized = normalized.substring(0, bracketIndex).trim()
      }
      return normalized
    },
    isDateType(type) {
      return type === 'date'
    },
    isTimeType(type) {
      return type === 'time' || type === 'time without time zone'
    },
    isTimestampType(type) {
      return this.isOffsetTimestampType(type) ||
        ['timestamp', 'timestamp without time zone', 'datetime', 'datetime2', 'smalldatetime'].includes(type)
    },
    isOffsetTimestampType(type) {
      return ['timestamptz', 'timestamp with time zone', 'datetimeoffset'].includes(type)
    },
    normalizeDateValue(value) {
      if (value instanceof Date) {
        return this.formatDateParts(value.getFullYear(), value.getMonth() + 1, value.getDate())
      }
      const text = String(value).trim()
      const match = text.match(/^(\d{4}-\d{2}-\d{2})/)
      return match ? match[1] : value
    },
    normalizeTimeValue(value) {
      if (value instanceof Date) {
        return this.formatTimeParts(value.getHours(), value.getMinutes(), value.getSeconds())
      }
      const text = String(value).trim()
      const localized = text.match(/^(\d{1,2}):(\d{2}):(\d{2})(\.\d{1,9})?\s*(上午|下午|AM|PM)$/i)
      if (localized) {
        let hour = Number(localized[1])
        const meridiem = localized[5].toUpperCase()
        if ((meridiem === '下午' || meridiem === 'PM') && hour < 12) {
          hour += 12
        }
        if ((meridiem === '上午' || meridiem === 'AM') && hour === 12) {
          hour = 0
        }
        return `${this.pad2(hour)}:${localized[2]}:${localized[3]}${localized[4] || ''}`
      }
      const canonical = text.match(/^(\d{1,2}):(\d{2}):(\d{2})(\.\d{1,9})?$/)
      if (canonical) {
        return `${this.pad2(Number(canonical[1]))}:${canonical[2]}:${canonical[3]}${canonical[4] || ''}`
      }
      const isoTime = text.match(/[T\s](\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?)/)
      return isoTime ? isoTime[1] : value
    },
    normalizeTimestampValue(value, keepOffset) {
      if (value instanceof Date) {
        return `${this.formatDateParts(value.getFullYear(), value.getMonth() + 1, value.getDate())} ${this.formatTimeParts(value.getHours(), value.getMinutes(), value.getSeconds())}`
      }
      const text = String(value).trim()
      if (keepOffset) {
        return text
      }
      const match = text.match(/^(\d{4}-\d{2}-\d{2})[T\s](\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?)/)
      return match ? `${match[1]} ${match[2]}` : value
    },
    formatDateParts(year, month, day) {
      return `${year}-${this.pad2(month)}-${this.pad2(day)}`
    },
    formatTimeParts(hour, minute, second) {
      return `${this.pad2(hour)}:${this.pad2(minute)}:${this.pad2(second)}`
    },
    pad2(value) {
      return String(value).padStart(2, '0')
    },
    buildSaveChangesPayload() {
      return {
        schema: this.resolveSaveSchema(),
        table: this.resolveSaveTable(),
        changes: Object.values(this.dirtyCells),
        insertRows: this.insertRows
          .filter((row) => Object.keys(row.values).length > 0)
          .map((row) => ({ values: row.values })),
        deleteRows: Object.values(this.deletedRows)
      }
    },
    resolveSaveSchema() {
      if (this.meta && Object.prototype.hasOwnProperty.call(this.meta, 'schema')) {
        return this.meta.schema
      }
      const field = this.getEditableSourceField()
      return field ? field.sourceSchema : undefined
    },
    resolveSaveTable() {
      if (this.meta && Object.prototype.hasOwnProperty.call(this.meta, 'table')) {
        return this.meta.table
      }
      const field = this.getEditableSourceField()
      return field ? field.sourceTable : undefined
    },
    getEditableSourceField() {
      if (!this.data || !this.data.fields) {
        return null
      }
      return this.data.fields.find((field) => field && field.editable === true && field.sourceTable && field.sourceColumn)
    },
    onSaveChanges() {
      if (!this.hasDirty()) {
        return
      }

      const payload = this.buildSaveChangesPayload()
      this.pendingSavePayload = payload
      const action = {
        action: this.previewBeforeSave ? 'save_changes_preview' : 'save_changes',
        dataView: this.meta.title,
        data: payload
      }

      this.$emit('action', action)
    },
    handleSaveChangesPreviewResult(result) {
      if (!result || !result.success) {
        const reason = result && result.reason ? result.reason : 'Preview failed'
        const index = result && result.failedChangeIndex !== undefined && result.failedChangeIndex !== null
          ? `, failedChangeIndex=${result.failedChangeIndex}`
          : ''
        this.$message.error(`${reason}${index}`)
        return
      }
      const updateCount = result.updateCount || 0
      const insertCount = result.insertCount || 0
      const deleteCount = result.deleteCount || 0
      this.$confirm(
        `Preview: ${updateCount} updates, ${insertCount} inserts, ${deleteCount} deletes. Continue?`,
        'Save changes',
        {
          confirmButtonText: 'Save',
          cancelButtonText: 'Cancel',
          type: 'warning'
        }
      ).then(() => {
        this.$emit('action', {
          action: 'save_changes',
          dataView: this.meta.title,
          data: this.pendingSavePayload || this.buildSaveChangesPayload()
        })
      }).catch(() => {})
    },
    onCancelChanges() {
      this.clearDirty()
      this.onRefresh()
    },
    onAddRow() {
      const id = this.nextInsertRowId++
      const data = {
        __chenInsertId: id,
        __chenValues: {}
      }
      this.insertRows.push({
        id,
        data,
        values: {}
      })
      this.reloadTable()
      this.dirtyVersion += 1
      this.refreshDirtyCells()
    },
    onDeleteRows() {
      const rows = this.getRowsForDelete()
      if (rows.length === 0) {
        return
      }
      rows.forEach((row) => this.markRowDeleted(row))
      this.reloadTable()
      this.dirtyVersion += 1
      this.refreshDirtyCells()
    },
    getRowsForDelete() {
      const grid = this.$refs.resultGrid
      if (grid && typeof grid.getRangeRowData === 'function') {
        const rows = grid.getRangeRowData()
        if (rows.length > 0) {
          return rows
        }
      }
      return this.currentRow ? [this.currentRow] : []
    },
    markRowDeleted(row) {
      if (!row) {
        return
      }
      if (this.isInsertRow(row)) {
        this.insertRows = this.insertRows.filter((item) => item.id !== row.__chenInsertId)
        if (this.currentRow === row) {
          this.currentRow = null
        }
        return
      }
      const primaryKeyField = this.getPrimaryKeyField()
      if (!primaryKeyField || !primaryKeyField.sourceColumn) {
        this.$message.warning('Primary key is missing, cannot delete this row')
        return
      }
      const pkValue = row[primaryKeyField.name]
      if (this.getValueIsNull(pkValue)) {
        this.$message.warning('Primary key value is empty, cannot delete this row')
        return
      }
      this.clearRowUpdates(pkValue)
      this.setObjectValue('deletedRows', this.buildDeleteKey(pkValue), {
        pkColumn: primaryKeyField.sourceColumn,
        pkValue,
        pkValueIsNull: false
      })
    },
    clearRowUpdates(pkValue) {
      const nextDirtyCells = { ...this.dirtyCells }
      Object.keys(nextDirtyCells).forEach((key) => {
        if (key.startsWith(`${JSON.stringify(pkValue)}::`)) {
          delete nextDirtyCells[key]
        }
      })
      this.dirtyCells = nextDirtyCells
    },
    fallbackWriteClipboardText(text, originalError) {
      const textarea = document.createElement('textarea')
      textarea.value = text
      textarea.setAttribute('readonly', '')
      textarea.style.position = 'fixed'
      textarea.style.left = '-9999px'
      document.body.appendChild(textarea)
      textarea.select()
      try {
        const copied = document.execCommand('copy')
        if (copied) {
          this.$message.success(this.$t('CopySucceeded'))
        } else {
          throw originalError || new Error('clipboard unavailable')
        }
      } catch (error) {
        this.$message.error(`${this.$t('CopyFailed')}: ${error}`)
      } finally {
        document.body.removeChild(textarea)
      }
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
    onCellClicked(params) {
      this.currentRow = params ? params.data : null
      this.dirtyVersion += 1
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

.data-view ::v-deep .chen-dirty-cell {
  background-color: rgba(3, 157, 0, 0.28) !important;
}

.data-view ::v-deep .chen-insert-row {
  background-color: rgba(47, 101, 202, 0.24) !important;
}

.data-view ::v-deep .chen-delete-row {
  background-color: rgba(190, 66, 66, 0.26) !important;
  color: #b8b8b8;
  text-decoration: line-through;
}
</style>
