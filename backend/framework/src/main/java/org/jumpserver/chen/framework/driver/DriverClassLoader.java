package org.jumpserver.chen.framework.driver;

import lombok.Getter;

import java.net.URL;
import java.net.URLClassLoader;

public class DriverClassLoader extends URLClassLoader {

    @Getter
    private final String jarName;

    public DriverClassLoader(String jarName, URL url) {
        super(new URL[]{url});
        this.jarName = jarName;
    }


}
