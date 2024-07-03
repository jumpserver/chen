package org.jumpserver.chen.framework.utils;

public class TimeUtils {

    public static long getNowUnixNanoTIme(){
        long nanoTime = System.nanoTime();
        long currentTimeMillis = System.currentTimeMillis();
        return currentTimeMillis * 1_000_000 + (nanoTime % 1_000_000);
    }
}
