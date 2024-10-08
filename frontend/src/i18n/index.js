// i18n.js
import Vue from 'vue'
import locale from 'element-ui/lib/locale'
import VueI18n from 'vue-i18n'
import messages from './langs'
import date from './date'
import VueCookie from 'vue-cookie'
import axios from 'axios'
import store from '@/store'

Vue.use(VueI18n)
const cookieLang = VueCookie.get('django_language')
const storeLang = VueCookie.get('lang')
const browserLang = navigator.systemLanguage || navigator.language
let lang = cookieLang || storeLang || browserLang || 'zh'
if (lang === 'zh-hant') {
  lang = 'zh_hant'
} else {
  lang = lang.slice(0, 2)
}
const i18n = new VueI18n({
  locale: lang,
  fallbackLocale: 'en',
  silentFallbackWarn: true,
  silentTranslationWarn: true,
  dateTimeFormats: date,
  messages
})
locale.i18n((key, value) => i18n.t(key, value)) // 重点: 为了实现element插件的多语言切换

Vue.prototype.$tr = (key) => {
  return i18n.t('' + key)
}

axios.get(`/api/v1/settings/i18n/chen/?lang=${lang}&flat=0`)
  .then((res) => {
    if (res.status !== 200) {
      return
    }
    const data = res.data
    for (const key in data) {
      if (Object.prototype.hasOwnProperty.call(data, key)) {
        i18n.mergeLocaleMessage(key, data[key])
      }
    }
  })
  .finally(() => {
    store.dispatch('app/setI18nLoaded', true)
  })

export default i18n
