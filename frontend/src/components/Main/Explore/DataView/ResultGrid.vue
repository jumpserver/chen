<template>
  <AgGridVue
    :row-data="rowData"
    :column-defs="columnDefs"
    class="result-grid ag-theme-balham-dark"
    style="height: 100%"
    :default-col-def="defaultColDef"
    :grid-options="gridOptions"
    @grid-ready="onGridReady"
    @cell-mouse-down="onCellMouseDown"
    @cell-mouse-over="onCellMouseOver"
    @cell-clicked="onCellClicked"
    @cell-context-menu="onCellContextMenu"
  />
</template>

<script>
import { AgGridVue } from 'ag-grid-vue'

import 'ag-grid-community/styles/ag-grid.css'
import 'ag-grid-community/styles/ag-theme-balham.css'

export default {
  name: 'ResultGrid',
  components: { AgGridVue },
  props: {
    rowData: {
      type: Array,
      default: () => []
    },
    columnDefs: {
      type: Array,
      default: () => []
    }
  },
  data() {
    return {
      defaultColDef: {
        resizable: true,
        sortable: false,
        filter: false,
        cellClassRules: {
          'chen-range-cell': (params) => this.isRangeCell(params),
          'chen-range-top': (params) => this.isRangeBorderCell(params, 'top'),
          'chen-range-bottom': (params) => this.isRangeBorderCell(params, 'bottom'),
          'chen-range-left': (params) => this.isRangeBorderCell(params, 'left'),
          'chen-range-right': (params) => this.isRangeBorderCell(params, 'right')
        }
      },
      gridOptions: {
        suppressMovableColumnsHints: true,
        suppressSortingHints: true
      },
      gridApi: null,
      columnApi: null,
      rangeSelection: {
        dragging: false,
        moved: false,
        anchor: null,
        current: null
      }
    }
  },
  watch: {
    rowData() {
      this.clearRangeSelection()
    }
  },
  mounted() {
    document.addEventListener('keydown', this.onDocumentKeyDown)
  },
  beforeDestroy() {
    document.removeEventListener('mouseup', this.onDocumentMouseUp)
    document.removeEventListener('keydown', this.onDocumentKeyDown)
  },
  methods: {
    onGridReady(params) {
      this.gridApi = params.api
      this.columnApi = params.columnApi
    },
    onCellContextMenu(params) {
      this.$emit('cell-context-menu', params)
    },
    getCellRef(params) {
      if (
        !params ||
        !params.column ||
        !params.node ||
        params.node.rowIndex === null ||
        params.node.rowIndex === undefined
      ) {
        return null
      }
      return {
        rowIndex: params.node.rowIndex,
        colId: params.column.getColId()
      }
    },
    isLeftMouseEvent(event) {
      return event && event.button === 0
    },
    onCellMouseDown(params) {
      if (!this.isLeftMouseEvent(params.event)) {
        return
      }
      const cell = this.getCellRef(params)
      if (!cell) {
        return
      }
      this.rangeSelection = {
        dragging: true,
        moved: false,
        anchor: cell,
        current: cell
      }
      document.addEventListener('mouseup', this.onDocumentMouseUp)
      this.refreshRangeCells()
    },
    onCellMouseOver(params) {
      if (!this.rangeSelection.dragging) {
        return
      }
      const cell = this.getCellRef(params)
      if (!cell || this.isSameCell(cell, this.rangeSelection.current)) {
        return
      }
      this.rangeSelection.current = cell
      this.rangeSelection.moved = true
      this.refreshRangeCells()
    },
    onDocumentMouseUp() {
      if (!this.rangeSelection.dragging) {
        return
      }
      this.rangeSelection.dragging = false
      document.removeEventListener('mouseup', this.onDocumentMouseUp)
    },
    onCellClicked(params) {
      const cell = this.getCellRef(params)
      if (!cell) {
        return
      }
      if (this.rangeSelection.moved) {
        this.rangeSelection.moved = false
        return
      }
      this.rangeSelection = {
        dragging: false,
        moved: false,
        anchor: cell,
        current: cell
      }
      this.refreshRangeCells()
    },
    isSameCell(cellA, cellB) {
      return !!cellA &&
        !!cellB &&
        cellA.rowIndex === cellB.rowIndex &&
        cellA.colId === cellB.colId
    },
    getDisplayedColumns() {
      if (this.gridApi && typeof this.gridApi.getAllDisplayedColumns === 'function') {
        return this.gridApi.getAllDisplayedColumns()
      }
      if (this.columnApi && typeof this.columnApi.getAllDisplayedColumns === 'function') {
        return this.columnApi.getAllDisplayedColumns()
      }
      return []
    },
    getRangeBounds() {
      const { anchor, current } = this.rangeSelection
      if (!anchor || !current) {
        return null
      }

      const displayedColumns = this.getDisplayedColumns()
      const displayedColIds = displayedColumns.map((column) => column.getColId())
      const anchorColIndex = displayedColIds.indexOf(anchor.colId)
      const currentColIndex = displayedColIds.indexOf(current.colId)

      if (anchorColIndex === -1 || currentColIndex === -1) {
        return null
      }

      const minCol = Math.min(anchorColIndex, currentColIndex)
      const maxCol = Math.max(anchorColIndex, currentColIndex)

      return {
        minRow: Math.min(anchor.rowIndex, current.rowIndex),
        maxRow: Math.max(anchor.rowIndex, current.rowIndex),
        minCol,
        maxCol,
        displayedColIds
      }
    },
    getCellPositionInRange(params) {
      if (!params || !params.column || !params.node) {
        return null
      }

      const bounds = this.getRangeBounds()
      if (!bounds) {
        return null
      }

      const rowIndex = params.node.rowIndex
      const colIndex = bounds.displayedColIds.indexOf(params.column.getColId())

      if (
        rowIndex < bounds.minRow ||
        rowIndex > bounds.maxRow ||
        colIndex < bounds.minCol ||
        colIndex > bounds.maxCol
      ) {
        return null
      }

      return { bounds, rowIndex, colIndex }
    },
    isRangeCell(params) {
      return !!this.getCellPositionInRange(params)
    },
    isRangeBorderCell(params, side) {
      const position = this.getCellPositionInRange(params)
      if (!position) {
        return false
      }

      const { bounds, rowIndex, colIndex } = position
      return (side === 'top' && rowIndex === bounds.minRow) ||
        (side === 'bottom' && rowIndex === bounds.maxRow) ||
        (side === 'left' && colIndex === bounds.minCol) ||
        (side === 'right' && colIndex === bounds.maxCol)
    },
    refreshRangeCells() {
      if (this.gridApi && typeof this.gridApi.refreshCells === 'function') {
        this.gridApi.refreshCells({ force: true })
      }
    },
    clearRangeSelection() {
      this.rangeSelection = {
        dragging: false,
        moved: false,
        anchor: null,
        current: null
      }
      this.refreshRangeCells()
    },
    onDocumentKeyDown(event) {
      if ((!event.ctrlKey && !event.metaKey) || event.key.toLowerCase() !== 'c') {
        return
      }
      if (!this.$el || !this.$el.contains(event.target)) {
        return
      }

      const bounds = this.getRangeBounds()
      if (!bounds) {
        return
      }

      event.preventDefault()
      this.copyRangeAsTsv(bounds)
    },
    copyRangeAsTsv(bounds) {
      try {
        const colIds = bounds.displayedColIds.slice(bounds.minCol, bounds.maxCol + 1)
        const lines = []

        for (let rowIndex = bounds.minRow; rowIndex <= bounds.maxRow; rowIndex++) {
          const rowNode = this.gridApi.getDisplayedRowAtIndex(rowIndex)
          if (!rowNode) {
            continue
          }

          lines.push(
            colIds
              .map((colId) => this.getCellDisplayValue(rowNode, colId))
              .join('\t')
          )
        }

        this.writeClipboardText(lines.join('\n'))
      } catch (error) {
        this.$message.error(`${this.$t('CopyFailed')}: ${error}`)
      }
    },
    getCellDisplayValue(rowNode, colId) {
      let value = ''

      if (this.gridApi && typeof this.gridApi.getCellValue === 'function') {
        value = this.gridApi.getCellValue({
          rowNode,
          colKey: colId,
          useFormatter: true
        })
      } else if (this.gridApi && typeof this.gridApi.getValue === 'function') {
        value = this.gridApi.getValue(colId, rowNode)
      } else if (rowNode && rowNode.data) {
        value = rowNode.data[colId]
      }

      return value === null || value === undefined ? '' : String(value)
    },
    writeClipboardText(text) {
      if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
        navigator.clipboard.writeText(text).then(() => {
          this.$message.success(this.$t('CopySucceeded'))
        }).catch((error) => {
          this.fallbackWriteClipboardText(text, error)
        })
        return
      }

      this.fallbackWriteClipboardText(text)
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
    }
  }
}
</script>

<style lang="scss" scoped>
.result-grid ::v-deep .ag-cell-focus {
  border-color: transparent !important;
}

.result-grid ::v-deep .chen-range-cell {
  --chen-range-top-shadow: 0 0 0 transparent;
  --chen-range-bottom-shadow: 0 0 0 transparent;
  --chen-range-left-shadow: 0 0 0 transparent;
  --chen-range-right-shadow: 0 0 0 transparent;
  background-color: rgba(47, 101, 202, 0.32) !important;
  box-shadow:
    var(--chen-range-top-shadow),
    var(--chen-range-bottom-shadow),
    var(--chen-range-left-shadow),
    var(--chen-range-right-shadow);
}

.result-grid ::v-deep .chen-range-top {
  --chen-range-top-shadow: inset 0 2px 0 #6ea1ff;
}

.result-grid ::v-deep .chen-range-bottom {
  --chen-range-bottom-shadow: inset 0 -2px 0 #6ea1ff;
}

.result-grid ::v-deep .chen-range-left {
  --chen-range-left-shadow: inset 2px 0 0 #6ea1ff;
}

.result-grid ::v-deep .chen-range-right {
  --chen-range-right-shadow: inset -2px 0 0 #6ea1ff;
}
</style>
