package oracle;

import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Properties;

public class TestOracleDriver {

    public static void main(String[] args) throws MalformedURLException {

        try {
            ClassLoader classLoader = new URLClassLoader(new URL[]{new URL("file:/Users/shenchenyang/IdeaProjects/chen/drivers/oracle/ojdbc8-21.9.0.0.jar")});
            Driver driver = (Driver) classLoader.loadClass("oracle.jdbc.driver.OracleDriver").getDeclaredConstructor().newInstance();

            Properties properties = new Properties();

            properties.setProperty("user","C##user1");
            properties.setProperty("password","woCalong@2015");

            var conn = driver.connect("jdbc:oracle:thin:@172.16.200.52:11521:xe", properties);
            conn.close();


        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
