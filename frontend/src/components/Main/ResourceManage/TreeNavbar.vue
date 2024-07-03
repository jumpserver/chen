<template>
  <div class="navbar">
    <span v-if="title" class="title">
      {{ title }}
    </span>
  </div>
</template>

<script>

export default {
  components: {
  },
  props: {
    title: {
      type: String,
      default: ''
    }
  },
  data() {
    return {
    }
  },
  methods: {
  }
}
</script>

<style scoped lang="scss">
@import '@/styles/variables.scss';

.navbar {
  display: flex;
  .title {
    height: 28px;
    line-height: 28px;
    padding: 0 10px;
    color: #FFFFFF;
  }
  .toolbar {
    flex: 1;
    display: flex;
  }
}
</style>
