<template>
  <div>
    <span v-for="item in items" :key="item.name && item.name()">
      <span v-if="item.split" class="split" />
      <el-tooltip
        :content="item.tip"
        :disabled="!item.tip"
        class="item"
        placement="top"
      >
        <span
          v-if="item.type === 'text'"
          v-show="!getValue(item,'hidden')"
          class="toolbar-dropdown "
        >
          <span class="el-dropdown-link">
            {{ getValue(item, 'value') }}
          </span>
        </span>

        <el-button
          v-if="item.type === 'button'"
          v-show="!getValue(item,'hidden')"
          :size="settings.size"
          :disabled="getValue(item,'disabled')"
          :icon="getValue(item,'icon')"
          :style="getValue(item,'style')"
          class="toolbar-btn iconfont"
          @click="item.onClick(item)"
        >
          {{ item.name && item.name() }}
        </el-button>

        <el-dropdown
          v-if="item.type==='dropdown'"
          v-show="!getValue(item,'hidden')"
          class="toolbar-dropdown"
          v-bind="item"
          :disabled="getValue(item,'disabled')"
          :style="getValue(item,'style')"
          @click="item.onClick && item.onClick($event)"
          @command="item.onCommand && item.onCommand($event)"
        >
          <span class="el-dropdown-link">
            <span class="el-dropdown-link">
              {{ item.customDisplayContent && item.customDisplayContent(item) }}
              <i class="el-icon-arrow-down el-icon--right" />
            </span>
          </span>
          <el-dropdown-menu slot="dropdown">
            <el-dropdown-item
              v-for="(i) in item.options"
              :key="i.value"
              :command="i.value"
              v-bind="i"
            >
              {{ i.label }}
            </el-dropdown-item>
          </el-dropdown-menu>
        </el-dropdown>
      </el-tooltip>
    </span>
  </div>
</template>

<script>
export default {
  props: {
    items: {
      type: Object,
      default: () => ({})
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
  methods: {
    getValue(item, key) {
      if (item[key] instanceof Function) {
        return item[key]()
      }
      return item[key]
    }
  }
}
</script>

<style scoped lang="scss">
@import '@/styles/variables.scss';

.toolbar-btn {
  background-color: transparent;
  border: 0px;
  margin-right: 6px;
  color: $icon-color;
}

.split {
  border-left: 1px solid #5F5F5F;
  margin: 0 8px 0 2px;
}

.toolbar-dropdown {
  height: 28px;
  line-height: 28px;
  font-size: 12px;
  vertical-align: top;
  color: $icon-color;
  margin-right: 6px;
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

.el-dropdown-menu {
  max-height: 300px;
  overflow: scroll;
}
</style>
