package org.jumpserver.chen.framework.utils;


public class CodeUtils {

    /**
     * 将导出文本作为 CSV 字段转义，并中和电子表格公式前缀。
     * @param value
     * @return
     */
    public static String escapeCsvValue(String value) {
        String trimmed = value.stripLeading();
        if ((!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0)
                || (!value.isEmpty() && "\t\r\n".indexOf(value.charAt(0)) >= 0)) {
            value = "'" + value;
        }
        if (value.contains("\"") || value.contains(",") || value.contains("\n") || value.contains("\r")) {
            // 对引号进行转义
            value = value.replace("\"", "\"\"");
            // 用引号包围值
            value = "\"" + value + "\"";
        }
        return value;
    }
}
