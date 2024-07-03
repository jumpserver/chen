package org.jumpserver.chen.framework.utils;

public class LangUtils {
    private static final String S_ = "_";

    public static String toHumpStr(String snakeStr) {
        if (null == snakeStr || !snakeStr.contains(S_)) {
            return snakeStr;
        }
        StringBuilder sb = new StringBuilder();
        char[] chars = snakeStr.toCharArray();
        boolean is = false;
        for (char c : chars) {
            if ('_' == c) {
                is = true;
            } else if (is) {
                sb.append(Character.toUpperCase(c));
                is = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
