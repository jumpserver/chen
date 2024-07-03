<template>
  <el-dialog
    v-if="visible"
    :title="$tc('SaveSQL')"
    :visible.sync="iVisible"
    width="40%"
  >
    <el-form ref="form" :model="form" label-width="80px">
      <el-form-item :label="$tc('Name')">
        <el-input v-model="form.name" />
      </el-form-item>
    </el-form>

    <span slot="footer" class="dialog-footer">
      <el-button @click="iVisible = false">{{ $tc('Cancel') }}</el-button>
      <el-button type="primary" @click="onSubmit">{{ $tc('Confirm') }}</el-button>
    </span>
  </el-dialog>
</template>

<script>
import axios from 'axios'
import VueCookie from 'vue-cookie'
import store from '@/store'

export default {
  name: 'SaveSnippetDialog',
  props: {
    visible: {
      type: Boolean,
      default: false
    },
    content: {
      type: String,
      default: ''
    }
  },
  data() {
    return {
      form: {
        name: ''
      }
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
    onSubmit() {
      const csrfToken = this.getCsrfToken()
      axios.post('/api/v1/ops/adhocs/', {
        name: this.form.name,
        args: this.content,
        module: store.getters.profile?.dbType
      }, {
        headers: {
          'X-CSRFToken': csrfToken
        }
      }).then(response => {
        this.$message.success(this.$tc('SaveSucceed'))
      }).catch(error => {
        this.$message.error(JSON.stringify((error.response.data)))
      }).finally(() => {
        this.iVisible = false
      })
    }
  }
}
</script>

<style scoped>

</style>
