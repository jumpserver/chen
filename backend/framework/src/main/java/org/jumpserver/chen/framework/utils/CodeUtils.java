package org.jumpserver.chen.framework.utils;


public class CodeUtils {

    /**
     * 对导出文件的换行符进行转义
     * @param value
     * @return
     */
    public static String escapeCsvValue(String value) {
        if (value.contains("\"") || value.contains(",") || value.contains("\n") || value.contains("\r")) {
            value = value.replace("\"", "\"\"");
            value = "\"" + value + "\"";
        }
        return value;
    }
}
