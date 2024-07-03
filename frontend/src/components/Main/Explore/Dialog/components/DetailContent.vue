<template>
  <table>
    <tr v-for="(item,index) in meta.items" :key="index">
      <td style="width: 30%">{{ item.label }}</td>
      <td>{{ item.value }}</td>
    </tr>
  </table>
</template>

<script>
export default {
  name: 'DetailContent',
  props: {
    meta: {
      type: Object,
      default: () => {
        return {}
      }
    }
  },
  mounted() {
    console.log(this.meta)
  }
}
</script>

<style scoped>
table {
  color: #e9e9e9;
  width: 100%;
  border-collapse: collapse;
}

td {
  padding: 8px;
  text-align: left;
  border: 1px solid #949292;
}

tr:nth-child(even) {
  background-color: #484747;
}
</style>
