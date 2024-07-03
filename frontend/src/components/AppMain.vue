<template>
  <div class="layout-container">
    <Controller @ready="onControllerReady" @close="onClose" />
    <split-pane
      v-if="ready"
      :min-percent="20"
      :default-percent="20"
      split="vertical"
    >
      <template #paneL>
        <resource-manage />
      </template>
      <template #paneR>
        <explore ref="explore" />
      </template>
    </split-pane>
  </div>
</template>

<script>

import Controller from '@/components/Controller/index.vue'
import Explore from '@/components/Main/Explore/index.vue'
import ResourceManage from '@/components/Main/ResourceManage/index.vue'
import SplitPane from 'vue-splitpane'

export default {
  name: 'Index',
  components: {
    Controller,
    Explore,
    ResourceManage,
    SplitPane
  },
  data() {
    return {
      ready: false
    }
  },
  methods: {
    onControllerReady() {
      this.ready = true
    },
    onClose() {
      this.ready = false
    }
  }
}
</script>

<style scoped>
.layout-container {
  height: 100vh;
  background-color: #383a3c;
}
</style>
