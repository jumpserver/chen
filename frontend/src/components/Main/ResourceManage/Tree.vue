<template>
  <div>
    <el-tree
      ref="tree"
      :data="data"
      :default-expanded-keys="expandedNodes"
      :load="loadNode"
      :props="props"
      :expand-on-click-node="false"
      :render-content="renderContent"
      lazy
      node-key="key"
      @node-click="handleNodeClick"
      @node-contextmenu="onContextmenu"
    />
  </div>
</template>

<script>

import { doAction, getActions, getResourceTreeChildren } from '@/api/resource'
import store from '@/store'

export default {
  name: 'Tree',
  components: {},
  data() {
    return {
      data: [],
      elementCaching: {},
      selectKey: [],
      cascaderProps: {
        expandTrigger: 'hover',
        value: 'key'
      },
      props: {
        label: 'label',
        children: 'children',
        isLeaf: 'leaf'
      },
      expandedNodes: [],
      treeClickCount: 0,
      treeClickTimer: null
    }
  },
  mounted() {
    this.$bus.$on('refresh_node', (data) => {
      const node = this.getTreeNode(data)
      const resolve = this.elementCaching[data]
      this.loadNode(node, resolve, true)
    })
  },
  methods: {
    onContextmenu(event, data, node) {
      getActions(node.data).then((actions) => {
        const items = actions.map(i => {
          if (!i.children) {
            return {
              ...i,
              onClick: () => {
                this.onContextMenuClick(node, i.key)
              }
            }
          } else {
            i.children = i.children.map(j => {
              return {
                ...j,
                onClick: () => {
                  this.onContextMenuClick(node, j.key)
                }
              }
            })
            return i
          }
        })
        if (!items.length) return
        this.$contextmenu({
          items: items,
          event,
          customClass: 'material-theme',
          zIndex: 120,
          minWidth: 180
        })
      })

      return false
    },

    getTreeNode(key) {
      return this.$refs.tree.getNode(key)
    },
    onContextMenuClick(node, action) {
      doAction(node.data, action).then(data => {
        switch (data.event) {
          case 'refresh_node':
            this.loadNode(node, this.elementCaching[data.data])
            break
          case 'new_query':
            this.$bus.$emit('new_query', data.data)
            break
          case 'view_data':
            this.$bus.$emit('view_data', data.data)
            break
          case 'new_form':
            this.$bus.$emit('new_form', { node: node.data, data: data.data })
            break
          case 'new_dialog':
            this.$bus.$emit('new_dialog', data.data)
        }
      })
    },
    handleNodeClick(data) {
      this.treeClickCount++
      window.clearTimeout(this.treeClickTimer)
      this.treeClickTimer = window.setTimeout(() => {
        this.treeClickCount = 0
      }, 440)
      if (this.treeClickCount > 2) return
      if (this.treeClickCount === 2) {
        doAction(data, 'show').then(resp => {
          switch (resp.event) {
            case 'view_data':
              this.$bus.$emit('view_data', resp.data)
              break
            default:
              this.expandedNodes = []
              this.expandedNodes.push(data.key)
              break
          }
        })
      }
    },
    onOpenTableView(databaseId, tableId) {
      this.$emit('onOpenTableView', { databaseId, tableId })
    },
    loadNode(node, resolve, force) {
      const leafTypes = ['table']
      if (!node.parent) {
        getResourceTreeChildren().then(data => {
          resolve(data)
          this.expandedNodes.push(data[0].key)
          this.$bus.$emit('new_query', data[0].key)
        })
      } else {
        this.elementCaching[node.data.key] = resolve
        getResourceTreeChildren(node.data, force).then(data => {
          data = data.map(item => {
            const i = { ...item }
            if (leafTypes.includes(i.type)) {
              i.leaf = true
              delete i.children
            }
            return i
          })
          resolve && resolve(data)
        }, () => {
          resolve && resolve([])
        })
      }
    },

    renderContent(h, { data }) {
      const label = data.label ? data.label : data.name
      let icon = 'folder'
      switch (data.type) {
        case 'table':
          icon = 'jurassic_table'
          break
        case 'datasource':
          icon = store.getters.profile?.dbType || 'database'
          break
        case 'field':
          icon = 'zidingyilie'
          break
        default:
          icon = data.type
      }

      const colorableTypes = ['database', 'schema', 'folder']
      const colorCls = colorableTypes.includes(data.type) ? 'colorable' : ''

      return (
        <span class={`${data.type} node`}>
          <span style='margin-right: 5px'>
            <i class={`iconfont icon-chen-${icon} ${colorCls}`}/></span>
          <span>{label}</span>
        </span>
      )
    }
  }
}
</script>

<style lang="scss" scoped>
@import '@/styles/variables';

.el-tree {
  background-color: #383a3c;
  color: $font-color;

  ::v-deep {
    .el-icon-caret-right {
      font-family: "iconfont" !important;
    }

    .el-tree-node__expand-icon.expanded {
      //-webkit-transform: rotate(0deg);
      //transform: rotate(0deg);

      .el-icon-caret-right:before {
        content: "\e723"
      }
    }

    .el-icon-caret-right:before {
      content: "\e60c"
    }

    .datasource {
      font-weight: bold;
    }

    .iconfont {
      &.colorable {
        color: #06AEFD;
      }
    }
  }
}

.right-menu {
  display: none;
  position: fixed;
  z-index: 999;
  font-size: 14px;
  background: #383a3c;
  box-shadow: 0px 0px 10px rgba(0, 0, 0, 0.2); /* 添加阴影效果 */
  border: 1px solid rgba(0, 0, 0, 0.1); /* 添加边框样式 */
  border-radius: 4px; /* 添加圆角 */
}

.right-menu a {
  width: 150px;
  height: 28px;
  line-height: 28px;
  text-align: center;
  display: block;
  color: $font-color;
  padding: 2px;
}

.right-menu a:hover {
  background: #bbb;
  color: #fff;
}

.menu-icon {
  width: 20px;
  height: 20px;
  margin-right: 5px;
  display: inline-block;
  vertical-align: middle;

  i {
    width: 15px;
  }
}

</style>
