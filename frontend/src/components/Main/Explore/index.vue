<template>
  <div class="explore_body">
    <Dialog :visible.sync="dialogVisible" :meta="dialogMeta"/>
    <FormTemplate
      v-if="formTemplateVisible"
      :form-options="formOptions"
      :visible.sync="formTemplateVisible"
    />
    <Message :subject="subjects.globalMessageSubject"/>
    <el-tabs v-model="activeTab" closable type="card" @tab-remove="onCloseTab">
      <el-tab-pane
        v-for="(item) in tabs"
        :key="item.name"
        :name="item.name"
      >
        <span v-if="!item.loading" slot="label" class="tab-pane-label">
          <i class="iconfont" :class="'icon-chen-' + item.icon"/>
          <el-tooltip effect="dark" :content="item.title">
            <span>{{ item.title }}</span>
          </el-tooltip>
        </span>
        <span v-if="item.loading" slot="label" class="tab-pane-label">
          <i class="fa fa-spinner fa-spin"/>
          Loading
        </span>
        <component
          :is="item.component"
          :ref="item.name"
          :tab="item"
          :node-key="item.nodeKey"
          :global-message-subject="subjects.globalMessageSubject"
          @changeTab="onChangeTab"
          @close="onCloseTab"
        />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script>
import FormTemplate from '@/components/Main/Explore/Dialog/FormTemplate.vue'
import Message from '@/components/Main/Explore/Message.vue'
import { Subject } from 'rxjs'
import Dialog from '@/components/Main/Explore/Dialog/Dialog.vue'

export default {
  name: 'Explore',
  components: {
    Dialog,
    Message,
    FormTemplate,
    QueryConsole: () => import('./QueryConsole/index.vue'),
    DataView: () => import('./DataView/index.vue')
  },
  data() {
    return {
      dialogMeta: {},
      dialogVisible: false,
      subjects: {
        globalMessageSubject: new Subject()
      },
      formTemplateVisible: false,
      formOptions: {
        node: {},
        formMeta: {}
      },
      tabNum: 0,
      activeTab: '0',
      tabs: []
    }
  },
  computed: {
    isEmptyExplore: function() {
      return this.tabs && this.tabs.length === 0
    }
  },
  mounted() {
    this.watchEventBus()
  },
  methods: {
    onChangeTab(title) {
      this.tabs.forEach(tab => {
        if (tab.title === title) {
          this.activeTab = tab.name
        }
      })
    },
    onCloseTab(name, changeTab = true) {
      this.tabs = this.tabs.filter(item => item.name !== name)
      if (this.tabs.length > 0) {
        if (changeTab) {
          this.activeTab = this.tabs[this.tabs.length - 1].name
        }
      }
    },
    watchEventBus() {
      this.$bus.$on('new_form', (data) => {
        this.formOptions = data
        this.formTemplateVisible = true
      })
      this.$bus.$on('new_query', (data) => {
        this.tabNum++
        this.tabs.push({
          title: 'Query',
          loading: true,
          name: '' + this.tabNum,
          component: 'QueryConsole',
          icon: 'query',
          nodeKey: data
        })
        this.activeTab = '' + this.tabNum
      })

      this.$bus.$on('view_data', (data) => {
        this.tabNum++
        this.tabs.push({
          title: 'DataView',
          name: '' + this.tabNum,
          component: 'DataView',
          icon: 'jurassic_table',
          nodeKey: data
        })
        this.activeTab = '' + this.tabNum
      })
      this.$bus.$on('new_dialog', (data) => {
        this.dialogMeta = data
        this.dialogVisible = true
      })
    }
  }
}
</script>

<style lang="scss" scoped>
.explore_body {
  height: 100vh;
  background-color: #2B2B2B;
  padding: 0 1px;
}

.content_holder {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%);
  color: #FFFFFF;
  text-align: center;
}

.nav-tab {
  position: relative;
  height: 100%;
}

</style>
