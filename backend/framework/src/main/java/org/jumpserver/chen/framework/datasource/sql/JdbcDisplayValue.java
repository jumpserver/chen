package org.jumpserver.chen.framework.datasource.sql;

import com.alibaba.druid.DbType;
import org.apache.commons.lang3.StringUtils;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Types;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Type-aware display conversion for JDBC result cells.
 * VARBINARY/BINARY/BYTEA stay on the existing 0x hex path; BLOB and spatial
 * types are classified from JDBC type, column type name, and dialect.
 */
public final class JdbcDisplayValue {
    private static final int SQLSERVER_GEOMETRY = -157;
    private static final int SQLSERVER_GEOGRAPHY = -158;
    private static final Pattern WKT_PATTERN = Pattern.compile(
            "^\\s*(SRID\\s*=\\s*\\d+\\s*;\\s*)?(POINT|LINESTRING|POLYGON|MULTIPOINT|"
                    + "MULTILINESTRING|MULTIPOLYGON|GEOMETRYCOLLECTION)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private JdbcDisplayValue() {
    }

    public static boolean isDecimalType(int jdbcType, String typeName) {
        if (jdbcType == Types.DECIMAL || jdbcType == Types.NUMERIC) {
            return true;
        }
        return switch (normalizeTypeName(typeName)) {
            case "DECIMAL", "NUMERIC", "NUMBER", "DEC", "DECFLOAT" -> true;
            default -> false;
        };
    }

    public static boolean isBlobType(int jdbcType, String typeName) {
        String type = normalizeTypeName(typeName);
        if (type.equals("VARBINARY") || type.equals("BINARY") || type.equals("BYTEA") || type.equals("RAW")) {
            return false;
        }
        if (jdbcType == Types.BLOB) {
            return true;
        }
        return switch (type) {
            case "BLOB", "TINYBLOB", "MEDIUMBLOB", "LONGBLOB", "IMAGE", "BFILE", "LONG RAW" -> true;
            default -> jdbcType == Types.LONGVARBINARY;
        };
    }

    public static boolean isGeometryType(int jdbcType, String typeName) {
        if (jdbcType == SQLSERVER_GEOMETRY || jdbcType == SQLSERVER_GEOGRAPHY) {
            return true;
        }
        String type = normalizeTypeName(typeName);
        if (type.isEmpty()) {
            return false;
        }
        return switch (type) {
            case "GEOMETRY", "GEOGRAPHY", "SDO_GEOMETRY", "ST_GEOMETRY", "ST_GEOGRAPHY",
                    "POINT", "LINESTRING", "POLYGON", "MULTIPOINT", "MULTILINESTRING",
                    "MULTIPOLYGON", "GEOMETRYCOLLECTION", "GEOMCOLLECTION",
                    "ST_POINT", "ST_LINESTRING", "ST_POLYGON", "ST_MULTIPOINT",
                    "ST_MULTILINESTRING", "ST_MULTIPOLYGON", "ST_GEOMCOLLECTION",
                    "ST_GEOMETRYCOLLECTION", "GEO_POINT", "GEO_LINESTRING", "GEO_POLYGON" -> true;
            default -> type.endsWith("GEOMETRY") || type.endsWith("GEOGRAPHY");
        };
    }

    public static String blobSummary() {
        return "<BLOB>";
    }

    public static String blobSummary(long byteLength) {
        return "<BLOB " + byteLength + " bytes>";
    }

    public static String geometrySummary(Integer byteLength) {
        if (byteLength == null) {
            return "[Geometry]";
        }
        return "[Geometry, " + byteLength + " B]";
    }

