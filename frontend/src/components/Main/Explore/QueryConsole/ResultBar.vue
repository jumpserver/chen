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
        :editable="true"
        :preview-before-save="true"
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
            disabled: () => {
              const ref = this.getDataViewRef(data.title)
              return !!(ref && ref.requestBusy)
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
          const ref = this.getDataViewRef(tab.name)
          if (ref && !ref.acceptDataResponse()) {
            return
          }
          tab.data = data.data
          this.activeTab = data.title
        }
      })
    })
    this.subjects.deleteResultSubject.subscribe((data) => {
      if (data instanceof String) {
        this.onTabClose(data, false, false)
      }
      if (data instanceof Array) {
        data.forEach((item) => {
          this.onTabClose(item, false, false)
        })
      }
      if (data instanceof Object) {
        this.onTabClose(data.sql, false, false)
      }
    })
    this.subjects.saveChangesResultSubject.subscribe((data) => {
      this.handleSaveChangesResult(data)
    })
    this.subjects.saveChangesPreviewResultSubject.subscribe((data) => {
      this.handleSaveChangesPreviewResult(data)
    })
  },
  methods: {
    onAction(dataView, action) {
      const ref = this.getDataViewRef(dataView)
      if (this.isDataRequestAction(action) && ref && !action.clientRequestSequence) {
        ref.startDataRequest(action)
        return
      }
      if (this.shouldGuardDirty(action) && ref && ref.hasDirty()) {
        this.$confirm('There are unsaved changes. Discard them and continue?', 'Warning', {
          confirmButtonText: 'Confirm',
          cancelButtonText: 'Cancel',
          type: 'warning'
        }).then(() => {
          ref.clearDirty()
          this.emitDataViewAction(dataView, action)
        }).catch(() => {
          ref.cancelClientRequest(action.clientRequestSequence)
        })
        return
      }
      this.emitDataViewAction(dataView, action)
    },
    emitDataViewAction(dataView, action) {
      const request = { ...action }
      delete request.clientRequestSequence
      this.$emit('dataViewAction', {
        ...request,
        dataView
      })
    },
    shouldGuardDirty(action) {
      return action && ['first_page', 'prev_page', 'next_page', 'last_page', 'refresh', 'change_limit'].includes(action.action)
    },
    isDataRequestAction(action) {
      return action && [
        'first_page',
        'prev_page',
        'next_page',
        'last_page',
        'refresh',
        'change_limit',
        'toggle_pinned'
      ].includes(action.action)
    },
    getDataViewRef(dataView) {
      const ref = this.$refs[dataView]
      return Array.isArray(ref) ? ref[0] : ref
    },
    onLimitChange(limit) {
      this.$emit('limitChange', limit)
    },
    handleSaveChangesResult(result) {
      if (!result || !result.dataView) {
        return
      }
      const ref = this.getDataViewRef(result.dataView)
      if (ref && typeof ref.handleSaveChangesResult === 'function') {
        ref.handleSaveChangesResult(result)
      }
    },
    hasDirty() {
      return this.tabs.some((tab) => {
        const ref = this.getDataViewRef(tab.name)
        return ref && typeof ref.hasDirty === 'function' && ref.hasDirty()
      })
    },
    clearDirty() {
      this.tabs.forEach((tab) => {
        const ref = this.getDataViewRef(tab.name)
        if (ref && typeof ref.clearDirty === 'function') {
          ref.clearDirty()
        }
      })
    },
    onTabClose(name, send = true, guardDirty = true) {
      if (name === 'log') {
        return
      }
      const ref = this.getDataViewRef(name)
      if (guardDirty && ref && typeof ref.hasDirty === 'function' && ref.hasDirty()) {
        this.$confirm('There are unsaved changes. Discard them and close?', 'Warning', {
          confirmButtonText: 'Confirm',
          cancelButtonText: 'Cancel',
          type: 'warning'
        }).then(() => {
          ref.clearDirty()
          this.closeTab(name, send)
        }).catch(() => {})
        return
      }
      this.closeTab(name, send)
    },
    closeTab(name, send = true) {
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
