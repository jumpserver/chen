const getters = {
  token: state => state.app.token,
  authenticated: state => state.app.authenticated,
  profile: state => state.app.profile,
  disableautohash: state => state.app.disableautohash
}
export default getters
