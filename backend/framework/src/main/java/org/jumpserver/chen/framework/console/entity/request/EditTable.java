package org.jumpserver.chen.framework.console.entity.request;


import java.util.ArrayList;
import java.util.List;

public class EditTable extends AbstractTableAction {
    private InsertRows insertRows;
    private DeleteRows deleteRows;
    private List<UpdateRow> updateRows = new ArrayList<>();

    public InsertRows getInsertRows() {
        return insertRows;
    }

    public void setInsertRows(InsertRows insertRows) {
        this.insertRows = insertRows;
    }

    public DeleteRows getDeleteRows() {
        return deleteRows;
    }

    public void setDeleteRows(DeleteRows deleteRows) {
        this.deleteRows = deleteRows;
    }

    public List<UpdateRow> getUpdateRows() {
        return updateRows;
    }

    public void setUpdateRows(List<UpdateRow> updateRows) {
        this.updateRows = updateRows;
    }
}
