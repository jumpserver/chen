<template>
  <ul
    v-show="menuVisible"
    class="menus"
    :style="{ top: menuTop, left: menuLeft }"
  >
    <li
      v-for="menu in iMenus"
      :key="menu.name"
      :class="['menu', menu.children? '' : 'menu-hover']"
      @click.stop="clickMenu(menu)"
    >
      <div @mouseenter="showSubMenu(menu)">
        <i :class="menu.icon" style="margin-right: 5px;" />
        <span>{{ menu.title }}</span>
        <i v-if="menu.children" class="el-icon-arrow-right" style="margin-left: 5px;" />
      </div>
      <ul v-show="subMenuVisible" class="submenu">
        <li
          v-for="subMenu in menu.children"
          :key="subMenu.name"
          class="submenu-item menu-hover"
          @click.stop="clickMenu(subMenu)"
        >
          <div>
            <i :class="subMenu.icon" style="margin-right: 5px;" />
            <span>{{ subMenu.title }}</span>
          </div>
        </li>
      </ul>
    </li>
  </ul>
</template>

<script>
export default {
  name: 'RightMenu',
  props: {
    menus: {
      type: Array,
      default: () => []
    }
  },
  data() {
    return {
      menuTop: '0px',
      menuLeft: '0px',
      menuVisible: false,
      subMenuVisible: false
    }
  },
  computed: {
    iMenus() {
      return this.menus.filter((m) => {
        if (typeof m.hidden === 'function') {
          return !m.hidden()
        }
        return !m.hidden
      })
    }
  },
  methods: {
    clickMenu(menu) {
      if (typeof menu.callback === 'function') {
        menu.callback()
      }
    },
    show(event) {
      if (this.iMenus.length === 0) {
        return
      }
      this.menuVisible = true
      const { clientX: x, clientY: y } = event
      const { innerWidth: innerWidth, innerHeight: innerHeight } = window
      const menuWidth = 180
      const menuHeight = this.iMenus.length * 30
      this.menuTop = (y + menuHeight > innerHeight ? innerHeight - menuHeight : y) + 'px'
      this.menuLeft = (x + menuWidth > innerWidth ? innerWidth - menuWidth : x) + 'px'
      document.addEventListener('mouseup', this.hide, false)
    },
    hide(e) {
      if (e.button === 0) {
        this.menuVisible = false
        this.subMenuVisible = false
        document.removeEventListener('mouseup', this.hide)
      }
    },
    showSubMenu(menu) {
      this.subMenuVisible = !!menu.children
    }
  }
}
</script>

<style lang='scss' scoped>
.menu-hover:hover {
  color: #2f65ca;
}

.menus {
  background: #fff;
  border-radius: 4px;
  list-style-type: none;
  padding: 3px;
  position: fixed;
  z-index: 9999;
  display: block;

  .menu {
    padding: 6px 12px;
    cursor: pointer;

    .submenu {
      background: #fff;
      list-style-type: none;
      padding: 3px;
      position: absolute;
      top: 0;
      left: 100%;
    }

    .submenu-item {
      display: block;
      white-space: nowrap;
      padding: 6px 12px;
      cursor: pointer;
    }
  }
}
</style>
