<template>
  <div class="container" style="z-index: 99;position: relative">
    <div class="toolbar-panel">
      <TreeNavbar
        :title="$tc('DatabaseExplorer')"
      />
    </div>
    <div class="tree-panel">
      <tree/>
    </div>
  </div>
</template>

<script>
import Tree from '@/components/Main/ResourceManage/Tree.vue'
import TreeNavbar from './TreeNavbar.vue'

export default {
  name: 'ResourceManage',
  components: {
    Tree,
    TreeNavbar
  },
  data() {
    return {
      treeData: []
    }
  },
  methods: {}
}
</script>

<style lang="scss" scoped>
@import '@/styles/variables.scss';

.container {
  height: 100%;
  background-color: $background-color;
}

.toolbar-panel {
  border-bottom: 1px solid $framework-border-color;
}

.tree-panel {
  height: calc(100% - 35px);
  overflow: auto;
  padding-top: 6px;
  background-color: $background-color;
}
</style>