    public static String toGeometryDisplay(Object value, DbType dbType) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            String wkt = wkbToWktForDialect(bytes, dbType);
            if (wkt != null) {
                return wkt;
            }
            return geometrySummary(bytes.length);
        }

        String fromMethod = firstWktMethod(value);
        if (fromMethod != null) {
            return fromMethod;
        }

        String text = extractText(value);
        if (looksLikeWkt(text)) {
            return text.trim();
        }
        if (looksLikeHexEwkb(text)) {
            return geometrySummary(hexByteLength(text));
        }
        if (StringUtils.isNotBlank(text) && !isDefaultObjectToString(value, text) && !looksLikeHexEwkb(text)) {
            return text;
        }
        return geometrySummary(null);
    }

    public static String mysqlWkbToWkt(byte[] data) {
        if (data == null || data.length < 5) {
            return null;
        }
        // MySQL/MariaDB store a 4-byte SRID prefix before OGC WKB.
        String withSrid = readWkb(data, 4);
        if (withSrid != null) {
            return withSrid;
        }
        return readWkb(data, 0);
    }

    public static boolean looksLikeWkt(String text) {
        return text != null && WKT_PATTERN.matcher(text).find();
    }

    static String normalizeTypeName(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return "";
        }
        String type = typeName.trim();
        int dot = type.lastIndexOf('.');
        if (dot >= 0 && dot < type.length() - 1) {
            type = type.substring(dot + 1);
        }
        int paren = type.indexOf('(');
        if (paren >= 0) {
            type = type.substring(0, paren);
        }
        return type.trim().toUpperCase(Locale.ROOT);
    }

    private static String wkbToWktForDialect(byte[] bytes, DbType dbType) {
        if (dbType == DbType.mysql || dbType == DbType.mariadb) {
            return mysqlWkbToWkt(bytes);
        }
        return null;
    }

    private static String firstWktMethod(Object value) {
        for (String name : new String[]{"STAsText", "asText", "toText", "getWKT", "toWKT", "asWKT"}) {
            String text = invokeStringMethod(value, name);
            if (looksLikeWkt(text)) {
                return text.trim();
            }
        }
        return null;
    }

    private static String extractText(Object value) {
        String fromValue = invokeStringMethod(value, "getValue");
        if (StringUtils.isNotBlank(fromValue)) {
            return fromValue;
        }
        return value.toString();
    }

    private static String invokeStringMethod(Object value, String name) {
        try {
            Method method = value.getClass().getMethod(name);
            Object result = method.invoke(value);
            return result == null ? null : result.toString();
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static boolean looksLikeHexEwkb(String text) {
        if (text == null) {
            return false;
        }
        String hex = stripHexPrefix(text.trim());
        if (hex.length() < 16 || (hex.length() & 1) != 0) {
            return false;
        }
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            boolean hexChar = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hexChar) {
                return false;
            }
        }
        return true;
    }

    private static int hexByteLength(String text) {
        return stripHexPrefix(text.trim()).length() / 2;
    }

    private static String stripHexPrefix(String text) {
        if (text.startsWith("\\x") || text.startsWith("\\X")) {
            return text.substring(2);
        }
        if (text.startsWith("0x") || text.startsWith("0X")) {
            return text.substring(2);
        }
        return text;
    }

    private static boolean isDefaultObjectToString(Object value, String text) {
        return text.equals(value.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(value)));
    }

    private static String readWkb(byte[] data, int offset) {
        try {
            WkbReader reader = new WkbReader(data, offset);
            String wkt = reader.readGeometry();
            return StringUtils.isBlank(wkt) ? null : wkt;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static final class WkbReader {
        private static final int POINT = 1;
        private static final int LINESTRING = 2;
        private static final int POLYGON = 3;
        private static final int MULTIPOINT = 4;
        private static final int MULTILINESTRING = 5;
        private static final int MULTIPOLYGON = 6;
        private static final int GEOMETRYCOLLECTION = 7;

        private final byte[] data;
        private int pos;
        private ByteOrder order = ByteOrder.LITTLE_ENDIAN;

        private WkbReader(byte[] data, int offset) {
            this.data = data;
            this.pos = offset;
        }

        private String readGeometry() {
            if (pos >= data.length) {
                throw new IllegalArgumentException("truncated wkb");
            }
            int endian = data[pos++] & 0xff;
            if (endian == 1) {
                order = ByteOrder.LITTLE_ENDIAN;
            } else if (endian == 0) {
                order = ByteOrder.BIG_ENDIAN;
            } else {
                throw new IllegalArgumentException("invalid byte order");
            }
            int type = readInt();
            if ((type & 0xE0000000) != 0) {
                // PostGIS EWKB flags — not MySQL WKB. Do not guess the layout.
                throw new IllegalArgumentException("ewkb");
            }
            return switch (type) {
                case POINT -> "POINT" + readPointBody();
                case LINESTRING -> "LINESTRING" + readLineStringBody();
                case POLYGON -> "POLYGON" + readPolygonBody();
                case MULTIPOINT -> "MULTIPOINT" + readMulti(POINT);
                case MULTILINESTRING -> "MULTILINESTRING" + readMulti(LINESTRING);
                case MULTIPOLYGON -> "MULTIPOLYGON" + readMulti(POLYGON);
                case GEOMETRYCOLLECTION -> "GEOMETRYCOLLECTION" + readCollection();
                default -> throw new IllegalArgumentException("unsupported wkb type " + type);
            };
        }

        private String readPointBody() {
            return "(" + formatCoord(readDouble()) + " " + formatCoord(readDouble()) + ")";
        }

        private String readLineStringBody() {
            int count = readInt();
            if (count < 0) {
                throw new IllegalArgumentException("negative point count");
            }
            if (count == 0) {
                return " EMPTY";
            }
            StringBuilder builder = new StringBuilder("(");
            for (int i = 0; i < count; i++) {
                if (i > 0) {
                    builder.append(", ");
                }
                builder.append(formatCoord(readDouble())).append(' ').append(formatCoord(readDouble()));
            }
            return builder.append(')').toString();
        }

        private String readPolygonBody() {
            int rings = readInt();
            if (rings < 0) {
                throw new IllegalArgumentException("negative ring count");
            }
            if (rings == 0) {
                return " EMPTY";
            }
            StringBuilder builder = new StringBuilder("(");
            for (int i = 0; i < rings; i++) {
                if (i > 0) {
                    builder.append(", ");
                }
                String ring = readLineStringBody();
                if (" EMPTY".equals(ring)) {
                    throw new IllegalArgumentException("empty ring");
                }
                builder.append(ring);
            }
            return builder.append(')').toString();
        }

        private String readMulti(int expectedType) {
            int count = readInt();
            if (count < 0) {
                throw new IllegalArgumentException("negative collection count");
            }
            if (count == 0) {
                return " EMPTY";
            }
            StringBuilder builder = new StringBuilder("(");
            for (int i = 0; i < count; i++) {
                if (i > 0) {
                    builder.append(", ");
                }
                String member = readGeometry();
                builder.append(memberBody(member, expectedType));
            }
            return builder.append(')').toString();
        }

        private String readCollection() {
            int count = readInt();
            if (count < 0) {
                throw new IllegalArgumentException("negative collection count");
            }
            if (count == 0) {
                return " EMPTY";
            }
            StringBuilder builder = new StringBuilder("(");
            for (int i = 0; i < count; i++) {
                if (i > 0) {
                    builder.append(", ");
                }
                builder.append(readGeometry());
            }
            return builder.append(')').toString();
        }

        private String memberBody(String wkt, int expectedType) {
            String prefix = switch (expectedType) {
                case POINT -> "POINT";
                case LINESTRING -> "LINESTRING";
                case POLYGON -> "POLYGON";
                default -> throw new IllegalArgumentException("unexpected member type");
            };
            if (!wkt.regionMatches(true, 0, prefix, 0, prefix.length())) {
                throw new IllegalArgumentException("mixed collection member");
            }
            return wkt.substring(prefix.length());
        }

        private int readInt() {
            if (pos + 4 > data.length) {
                throw new IllegalArgumentException("truncated int");
            }
            int value = ByteBuffer.wrap(data, pos, 4).order(order).getInt();
            pos += 4;
            return value;
        }

        private double readDouble() {
            if (pos + 8 > data.length) {
                throw new IllegalArgumentException("truncated double");
            }
            double value = ByteBuffer.wrap(data, pos, 8).order(order).getDouble();
            pos += 8;
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("non-finite coordinate");
            }
            return value;
        }

        private static String formatCoord(double value) {
            return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        }
    }
}
