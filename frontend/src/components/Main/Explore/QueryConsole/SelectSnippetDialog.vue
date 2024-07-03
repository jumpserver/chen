<template>
  <el-dialog
      v-if="visible"
      :title="$tc('title.select_sql')"
      :visible.sync="iVisible"
      width="40%"
  >
    <span>
      <el-table :data="snippets">
        <el-table-column property="name" :label="$tc('common.name')" width="120px"/>
        <el-table-column show-overflow-tooltip property="args" :label="$tc('common.content')"/>
        <el-table-column width="80px" label="">
          <template slot-scope="scope">
            <el-button type="text" size="small" @click="onSelectSnippet(scope.row)">{{
                $tc('button.insert')
              }}</el-button>
          </template>
        </el-table-column>
      </el-table>

    </span>
  </el-dialog>
</template>

<script>
import store from '@/store'
import { getSnippets } from '@/api/jms'

export default {
  name: 'SelectSnippetDialog',
  props: {
    visible: {
      type: Boolean,
      default: false
    }
  },
  data() {
    return {
      snippets: []
    }
  },
  computed: {
    iVisible: {
      get() {
        return this.visible
      },
      set(val) {
        this.$emit('update:visible', val)
      }
    }
  },
  mounted() {
    this.loadSnippets()
  },
  methods: {
    loadSnippets() {
      const sqlType = store.getters.profile?.dbType
      getSnippets().then(data => {
        this.snippets = data.filter(item => item.module.value === sqlType)
      })
    },
    onSelectSnippet(item) {
      this.$emit('select', item.args)
    }
  }
}
</script>

<style scoped>
::v-deep .el-table tr {
  background-color: #383a3c;
}

::v-deep .el-table tr:hover {
  background-color: #7c7c7e !important;
}

::v-deep .el-table--enable-row-hover .el-table__body tr:hover > td.el-table__cell {
  background-color: #7c7c7e !important;
}

::v-deep .el-table td.el-table__cell, .el-table th.el-table__cell.is-leaf {
  border-bottom: 1px solid #7c7c7e
}

::v-deep .el-table th.el-table__cell {
  background-color: #383a3c;
}

.el-table {
  color: #e9e9e9;
}
</style>
