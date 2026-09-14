package org.jumpserver.chen.framework.i18n;

import org.jumpserver.chen.framework.datasource.sql.SqlValidator;
import org.jumpserver.chen.framework.i18n.MessageUtils;
import org.jumpserver.chen.framework.session.Session;
import org.jumpserver.chen.framework.session.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserVisibleMessageI18nTest {
    private String sessionToken;
    private final AtomicReference<Locale> locale = new AtomicReference<>(Locale.US);

    @BeforeEach
    void setUp() {
        ReloadableResourceBundleMessageSource messageSource = new ReloadableResourceBundleMessageSource();
        Path bundle = propertiesDir().resolve("chen");
        messageSource.setBasename(bundle.toUri().toString());
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setFallbackToSystemLocale(false);
        new MessageUtils(messageSource);
        Session session = (Session) Proxy.newProxyInstance(
                Session.class.getClassLoader(),
                new Class[]{Session.class},
                (proxy, method, args) -> "getLocale".equals(method.getName()) ? this.locale.get() : null);
        this.sessionToken = SessionManager.registerSession(session);
        SessionManager.setContext(this.sessionToken);
    }

    @AfterEach
    void tearDown() {
        if (this.sessionToken != null) {
            SessionManager.unregisterSession(this.sessionToken);
        }
        SessionManager.setContext(null);
    }

    @Test
    void chineseAndEnglishUserVisibleMessagesDiffer() {
        this.locale.set(Locale.US);
        String enAffected = MessageUtils.get("AffectedRows");
        String enUnknown = MessageUtils.get("Unknown");
        String enUnsupported = SqlValidator.queryUnsupportedMessage();
        String enExported = MessageUtils.get("RowsExported", 3);
        String enError = MessageUtils.get("ErrorPrefix", "boom");

        this.locale.set(Locale.CHINA);
        String zhAffected = MessageUtils.get("AffectedRows");
        String zhUnknown = MessageUtils.get("Unknown");
        String zhUnsupported = SqlValidator.queryUnsupportedMessage();
        String zhExported = MessageUtils.get("RowsExported", 3);
        String zhError = MessageUtils.get("ErrorPrefix", "boom");

        assertEquals("Affected rows", enAffected);
        assertEquals("unknown", enUnknown);
        assertEquals("受影响行数", zhAffected);
        assertEquals("未知", zhUnknown);
        assertEquals("Error: boom", enError);
        assertEquals("错误: boom", zhError);
        assertNotEquals(enAffected, zhAffected);
        assertNotEquals(enUnknown, zhUnknown);
        assertNotEquals(enUnsupported, zhUnsupported);
        assertNotEquals(enExported, zhExported);
        assertTrue(enExported.contains("3"));
        assertTrue(zhExported.contains("3"));
    }

    @Test
    void affectedRowsTicketPrefixDoesNotRewriteSql() {
        String sql = "UPDATE accounts SET balance = balance - 10";
        this.locale.set(Locale.CHINA);
        String zh = formatTicket(sql, null);
        this.locale.set(Locale.US);
        String en = formatTicket(sql, 3);

        assertTrue(zh.startsWith("受影响行数: 未知\n"));
        assertTrue(zh.endsWith("\n" + sql));
        assertTrue(en.startsWith("Affected rows: 3\n"));
        assertTrue(en.endsWith("\n" + sql));
        assertEquals(sql, zh.substring(zh.indexOf('\n') + 1));
        assertEquals(sql, en.substring(en.indexOf('\n') + 1));
    }

    @Test
    void propertiesKeySetsStayAligned() throws Exception {
        Path dir = propertiesDir();
        List<Path> files = Files.list(dir)
                .filter(path -> path.getFileName().toString().startsWith("chen")
                        && path.getFileName().toString().endsWith(".properties"))
                .sorted()
                .toList();
        assertFalse(files.isEmpty());
        Set<String> expected = null;
        for (Path file : files) {
            Set<String> keys = Files.readAllLines(file).stream()
                    .filter(line -> !line.isBlank() && !line.startsWith("#") && line.contains("="))
                    .map(line -> line.substring(0, line.indexOf('=')))
                    .collect(Collectors.toCollection(HashSet::new));
            if (expected == null) {
                expected = keys;
            } else {
                assertEquals(expected, keys, file.getFileName().toString());
            }
        }
        assertTrue(expected.contains("AffectedRows"));
        assertTrue(expected.contains("Unknown"));
        assertTrue(expected.contains("QueryUnsupported"));
        assertTrue(expected.contains("RowsExported"));
        assertTrue(expected.contains("ErrorPrefix"));
    }

    private static Path propertiesDir() {
        Path dir = Path.of("../web/src/main/resources/i18n");
        if (!Files.isDirectory(dir)) {
            dir = Path.of("backend/web/src/main/resources/i18n");
        }
        if (!Files.isDirectory(dir)) {
            dir = Path.of("src/main/resources/i18n");
        }
        return dir.toAbsolutePath().normalize();
    }

    private static String formatTicket(String sql, Integer affectedRows) {
        String value = affectedRows == null
                ? MessageUtils.getOrDefault("Unknown", "unknown")
                : Integer.toString(affectedRows);
        return String.format("%s: %s\n%s",
                MessageUtils.getOrDefault("AffectedRows", "Affected rows"),
                value,
                sql);
    }
}
