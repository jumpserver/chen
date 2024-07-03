const path = require('path')

function resolve(dir) {
  return path.join(__dirname, dir)
}

module.exports = {
  productionSourceMap: true,
  publicPath: '/chen',
  devServer: {
    port: 9523,
    open: true,
    overlay: {
      warnings: false,
      errors: true
    },
    proxy: {
      '/chen/api': {
        target: 'http://0.0.0.0:8082',
        ws: true,
        secure: false
      },
      '/chen/ws': {
        target: 'ws://0.0.0.0:8082',
        ws: true,
        secure: false
      }
    }
  },
  configureWebpack: {
    resolve: {
      alias: {
        '@': resolve('src')
      },
      extensions: ['.vue', '.js', '.json']
    }
  },
  chainWebpack(config) {
    config.plugins.delete('preload')
    config.plugins.delete('prefetch')
    config
      .when(process.env.NODE_ENV === 'development',
        config => config.devtool('cheap-source-map')
      )
    config
      .when(process.env.NODE_ENV !== 'development',
        config => {
          config.optimization.runtimeChunk('single')
        }
      )
  }
}
