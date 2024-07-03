import el from 'element-ui/lib/locale/lang/zh-TW'

const message = {
  title: {
    database_explorer: '資料庫瀏覽器',
    save_sql: '保存SQL',
    select_sql: '選擇SQL',
    export_data: '導出數據'
  },
  button: {
    run: '運行',
    run_selected: '運行選中',
    refresh: '刷新',
    total: '共',
    insert: '插入'
  },
  common: {
    num_row: '{num}行',
    log: '日誌輸出',
    current: '當前',
    name: '名稱',
    content: '內容'
  },
  action: {
    confirm: '確認',
    cancel: '取消'
  },
  tip: {
    upload: '運行SQL文件',
    run: '運行 (Ctrl + Enter)',
    stop: '停止 (Ctrl + C)',
    format: '格式化 (Ctrl + L)',
    open: '打開 (Ctrl + R)',
    save: '保存 (Ctrl + S)'
  },
  option: {
    export_all: '導出全部',
    export_current: '導出當前'
  },
  message: {
    save_success: '保存成功'
  },
  msg: {
    copy_not_allowed: '不允許複製，請聯絡管理員開啟！',
    paste_not_allowed: '不允許黏貼，請聯絡管理員開啟！'
  }

}

export default {
  ...el,
  ...message
}
