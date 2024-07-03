<template>
  <div :style="iStyle" class="toolbar">
    <span class="left">
      <Items :items="defaultItems" :setting="iSettings" v-bind="$attrs"/>
    </span>
    <span class="right">
      <Items :items="rightItems" :setting="iSettings" v-bind="$attrs"/>
    </span>
  </div>
</template>

<script>
import Items from './Items.vue'

export default {
  name: 'Toolbar',
  components: {
    Items
  },
  props: {
    title: {
      type: String,
      default: ''
    },
    items: {
      type: Object,
      default() {
        return {}
      }
    },
    leftItems: {
      type: Object,
      default() {
        return {}
      }
    },
    rightItems: {
      type: Object,
      default() {
        return {}
      }
    },
    settings: {
      type: Object,
      default() {
        return {
          size: 'mini',
          style: {}
        }
      }
    }
  },
  data() {
    return {
      defaultItems: {}
    }
  },
  computed: {
    iStyle() {
      const defaultStyle = {
        'text-align': 'left',
        'padding': '0 2px',
        'background-color': '#383a3c'
      }
      return Object.assign(defaultStyle, this.settings.style)
    },
    iSettings() {
      const defaultSetting = {}
      return Object.assign(defaultSetting, this.settings)
    }
  },
  created() {
    this.defaultItems = { ...this.items, ...this.leftItems }
  }
}
</script>

<style>
::v-deep .el-input--mini .el-input__inner {
  height: 26px !important;
  line-height: 26px !important;
}

::v-deep .el-input__inner {
  background: transparent !important;
}
</style>

<style lang="scss" scoped>
@import '@/styles/variables.scss';

.toolbar {
  display: flex;
  justify-content: space-between;
  height: 28px;
  line-height: 29px;
  overflow: hidden;
  background: $background-color;

  .split {
    border-left: 1px solid #5F5F5F;
    margin: 0 8px;
  }

  .toolbar-dropdown {
    height: 28px;
    line-height: 28px;
    font-size: 12px;
    vertical-align: top;
    color: $icon-color;
  }
}

.toolbar-btn {
  background-color: transparent;
  border: 0px;
  margin-right: 6px;
  color: $icon-color;
}

.el-button--mini, .el-button--mini.is-round {
  padding: 4px;
}

.el-button + .el-button {
  margin-left: 0;
}

::v-deep .el-button:focus, .el-button:hover {
  border-color: #505254;
  background-color: #505254;
}

.toolbar-select {
  .el-input__inner {
    background: transparent !important;
  }

  &.el-input--mini .el-input__inner {
    height: 26px !important;
    line-height: 26px !important;
  }
}
</style>
