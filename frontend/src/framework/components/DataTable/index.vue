<template>
  <HotTable
    ref="hostTable"
    :hot-settings="iHotSettings"
    :data="data.data"
    class="hot-table"
  />
</template>

<script>
export default {
  name: 'DataTable',
  props: {
    settings: {
      type: Object,
      default: () => ({})
    },
    data: {}
  },
  computed: {
    iHotSettings() {
      return Object.assign(this.defaultSettings, this.settings)
    },
    hotInstance() {
      return this.$refs.hostTable.hotInstance
    }
  },
  mounted() {
  },
  data() {
    return {
      defaultSettings: {}
    }
  },
  methods: {
    initTable() {
      const headers = this.data.fields.map((item) => item.name)
      const columns = this.data.fields.map((item) => {
        return {
          data: item.name,
          type: 'text',
          readOnly: true
        }
      })
      this.defaultSettings.colHeaders = headers
      this.defaultSettings.columns = columns
      this.hotInstance.updateSettings(this.iHotSettings, false)
      this.hotInstance.render()
    }
  }
}
</script>

<style scoped>

</style>
