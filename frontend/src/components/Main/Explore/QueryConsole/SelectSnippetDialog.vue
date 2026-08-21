<template>
  <el-dialog
    v-if="visible"
    :title="$tc('SelectSQL')"
    :visible.sync="iVisible"
    width="40%"
    :modal="false"
    :append-to-body="true"
  >
    <div>
      <el-table v-loading="loading" class="snippet-table" :data="snippets">
        <el-table-column property="name" :label="$tc('Name')" width="120px" />
        <el-table-column show-overflow-tooltip property="args" :label="$tc('Content')" />
        <el-table-column width="140px" label="">
          <template slot-scope="scope">
            <el-button type="text" size="small" @click="onSelectSnippet(scope.row)">
              {{ $tc('Insert') }}
            </el-button>
            <el-button type="text" size="small" @click="onDeleteSnippet(scope.row)">
              {{ $tc('Delete') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-if="total > pageSize"
        class="snippet-pagination"
        background
        layout="total, prev, pager, next"
        :current-page="currentPage"
        :page-size="pageSize"
        :total="total"
        @current-change="onPageChange"
      />
    </div>
  </el-dialog>
</template>

<script>
import axios from 'axios'
import VueCookie from 'vue-cookie'
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
      snippets: [],
      currentPage: 1,
      pageSize: 10,
      total: 0,
      loading: false
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
    getCsrfToken() {
      let cookieNamePrefix = VueCookie.get('SESSION_COOKIE_NAME_PREFIX')
      if (!cookieNamePrefix || ['""', '\'\''].indexOf(cookieNamePrefix) > -1) {
        cookieNamePrefix = ''
      }
      const TOKEN_KEY = `${cookieNamePrefix}csrftoken`
      return VueCookie.get(TOKEN_KEY)
    },
    loadSnippets() {
      const sqlType = store.getters.profile?.dbType
      this.loading = true
      getSnippets({
        module: sqlType,
        limit: this.pageSize,
        offset: (this.currentPage - 1) * this.pageSize,
        order: '-date_updated'
      }).then(data => {
        this.snippets = data.results
        this.total = data.count
      }).finally(() => {
        this.loading = false
      })
    },
    onPageChange(page) {
      this.currentPage = page
      this.loadSnippets()
    },
    onSelectSnippet(item) {
      this.$emit('select', item.args)
    },
    onDeleteSnippet(item) {
      this.$confirm(this.$tc('ConfirmDelete'), this.$tc('Warning'), {
        confirmButtonText: this.$tc('Confirm'),
        cancelButtonText: this.$tc('Cancel'),
        type: 'warning'
      }).then(() => {
        const csrfToken = this.getCsrfToken()
        axios.delete(`/api/v1/ops/adhocs/${item.id}/`, {
          headers: {
            'X-CSRFToken': csrfToken
          }
        }).then(() => {
          this.$message.success(this.$tc('DeleteSuccess'))
          if (this.snippets.length === 1 && this.currentPage > 1) {
            this.currentPage -= 1
          }
          this.loadSnippets()
        })
      }).catch(() => {})
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

.snippet-pagination {
  margin-top: 16px;
  text-align: right;
}

</style>
