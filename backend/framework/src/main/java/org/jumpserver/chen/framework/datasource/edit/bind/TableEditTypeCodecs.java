package org.jumpserver.chen.framework.datasource.edit.bind;

import com.alibaba.druid.DbType;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class TableEditTypeCodecs {
    private static final Gson GSON = new Gson();
    private static final int SQLSERVER_GUID = -145;
    private static final int SQLSERVER_SMALLDATETIME = -150;
    private static final int SQLSERVER_DATETIME = -151;
    private static final int SQLSERVER_DATETIMEOFFSET = -155;
    private static final int ORACLE_TIMESTAMPNS = -100;
    private static final int ORACLE_TIMESTAMPTZ = -101;
    private static final int ORACLE_TIMESTAMPLTZ = -102;
    private static final int ORACLE_JSON = 2016;
    private static final BigInteger UNSIGNED_TINYINT_MAX = BigInteger.valueOf(255);
    private static final BigInteger UNSIGNED_SMALLINT_MAX = BigInteger.valueOf(65_535);
    private static final BigInteger UNSIGNED_MEDIUMINT_MAX = BigInteger.valueOf(16_777_215);
    private static final BigInteger UNSIGNED_INT_MAX = BigInteger.valueOf(4_294_967_295L);
    private static final BigInteger UNSIGNED_BIGINT_MAX = new BigInteger("18446744073709551615");

    private TableEditTypeCodecs() {
    }

    public static boolean supports(Field field) {
        return supports(field, null);
    }

    public static boolean supports(Field field, DbType dbType) {
        return kind(field, dbType) != Kind.UNSUPPORTED;
    }

    public static Object coerce(Object value, Field field) throws SQLException {
        return coerce(value, field, null);
    }

    public static Object coerce(Object value, Field field, DbType dbType) throws SQLException {
        Kind kind = kind(field, dbType);
        String type = normalizeType(field);
        if (kind == Kind.UNSUPPORTED) {
            throw new SQLException("unsupported table edit type, column=" +
                    (field != null ? field.getSourceColumn() : null) + ", type=" + type);
        }
        if (value == null) {
            return null;
        }

        try {
            return switch (kind) {
                case TINYINT -> toByte(value);
                case SMALLINT -> toShort(value);
                case INTEGER -> toInteger(value);
                case BIGINT -> toLong(value);
                case UNSIGNED_TINYINT -> exactInteger(value, BigInteger.ZERO, UNSIGNED_TINYINT_MAX).intValueExact();
                case UNSIGNED_SMALLINT -> exactInteger(value, BigInteger.ZERO, UNSIGNED_SMALLINT_MAX).intValueExact();
                case UNSIGNED_MEDIUMINT -> exactInteger(value, BigInteger.ZERO, UNSIGNED_MEDIUMINT_MAX).intValueExact();
                case UNSIGNED_INT -> exactInteger(value, BigInteger.ZERO, UNSIGNED_INT_MAX).longValueExact();
                case UNSIGNED_BIGINT -> exactInteger(value, BigInteger.ZERO, UNSIGNED_BIGINT_MAX);
                case DECIMAL -> toBigDecimal(value);
                case FLOATING -> toFiniteDouble(value);
                case BOOLEAN -> toBoolean(value);
                case STRING -> value.toString();
                case DATE -> toDate(value);
                case TIME -> toTime(value);
                case TIMESTAMP -> toTimestamp(value);
                case OFFSET_TIMESTAMP -> toOffsetDateTime(value);
                case UUID -> toUuid(value);
                case JSON -> toJsonString(value);
                case UNSUPPORTED -> throw new IllegalArgumentException("unsupported type");
            };
        } catch (RuntimeException e) {
            String column = field != null ? field.getSourceColumn() : null;
            throw new SQLException("convert value failed, column=" + column + ", type=" + type, e);
        }
    }

    public static String renderLiteral(Object value, boolean isNull, Field field) throws SQLException {
        return renderLiteral(value, isNull, field, null);
    }

    public static String renderLiteral(Object value, boolean isNull, Field field, DbType dbType) throws SQLException {
        if (isNull || value == null) {
            return "NULL";
        }
        Object coercedValue = coerce(value, field, dbType);
        if (coercedValue == null) {
            return "NULL";
        }
        if (coercedValue instanceof Number) {
            return coercedValue.toString();
        }
        if (coercedValue instanceof Boolean bool) {
            return bool ? "TRUE" : "FALSE";
        }
        return "'" + coercedValue.toString().replace("'", "''") + "'";
    }

    public static String normalizeType(Field field) {
        if (field == null || StringUtils.isBlank(field.getType())) {
            return null;
        }
        String type = unwrapKnownTypeWrappers(field.getType().trim().toLowerCase());
        return type.replaceAll("\\([^)]*\\)", "").trim();
    }

    private static Kind kind(Field field, DbType dbType) {
        if (field == null) {
            return Kind.UNSUPPORTED;
        }
        String type = normalizeType(field);
        Integer jdbcType = field.getJdbcType();
        if (isUnsignedType(type)) {
            return mysqlMariaUnsignedIntegerKind(type, dbType);
        }
        if (jdbcType != null) {
            if (jdbcType == SQLSERVER_GUID) {
                return dbType == DbType.sqlserver ? Kind.UUID : Kind.UNSUPPORTED;
            }
            if (jdbcType == SQLSERVER_DATETIMEOFFSET) {
                return dbType == DbType.sqlserver ? Kind.OFFSET_TIMESTAMP : Kind.UNSUPPORTED;
            }
            if (jdbcType == ORACLE_TIMESTAMPTZ) {
                return dbType == DbType.oracle ? Kind.OFFSET_TIMESTAMP : Kind.UNSUPPORTED;
            }
            if (jdbcType == ORACLE_TIMESTAMPLTZ) {
                return Kind.UNSUPPORTED;
            }
            if (jdbcType == ORACLE_JSON) {
                return dbType == DbType.oracle ? Kind.JSON : Kind.UNSUPPORTED;
            }
            if (jdbcType == Types.TIMESTAMP_WITH_TIMEZONE) {
                return timestampWithTimezoneKind(type, dbType);
            }
            if (jdbcType == Types.BOOLEAN) {
                return booleanKind(type, dbType);
            }
            if (jdbcType == Types.BIT) {
                return bitKind(type, dbType);
            }
            if (jdbcType == Types.OTHER && StringUtils.isNotBlank(type)) {
                if (isTimestampWithTimezone(type)) {
                    return timestampWithTimezoneKind(type, dbType);
                }
                if (isUuid(type)) {
                    return uuidKind(type, dbType);
                }
                if (isJson(type)) {
                    return jsonKind(type, dbType);
                }
                if (isBoolean(type)) {
                    return booleanKind(type, dbType);
                }
            }
            if (jdbcType == SQLSERVER_DATETIME) {
                return dbType == DbType.sqlserver ? Kind.TIMESTAMP : Kind.UNSUPPORTED;
            }
            if (jdbcType == SQLSERVER_SMALLDATETIME) {
                return dbType == DbType.sqlserver ? Kind.TIMESTAMP : Kind.UNSUPPORTED;
            }
            if (jdbcType == ORACLE_TIMESTAMPNS) {
                return dbType == DbType.oracle ? Kind.TIMESTAMP : Kind.UNSUPPORTED;
            }
        }
        if (jdbcType != null && jdbcType != Types.OTHER) {
            return switch (jdbcType) {
                case Types.TINYINT -> Kind.TINYINT;
                case Types.SMALLINT -> Kind.SMALLINT;
                case Types.INTEGER -> Kind.INTEGER;
                case Types.BIGINT -> Kind.BIGINT;
                case Types.NUMERIC, Types.DECIMAL -> Kind.DECIMAL;
                case Types.REAL, Types.FLOAT, Types.DOUBLE -> Kind.FLOATING;
                case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
                        Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR -> Kind.STRING;
                case Types.DATE -> Kind.DATE;
                case Types.TIME -> Kind.TIME;
                case Types.TIMESTAMP -> Kind.TIMESTAMP;
                default -> Kind.UNSUPPORTED;
            };
        }
        if (StringUtils.isBlank(type)) {
            return Kind.UNSUPPORTED;
        }
        if (isBitString(type)) {
            return Kind.UNSUPPORTED;
        }
        if (isBigInt(type)) {
            return Kind.BIGINT;
        }
        if (isInteger(type)) {
            return Kind.INTEGER;
        }
        if (isTinyInt(type)) {
            return Kind.TINYINT;
        }
        if (isSmallInt(type)) {
            return Kind.SMALLINT;
        }
        if (isDecimal(type)) {
            return Kind.DECIMAL;
        }
        if (isFloating(type)) {
            return Kind.FLOATING;
        }
        if (isBoolean(type)) {
            return booleanKind(type, dbType);
        }
        if (isString(type)) {
            return Kind.STRING;
        }
        if (isDate(type)) {
            return Kind.DATE;
        }
        if (isTime(type)) {
            return Kind.TIME;
        }
        if (isTimestamp(type)) {
            return Kind.TIMESTAMP;
        }
        if (isTimestampWithTimezone(type)) {
            return timestampWithTimezoneKind(type, dbType);
        }
        if (isUuid(type)) {
            return uuidKind(type, dbType);
        }
        if (isJson(type)) {
            return jsonKind(type, dbType);
        }
        return Kind.UNSUPPORTED;
    }

    private static Kind booleanKind(String type, DbType dbType) {
        if (dbType == DbType.postgresql || dbType == DbType.mysql || dbType == DbType.mariadb) {
            return StringUtils.isBlank(type) || isBoolean(type) ? Kind.BOOLEAN : Kind.UNSUPPORTED;
        }
        return Kind.UNSUPPORTED;
    }

    private static Kind bitKind(String type, DbType dbType) {
        if (dbType == DbType.postgresql && isBoolean(type)) {
            return Kind.BOOLEAN;
        }
        if (dbType == DbType.sqlserver && (StringUtils.isBlank(type) || StringUtils.equals(type, "bit"))) {
            return Kind.BOOLEAN;
        }
        return Kind.UNSUPPORTED;
    }

    private static Kind uuidKind(String type, DbType dbType) {
        if (dbType == DbType.postgresql && StringUtils.equals(type, "uuid")) {
            return Kind.UUID;
        }
        if (dbType == DbType.sqlserver && StringUtils.equals(type, "uniqueidentifier")) {
            return Kind.UUID;
        }
        return Kind.UNSUPPORTED;
    }

    private static Kind jsonKind(String type, DbType dbType) {
        if (dbType == DbType.postgresql && StringUtils.equalsAny(type, "json", "jsonb")) {
            return Kind.JSON;
        }
        if ((dbType == DbType.mysql || dbType == DbType.mariadb || dbType == DbType.oracle) &&
                StringUtils.equals(type, "json")) {
            return Kind.JSON;
        }
        return Kind.UNSUPPORTED;
    }

    private static Kind timestampWithTimezoneKind(String type, DbType dbType) {
        if (dbType == DbType.postgresql && (StringUtils.isBlank(type) || StringUtils.equals(type, "timestamptz") ||
                StringUtils.equals(type, "timestamp with time zone"))) {
            return Kind.OFFSET_TIMESTAMP;
        }
        if (dbType == DbType.sqlserver && (StringUtils.isBlank(type) || StringUtils.equals(type, "datetimeoffset"))) {
            return Kind.OFFSET_TIMESTAMP;
        }
        if (dbType == DbType.oracle && (StringUtils.isBlank(type) ||
                StringUtils.equals(type, "timestamp with time zone"))) {
            return Kind.OFFSET_TIMESTAMP;
        }
        return Kind.UNSUPPORTED;
    }

    private static String unwrapKnownTypeWrappers(String type) {
        String unwrapped = type;
        boolean changed;
        do {
            changed = false;
            if (unwrapped.startsWith("nullable(") && unwrapped.endsWith(")")) {
                unwrapped = unwrapped.substring("nullable(".length(), unwrapped.length() - 1).trim();
                changed = true;
            }
            if (unwrapped.startsWith("lowcardinality(") && unwrapped.endsWith(")")) {
                unwrapped = unwrapped.substring("lowcardinality(".length(), unwrapped.length() - 1).trim();
                changed = true;
            }
        } while (changed);
        return unwrapped;
    }

    private static boolean isBigInt(String type) {
        return StringUtils.equalsAny(type, "bigserial", "bigint", "int8");
    }

    private static boolean isInteger(String type) {
        return StringUtils.equalsAny(type, "serial", "integer", "int", "int4", "mediumint");
    }

    private static boolean isTinyInt(String type) {
        return StringUtils.equalsAny(type, "tinyint", "int1");
    }

    private static boolean isSmallInt(String type) {
        return StringUtils.equalsAny(type, "smallint", "int2");
    }

    private static boolean isDecimal(String type) {
        return StringUtils.equalsAny(type, "numeric", "decimal", "number");
    }

    private static boolean isFloating(String type) {
        return StringUtils.equalsAny(type, "real", "float", "float4", "float8", "double", "double precision");
    }

    private static Kind mysqlMariaUnsignedIntegerKind(String type, DbType dbType) {
        if (dbType != DbType.mysql && dbType != DbType.mariadb) {
            return Kind.UNSUPPORTED;
        }
        if (StringUtils.equals(type, "tinyint unsigned")) {
            return Kind.UNSIGNED_TINYINT;
        }
        if (StringUtils.equals(type, "smallint unsigned")) {
            return Kind.UNSIGNED_SMALLINT;
        }
        if (StringUtils.equals(type, "mediumint unsigned")) {
            return Kind.UNSIGNED_MEDIUMINT;
        }
        if (StringUtils.equalsAny(type, "int unsigned", "integer unsigned")) {
            return Kind.UNSIGNED_INT;
        }
        if (StringUtils.equals(type, "bigint unsigned")) {
            return Kind.UNSIGNED_BIGINT;
        }
        return Kind.UNSUPPORTED;
    }

    private static boolean isUnsignedType(String type) {
        return StringUtils.endsWith(type, " unsigned") || StringUtils.startsWith(type, "uint");
    }

    private static boolean isBoolean(String type) {
        return StringUtils.equalsAny(type, "bool", "boolean");
    }

    private static boolean isBitString(String type) {
        return StringUtils.equalsAny(type, "bit", "bit varying", "varbit");
    }

    private static boolean isString(String type) {
        return StringUtils.equalsAny(type, "varchar", "varchar2", "character varying", "text", "tinytext",
                "mediumtext", "longtext", "char", "character", "bpchar", "nvarchar", "nvarchar2", "nchar",
                "longnvarchar");
    }

    private static boolean isDate(String type) {
        return StringUtils.equals(type, "date");
    }

    private static boolean isTime(String type) {
        return StringUtils.equalsAny(type, "time", "time without time zone");
    }

    private static boolean isTimestamp(String type) {
        return StringUtils.equalsAny(type, "timestamp", "timestamp without time zone", "datetime", "datetime2",
                "smalldatetime");
    }

    private static boolean isTimestampWithTimezone(String type) {
        return StringUtils.equalsAny(type, "timestamptz", "timestamp with time zone", "datetimeoffset");
    }

    private static boolean isUuid(String type) {
        return StringUtils.equalsAny(type, "uuid", "uniqueidentifier");
    }

    private static boolean isJson(String type) {
        return StringUtils.equalsAny(type, "json", "jsonb");
    }

    private static Byte toByte(Object value) {
        return exactInteger(value, BigInteger.valueOf(Byte.MIN_VALUE), BigInteger.valueOf(Byte.MAX_VALUE)).byteValueExact();
    }

    private static Short toShort(Object value) {
        return exactInteger(value, BigInteger.valueOf(Short.MIN_VALUE), BigInteger.valueOf(Short.MAX_VALUE)).shortValueExact();
    }

    private static Integer toInteger(Object value) {
        return exactInteger(value, BigInteger.valueOf(Integer.MIN_VALUE), BigInteger.valueOf(Integer.MAX_VALUE)).intValueExact();
    }

    private static Long toLong(Object value) {
        return exactInteger(value, BigInteger.valueOf(Long.MIN_VALUE), BigInteger.valueOf(Long.MAX_VALUE)).longValueExact();
    }

    private static BigInteger exactInteger(Object value, BigInteger minimum, BigInteger maximum) {
        BigInteger integer = toBigDecimal(value).toBigIntegerExact();
        if (integer.compareTo(minimum) < 0 || integer.compareTo(maximum) > 0) {
            throw new ArithmeticException("integer out of range");
        }
        return integer;
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal;
        }
        if (value instanceof Number || value instanceof CharSequence) {
            return new BigDecimal(value.toString());
        }
        throw new IllegalArgumentException("value is not numeric");
    }

    private static Double toFiniteDouble(Object value) {
        double converted;
        if (value instanceof Number number) {
            converted = number.doubleValue();
        } else {
            converted = Double.parseDouble(value.toString().trim());
        }
        if (!Double.isFinite(converted)) {
            throw new IllegalArgumentException("value is not finite");
        }
        return converted;
    }

    private static Boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = value.toString().trim();
        if (StringUtils.equalsAnyIgnoreCase(text, "true", "t", "1", "yes", "y")) {
            return true;
        }
        if (StringUtils.equalsAnyIgnoreCase(text, "false", "f", "0", "no", "n")) {
            return false;
        }
        throw new IllegalArgumentException("value is not boolean");
    }

    private static Date toDate(Object value) {
        if (value instanceof Date date) {
            return date;
        }
        if (value instanceof java.util.Date date) {
            return new Date(date.getTime());
        }
        String text = value.toString().trim();
        int timeSeparator = Math.max(text.indexOf('T'), text.indexOf(' '));
        if (timeSeparator > -1) {
            text = text.substring(0, timeSeparator);
        }
        return Date.valueOf(LocalDate.parse(text));
    }

    private static Time toTime(Object value) {
        if (value instanceof Time time) {
            return time;
        }
        if (value instanceof java.util.Date date) {
            return new Time(date.getTime());
        }
        String text = value.toString().trim();
        if (text.endsWith("Z") || text.matches(".*[+-]\\d{2}:\\d{2}$")) {
            return Time.valueOf(OffsetDateTime.parse(text).toLocalTime());
        }
        if (text.contains("T") || text.contains(" ")) {
            return Time.valueOf(LocalDateTime.parse(text.replace(' ', 'T')).toLocalTime());
        }
        return Time.valueOf(LocalTime.parse(text));
    }

    private static Timestamp toTimestamp(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp;
        }
        if (value instanceof java.util.Date date) {
            return new Timestamp(date.getTime());
        }
        String text = value.toString().trim();
        if (text.endsWith("Z") || text.matches(".*[+-]\\d{2}:\\d{2}$")) {
            return Timestamp.from(OffsetDateTime.parse(text).toInstant());
        }
        return Timestamp.valueOf(LocalDateTime.parse(text.replace(' ', 'T')));
    }

    private static OffsetDateTime toOffsetDateTime(Object value) {
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime;
        }
        return OffsetDateTime.parse(value.toString().trim());
    }

    private static UUID toUuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(value.toString().trim());
    }

    private static String toJsonString(Object value) {
        String text = value instanceof CharSequence ? value.toString() : GSON.toJson(value);
        try {
            JsonParser.parseString(text);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("value is not valid json", e);
        }
        return text;
    }

    private enum Kind {
        TINYINT,
        SMALLINT,
        INTEGER,
        BIGINT,
        UNSIGNED_TINYINT,
        UNSIGNED_SMALLINT,
        UNSIGNED_MEDIUMINT,
        UNSIGNED_INT,
        UNSIGNED_BIGINT,
        DECIMAL,
        FLOATING,
        BOOLEAN,
        STRING,
        DATE,
        TIME,
        TIMESTAMP,
        OFFSET_TIMESTAMP,
        UUID,
        JSON,
        UNSUPPORTED
    }
}
