import Vue from 'vue'
import App from './App.vue'
import ElementUI from 'element-ui'
import 'element-ui/lib/theme-chalk/index.css'
import 'font-awesome/css/font-awesome.min.css'
import '@/assets/iconfont/iconfont.css'
import VueRx from 'vue-rx'
import 'codemirror/lib/codemirror.css'
import VueCodemirror from 'vue-codemirror'
import i18n from './i18n'
import request from './request'
import store from './store'
import '@/styles/index.scss'
import moment from 'moment'
import Contextmenu from 'vue-contextmenujs'

Vue.config.productionTip = false

Vue.use(VueRx)
Vue.use(VueCodemirror)
Vue.use(request)
Vue.use(Contextmenu)

Vue.use(ElementUI, {
  size: 'small',
  i18n: (key, value) => i18n.t(key, value)
})

Vue.filter('dateTime', function(value) {
  return moment(value * 1).format('YYYY-MM-DD HH:mm:ss')
})

new Vue({
  i18n,
  store,
  beforeCreate() {
    Vue.prototype.$bus = this
  },
  render: h => h(App)
}).$mount('#app')
