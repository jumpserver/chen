import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import org.junit.Test;

public class TestFileCreate {

    @Test
    public void testFileCreate() {

        var stmts = SQLUtils.parseStatements("show tables", DbType.clickhouse);
        System.out.println(stmts);
    }
}
