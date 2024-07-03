<template>
  <div class="log">
    <div v-for="(item,index) of logs" :key="index">
      <span class="timestamp">
        [{{ item.timestamp | dateTime }}]
      </span>
      <span :style="{ color: logLevelColor[item.level] }" class="message">
        {{ capFirst(item.message) }}
      </span>
    </div>
  </div>
</template>

<script>
import { Subject } from 'rxjs'

export default {
  name: 'Log',
  props: {
    subject: {
      type: Subject,
      default: () => {
      }
    }
  },
  data() {
    return {
      logLevelColor: {
        3: '#00FF00',
        2: '#4C88FF',
        1: '#FFFF00',
        0: '#FF0000'
      },
      logs: []
    }
  },

  mounted() {
    this.subject.subscribe((data) => {
      this.logs.push(data)
    })
  },
  methods: {
    capFirst(string) {
      return string.charAt(0).toUpperCase() + string.slice(1)
    }
  }
}
</script>

<style lang="scss" scoped>
@import '@/styles/variables.scss';

.log {
  height: 100%;
  overflow: auto;
  box-sizing: border-box;

  .timestamp {
    color: $font-color;
    padding: 0 3px 0 5px;
  }
}
</style>
