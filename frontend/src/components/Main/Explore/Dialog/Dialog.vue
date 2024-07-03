<template>
  <el-dialog
    :title="meta.title"
    :visible.sync="iVisible"
    :width="meta.width"
    :close-on-click-modal="false"
    :destroy-on-close="true"
  >
    <div style="margin: 32px 10px;border-top: 1px">
      <div v-if="meta.type === 'detail'">
        <DetailContent :meta="meta" />
      </div>
      <div v-if="meta.type === 'form'">
        <DetailContent />
      </div>
    </div>
  </el-dialog>
</template>

<script>
import DetailContent from '@/components/Main/Explore/Dialog/components/DetailContent.vue'

export default {
  name: 'Dialog',
  components: { DetailContent },
  props: {
    visible: {
      type: Boolean,
      default: false
    },
    meta: {
      type: Object,
      default: () => {
        return {
          title: '数据源详情',
          type: 'detail',
          width: '30%',
          items: [
            {
              name: 'name',
              label: '名称',
              type: 'text',
              value: 'mysql5'
            }
          ]
        }
      }
    }
  },
  data() {
    return {
      aaa: true

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
  methods: {
    onClose() {
      this.iVisible = false
    }
  }
}
</script>

<style scoped>

</style>
