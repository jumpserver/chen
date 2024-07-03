const getters = {
  token: state => state.app.token,
  authenticated: state => state.app.authenticated,
  profile: state => state.app.profile,
  disableautohash: state => state.app.disableautohash,
  i18nLoaded: state => state.app.i18nLoaded
}
export default getters
