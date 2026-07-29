package com.webhookpage.ui;

import com.webhookpage.WebhookRequestLog;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

public final class RequestLogTableModel extends AbstractTableModel {

    private static final String[] COLUMNS = {
            "Time", "Method", "URL", "IP", "User-Agent", "Body Summary"
    };

    private final List<WebhookRequestLog> rows = new ArrayList<>();
    private final int maxRows;

    public RequestLogTableModel(int maxRows) {
        this.maxRows = Math.max(100, maxRows);
    }

    public synchronized void addEntry(WebhookRequestLog entry) {
        rows.add(0, entry);
        while (rows.size() > maxRows) {
            rows.remove(rows.size() - 1);
        }
        fireTableDataChanged();
    }

    public synchronized void clear() {
        rows.clear();
        fireTableDataChanged();
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        WebhookRequestLog entry = rows.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> entry.getFormattedTime();
            case 1 -> entry.getMethod();
            case 2 -> entry.getUrl();
            case 3 -> entry.getClientIp();
            case 4 -> entry.getUserAgent();
            case 5 -> entry.getBodySummary();
            default -> "";
        };
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return false;
    }
}
