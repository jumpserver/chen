package org.jumpserver.chen.framework.console.dataview.export;

import org.jumpserver.chen.framework.console.dataview.DataView;
import org.jumpserver.chen.framework.console.dataview.DataViewData;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataExportTest {
    @TempDir
    Path tempDir;

    @Test
    void excelCurrentPageExportKeepsRefreshAndPagingUsable() throws Exception {
        verifyCurrentPageExport(new DataExportExcel(), "xlsx");
    }

    @Test
    void csvCurrentPageExportKeepsRefreshAndPagingUsable() throws Exception {
        verifyCurrentPageExport(new DataExportCSV(), "csv");
    }

    @Test
    void allDataSnapshotExportsRemainUnchanged() throws Exception {
        DataViewData data = dataViewData();
        List<Field> fields = data.getFields();
        List<Map<String, Object>> rows = data.getData();

        Path excel = tempDir.resolve("all.xlsx");
        Path csv = tempDir.resolve("all.csv");
        new DataExportExcel().exportData(excel.toString(), data);
        new DataExportCSV().exportData(csv.toString(), data);

        assertSame(fields, data.getFields());
        assertSame(rows, data.getData());
        assertEquals(List.of("ROWNUM", "ID", "NAME"), fieldNames(data));
        assertEquals(3, data.getData().get(0).size());
        assertExportContent(excel, csv);
    }

    private void verifyCurrentPageExport(DataExportInterface exporter, String extension) throws Exception {
        List<Request> requests = new ArrayList<>();
        DataView view = new DataView("test", null, null);
        view.setLoadDataInterface(params -> {
            requests.add(new Request(params.getOffset(), params.getLimit()));
            return queryResult();
        });
        view.loadData();

        exportAndAssertSourceUnchanged(exporter, view.getData(), "refresh." + extension);
        view.refresh();

        exportAndAssertSourceUnchanged(exporter, view.getData(), "next-page." + extension);
        view.nextPage();

        exportAndAssertSourceUnchanged(exporter, view.getData(), "change-limit." + extension);
        view.changeLimit(100);

        assertEquals(List.of(
                new Request(0, 50),
                new Request(0, 50),
                new Request(50, 50),
                new Request(0, 100)
        ), requests);
        assertEquals(List.of("ROWNUM", "ID", "NAME"), fieldNames(view.getData()));
    }

    private void exportAndAssertSourceUnchanged(DataExportInterface exporter, DataViewData data, String filename)
            throws Exception {
        List<Field> fields = data.getFields();
        List<Map<String, Object>> rows = data.getData();

        exporter.exportData(tempDir.resolve(filename).toString(), data);

        assertSame(fields, data.getFields());
        assertSame(rows, data.getData());
        assertEquals(List.of("ROWNUM", "ID", "NAME"), fieldNames(data));
        assertEquals(3, data.getData().get(0).size());
    }

    private SQLQueryResult queryResult() {
        DataViewData data = dataViewData();
        SQLQueryResult result = new SQLQueryResult("SELECT ROWNUM, ID, NAME FROM users");
        result.setTotal(120);
        result.setFields(data.getFields());
        result.setData(List.of(List.of(1, 7, "Alice")));
        return result;
    }

    private DataViewData dataViewData() {
        DataViewData data = new DataViewData();
        data.setFields(new ArrayList<>(List.of(field("ROWNUM"), field("ID"), field("NAME"))));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ROWNUM", 1);
        row.put("ID", 7);
        row.put("NAME", "Alice");
        data.setData(new ArrayList<>(List.of(row)));
        return data;
    }

    private Field field(String name) {
        Field field = new Field();
        field.setName(name);
        return field;
    }

    private List<String> fieldNames(DataViewData data) {
        return data.getFields().stream().map(Field::getName).toList();
    }

    private void assertExportContent(Path excel, Path csv) throws IOException {
        String workbookXml = readWorkbookXml(excel);
        assertTrue(workbookXml.contains("ID"));
        assertTrue(workbookXml.contains("NAME"));
        assertTrue(workbookXml.contains("Alice"));
        assertFalse(workbookXml.contains("ROWNUM"));

        String csvContent = Files.readString(csv);
        assertEquals("ID,NAME," + System.lineSeparator()
                + "7,Alice," + System.lineSeparator()
                + System.lineSeparator(), csvContent);
    }

    private String readWorkbookXml(Path workbook) throws IOException {
        StringBuilder xml = new StringBuilder();
        try (ZipFile zip = new ZipFile(workbook.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                try (var input = zip.getInputStream(entry)) {
                    xml.append(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return xml.toString();
    }

    private record Request(int offset, int limit) {
    }
}
