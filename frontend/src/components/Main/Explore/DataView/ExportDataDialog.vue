<template>
  <el-dialog
    v-if="visible"
    :title="$tc('ExportData')"
    :visible.sync="iVisible"
    width="40%"
  >
    <el-form ref="form" :model="form" label-width="80px">
      <el-radio v-model="form.scope" label="current">{{ $tc('ExportCurrent') }}</el-radio>
      <el-radio v-model="form.scope" label="all">{{ $tc('ExportAll') }}</el-radio>
    </el-form>

    <span slot="footer" class="dialog-footer">
      <el-button @click="iVisible = false">{{ $tc('Cancel') }}</el-button>
      <el-button type="primary" @click="onSubmit">{{ $tc('Confirm') }}</el-button>
    </span>
  </el-dialog>
</template>

<script>
// import { index } from '@/request'

export default {
  name: 'ExportDataDialog',
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
        scope: 'current'
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
    onSubmit() {
      this.$emit('submit', this.form.scope)
    }
  }
}
</script>

<style scoped>

</style>
