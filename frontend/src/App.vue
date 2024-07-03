<template>
  <div id="app" v-if="ready">
    <AppMain/>
  </div>
</template>

<script>

import AppMain from '@/components/AppMain.vue'

export default {
  name: 'App',
  components: {
    AppMain
  },
  computed: {
    ready() {
      return this.$store.getters.i18nLoaded
    }
  }
}
</script>

<style>
html, body {
  height: 100%;
  overflow: hidden;
}

#app {
  font-family: "open sans", "Helvetica Neue", Helvetica, Arial, sans-serif;
  font-size: 13px;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  text-align: center;
  color: #2c3e50;
}
</style>
