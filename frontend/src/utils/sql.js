export function compileSQL(template, params) {
  const paramNames = []
  const matcher = /:(\w+)/g

  let match = matcher.exec(template)
  while (match) {
    paramNames.push(match[1])
    match = matcher.exec(template)
  }

  for (const paramName of paramNames) {
    let paramValue = params[paramName]
    if (paramValue === undefined) {
      paramValue = ''
    }
    template = template.replace(new RegExp(`:${paramName}`, 'g'), paramValue)
  }
  return template
}
