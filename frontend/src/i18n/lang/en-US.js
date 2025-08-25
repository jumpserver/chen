import el from 'element-ui/lib/locale/lang/en'

const message = {
  title: {
    database_explorer: 'Database Explorer',
    save_sql: 'Save SQL',
    select_sql: 'Select SQL',
    export_data: 'Export Data'
  },
  button: {
    run: 'Run',
    run_selected: 'Run selected',
    refresh: 'Refresh',
    total: 'Total'
  },
  common: {
    num_row: '{num} rows',
    log: 'Log Output',
    current: 'Current'
  },
  action: {
    confirm: 'Confirm',
    cancel: 'Cancel'
  },
  tip: {
    run: 'Run (Ctrl + Enter)',
    stop: 'Stop (Ctrl + D)',
    format: 'Format (Ctrl + L)'
  },
  option: {
    export_all: 'Export all data',
    export_current: 'Export current page'
  },
  msg: {
    copy_not_allowed: 'You are not allowed to copy, please contact the administrator to open it!',
    paste_not_allowed: 'You are not allowed to paste, please contact the administrator to open it!'
  }
}

export default {
  ...el,
  ...message
}
