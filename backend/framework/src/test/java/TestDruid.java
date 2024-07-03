import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TestDruid {

    private static String[] keywords = new String[]{"select", "from", "where"};
    private static Map<String, List<String>> tables = new HashMap<>();

    static {
        tables.put("user", List.of("id", "name", "age"));
        tables.put("student", List.of("id", "name", "age"));
        tables.put("lll", List.of("id", "name", "age"));
    }

    public static void main(String[] args) {
        var sql = "select * from user.";

        var suggestions = new ArrayList<String>();
        var tokens = sql.split("\\s+");

        if (tokens.length == 0) {
            return;
        }
        // 获取最后一个 token
        var lastToken = tokens[tokens.length - 1];

        if (lastToken.endsWith(".")) {
            var tableName = lastToken.substring(0, lastToken.length() - 1);
            // 获取表中的字段
            var fields = tables.get(tableName);
            if (fields != null) {
                suggestions.addAll(fields);
            }
        } else {
            // 从 keywords 中获取匹配的关键字
            for (var keyword : keywords) {
                if (keyword.startsWith(lastToken)) {
                    suggestions.add(keyword);
                }
            }
        }


        System.out.println(suggestions);
    }
}
