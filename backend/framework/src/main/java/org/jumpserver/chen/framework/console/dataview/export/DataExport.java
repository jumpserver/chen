package org.jumpserver.chen.framework.console.dataview.export;

import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.jumpserver.chen.framework.console.dataview.DataViewData;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.utils.CodeUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Clob;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Map;


public class DataExport {
    public static File export(String format, DataViewData data) throws Exception {
        var session = SessionManager.getCurrentSession();

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
        String timestamp = LocalDateTime.now().format(formatter);

        String filename = String.format("data_%s", timestamp);

        File f;

        switch (format) {
            case "excel":
                f = session.createFile(String.format("%s.xlsx", filename));
                new DataExportExcel().exportData(f.toPath().toString(), data);
                break;
            case "csv":
                f = session.createFile(String.format("%s.csv", filename));
                new DataExportCSV().exportData(f.toPath().toString(), data);
                break;
            default:
                throw new Exception("unsupported format: " + format);
        }
        return f;
    }
}


class DataExportExcel implements DataExportInterface {
    @Override
    public void exportData(String path, DataViewData data) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(path);
             Workbook workbook = new Workbook(fos, "JumpServer", "4.0")) {

            Worksheet sheet = workbook.newWorksheet("Data");
            List<Field> fields = data.getFields();
            List<Map<String, Object>> rows = data.getData();

            for (int col = 0; col < fields.size(); col++) {
                sheet.value(0, col, fields.get(col).getName());
            }

            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                Map<String, Object> row = rows.get(rowIndex);

                for (int col = 0; col < fields.size(); col++) {
                    Field field = fields.get(col);
                    Object obj = row.get(field.getName());

                    if (obj == null) {
                        sheet.value(rowIndex + 1, col, "NULL");
                    } else if (obj instanceof Clob clob) {
                        try {
                            sheet.value(rowIndex + 1, col, clob.getSubString(1, (int) clob.length()));
                        } catch (Exception e) {
                            sheet.value(rowIndex + 1, col, "ERROR_CLOB");
                        }
                    } else if (obj instanceof Date) {
                        sheet.value(rowIndex + 1, col, dateFormat.format(obj));
                    } else {
                        sheet.value(rowIndex + 1, col, obj.toString());
                    }
                }
            }
        }
    }
}

class DataExportCSV implements DataExportInterface {
    @Override
    public void exportData(String path, DataViewData data) throws Exception {
        var writer = Files.newBufferedWriter(Path.of(path));

        for (Field field : data.getFields()) {
            writeString(writer, field.getName());
            writer.write(",");
        }
        writer.newLine();
        for (Map<String, Object> row : data.getData()) {
            for (Field field : data.getFields()) {
                var obj = row.get(field.getName());
                if (obj == null) {
                    writer.write("NULL");
                    writer.write(",");
                } else if (obj instanceof Clob clob) {
                    writer.write(CodeUtils.escapeCsvValue(clob.getSubString(1, (int) clob.length())));
                    writer.write(",");
                } else if (obj instanceof Date) {
                    SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    writeString(writer, fmt.format(obj));
                } else {
                    writeString(writer, row.get(field.getName()));
                    writer.write(",");
                }
            }
            writer.newLine();
        }

        writer.newLine();
        writer.flush();
        writer.close();
    }

    private static void writeString(BufferedWriter writer, Object object) throws IOException {
        var str = object.toString();

        if (str.contains(",")) {
            str = "\"" + str + "\"";
        }
        writer.write(str);
    }
}