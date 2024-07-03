import { auth, getProfile } from '@/api/app'
import { Message } from 'element-ui'
import i18n from '@/i18n'

const state = {
  authenticated: false,
  token: '',
  lang: 'zh-CN',
  disableautohash: false,
  profile: {
    dbType: '',
    username: '',
    assetName: '',
    canCopy: false,
    canPaste: false
  }
}

const mutations = {
  DISABLE_AUTO_HASH: (state, disableautohash) => {
    state.disableautohash = disableautohash
  },
  AUTH: (state, authResponse) => {
    state.authenticated = true
    state.token = authResponse.token
    state.lang = authResponse.lang
    localStorage.setItem('chen_language', authResponse.lang)
  },
  PROFILE: (state, profile) => {
    state.profile = profile
    if (!profile.canCopy) {
      document.addEventListener('copy', (e) => {
        Message.error(i18n.t('msg.copy_not_allowed'))
        e.preventDefault()
        navigator.clipboard.writeText('').then(r => {})
      })
    }
    if (!profile.canPaste) {
      document.addEventListener('paste', (e) => {
        Message.error(i18n.t('msg.copy_not_allowed'))
        e.preventDefault()
        navigator.clipboard.writeText('').then(r => {})
      })
    }
  }
}

const actions = {
  loadProfile({ commit }) {
    getProfile().then(data => {
      commit('PROFILE', data)
    })
  },

  auth({ commit }, params) {
    return new Promise((resolve, reject) => {
      auth(params.token, params.disableautohash).then(response => {
        commit('AUTH', response)
        commit('DISABLE_AUTO_HASH', params.disableautohash)
        resolve(response.token)
      }).catch(error => {
        reject(error)
      })
    })
  }
}

export default {
  namespaced: true,
  state,
  mutations,
  actions
}
