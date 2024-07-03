<template>
  <el-alert
    v-show="opened"
    :description="message"
    :type="type"
    class="query-message"
    @close="onClose"
  />
</template>

<script>
import { Subject } from 'rxjs'

export default {
  name: 'Message',
  props: {
    subject: {
      type: Subject,
      default: () => {
      }
    }
  },
  data() {
    return {
      opened: false,
      title: '',
      message: '',
      type: 'info',
      closeDelay: 5
    }
  },
  mounted() {
    this.subject.subscribe((data) => {
      this.title = data.title
      this.message = data.message
      this.type = data.type
      this.opened = true
      this.closeDelay = data.closeDelay || 5

      setTimeout(() => {
        this.opened = false
      }, this.closeDelay * 1000)
    })
  },
  methods: {
    onClose() {
      this.opened = false
    }
  }

}
</script>

<style scoped>
</style>
