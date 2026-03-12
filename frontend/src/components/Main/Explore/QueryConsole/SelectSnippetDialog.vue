<template>
  <el-dialog
    v-if="visible"
    :title="$tc('SelectSQL')"
    :visible.sync="iVisible"
    width="40%"
    :modal="false"
  >
    <div>
      <el-table class="snippet-table" :data="snippets">
        <el-table-column property="name" :label="$tc('Name')" width="120px" />
        <el-table-column show-overflow-tooltip property="args" :label="$tc('Content')" />
        <el-table-column width="80px" label="">
          <template slot-scope="scope">
            <el-button type="text" size="small" @click="onSelectSnippet(scope.row)">
              {{ $tc('Insert') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
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

::v-deep .snippet-table td.el-table__cell {
  background: #383a3c;
  border-bottom: 1px solid #7c7c7e;
}

::v-deep .snippet-table tr:hover td.el-table__cell {
  background: #4a4d50;
}

::v-deep .snippet-table th.el-table__cell {
  background: #383a3c;
}

.snippet-table {
  color: #e9e9e9;
}

</style>
