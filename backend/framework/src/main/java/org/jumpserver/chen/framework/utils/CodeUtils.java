package org.jumpserver.chen.framework.utils;


public class CodeUtils {

    /**
     * 对导出文件的换行符进行转义
     * @param value
     * @return
     */
    public static String escapeCsvValue(String value) {
        if (value.contains("\"") || value.contains(",") || value.contains("\n")) {
            // 对引号进行转义
            value = value.replace("\"", "\"\"");
            // 用引号包围值
            value = "\"" + value + "\"";
        }
        return value;
    }
}
