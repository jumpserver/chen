<template>
  <el-tabs v-model="activeTab" type="card" @tab-remove="onTabClose">
    <el-tab-pane
      :closable="false"
      name="log"
    >
      <span slot="label" class="tab-pane-label">
        <i class="el-icon-tickets" />
        {{ $tc('LogOutput') }}
      </span>
      <Log :subject="subjects.logSubject" style="padding: 5px" />
    </el-tab-pane>

    <el-tab-pane
      v-for="item in tabs"
      :key="item.name"
      :name="item.name"
      closable
    >
      <span slot="label" class="tab-pane-label">
        <i class="icon iconfont icon-chen-jurassic_table" />
        <el-tooltip effect="dark" :content="item.title">
          <span>{{ item.title }}</span>
        </el-tooltip>
      </span>
      <DataView
        :key="item.name"
        :ref="item.name"
        :meta="item.meta"
        :data="item.data"
        :state-subject="subjects.stateSubject"
        :update-subject="subjects.updateResultSubject"
        :tool-bar-items="item.extraToolBarItems"
        @action="onAction(item.name, $event)"
        @limitChange="onLimitChange($event)"
      />
    </el-tab-pane>
  </el-tabs>
</template>
<script>

import DataView from '@/components/Main/Explore/DataView/DataView.vue'

export default {
  components: {
    DataView,
    Log: () => import('./Log.vue')
  },
  props: {
    subjects: {
      type: Object,
      default: () => ({})
    }
  },
  data() {
    return {
      tabNum: 0,
      activeTab: 'log',
      tabs: []
    }
  },
  mounted() {
    this.subjects.newResultSubject.subscribe((data) => {
      this.tabs.push({
        title: data.title,
        name: data.title,
        meta: data,
        data: null,
        extraToolBarItems: {
          pinned: {
            split: true,
            type: 'button',
            icon: () => {
              const ref = this.$refs[data.title]
              if (ref) {
                const state = ref[0].getState()
                return state.pinned ? 'icon-chen-pin-fill text-primary' : 'icon-chen-pin-fill'
              }
              return 'icon-chen-pin-fill'
            },
            onClick: () => {
              this.onAction(data.title, { action: 'toggle_pinned' })
            }
          }
        }
      })
      this.activeTab = data.title
    })

    this.subjects.updateResultSubject.subscribe((data) => {
      this.tabs.forEach((tab) => {
        if (tab.name === data.title) {
          tab.data = data.data
          this.activeTab = data.title
        }
      })
    })
    this.subjects.deleteResultSubject.subscribe((data) => {
      if (data instanceof String) {
        this.onTabClose(data, false)
      }
      if (data instanceof Array) {
        data.forEach((item) => {
          this.onTabClose(item, false)
        })
      }
      if (data instanceof Object) {
        this.onTabClose(data.sql, false)
      }
    })
  },
  methods: {
    onAction(dataView, action) {
      action.dataView = dataView
      this.$emit('dataViewAction', action)
    },
    onLimitChange(limit) {
      this.$emit('limitChange', limit)
    },
    onTabClose(name, send = true) {
      if (name === 'log') {
        return
      }
      this.tabs = this.tabs.filter((tab) => {
        return tab.name !== name
      })
      if (send) {
        this.$emit('closeDataView', name)
      }
      if (this.tabs.length === 0) {
        this.activeTab = 'log'
      } else {
        this.activeTab = this.tabs[this.tabs.length - 1].name
      }
    }
  }
}
</script>

<style lang="scss" scoped>
.el-tabs {
  height: calc(100% - 22px);
  // display: flex;
  // flex-direction: column;
}
</style>
