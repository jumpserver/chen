<template>
  <div id="app">
    <AppMain/>
  </div>
</template>

<script>

import AppMain from '@/components/AppMain.vue'

export default {
  name: 'App',
  components: {
    AppMain
  }
}
</script>

<style>
html, body {
  height: 100%;
  overflow: hidden;
}

#app {
  font-family: "SF Mono", Monaco, Consolas, monospace, Avenir, Helvetica, Arial, sans-serif;
  font-size: 13px;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  text-align: center;
  color: #2c3e50;
}
</style>
