import Vue from 'vue'
import VueI18n from 'vue-i18n'

Vue.use(VueI18n)

const LOADED_LANGUAGES = ['zh-CN', 'en-US', 'ja-JP', 'zh-Hant']
const LANG_FILES = require.context('./lang', true, /\.js$/)

const messages = LANG_FILES.keys().reduce((messages, path) => {
  const value = LANG_FILES(path)
  const lang = path.replace(/^\.\/(.*)\.\w+$/, '$1')
  if (LOADED_LANGUAGES.includes(lang)) {
    messages[lang] = value.default
  }
  return messages
}, {})

export const getLanguage = () => {
  const language = localStorage.getItem('chen_language')
  return language
}

const i18n = new VueI18n({
  locale: getLanguage(),
  messages
})

const importLanguage = lang => {
  return Promise.resolve(lang)
}

const setLang = lang => {
  localStorage.setItem('language', lang)
  i18n.locale = lang
}

export const setLanguage = lang => {
  if (i18n.locale !== lang) {
    importLanguage(lang).then(setLang)
  }
}

Vue.prototype.$tm = function(key, ...keys) {
  const values = []
  for (const k of keys) {
    values.push(i18n.t(k))
  }
  return i18n.t(key, values)
}

Vue.prototype.$tk = function(key) {
  const hasKey = i18n.te(key)
  if (hasKey) {
    return i18n.t(key)
  }
  return key
}

Vue.prototype.$setLang = setLanguage

export default i18n
