package com.fsck.k9.helper;

import java.util.Comparator;

import android.database.Cursor;


public class MergeCursorWithUniqueId extends MergeCursor {
    private static final int SHIFT = 48;
    private static final long MAX_ID = (1L << SHIFT) - 1;
    private static final long MAX_CURSORS = 1L << (63 - SHIFT);

    private int columnCount = -1;
    private int idColumnIndex = -1;


    public MergeCursorWithUniqueId(Cursor[] cursors, Comparator<Cursor> comparator) {
        super(cursors, comparator);

        if (cursors.length > MAX_CURSORS) {
            throw new IllegalArgumentException("This class only supports up to " +
                    MAX_CURSORS + " cursors");
        }
    }

    @Override
    public int getColumnCount() {
        if (columnCount == -1) {
            columnCount = super.getColumnCount();
        }

        return columnCount + 1;
    }

    @Override
    public int getColumnIndex(String columnName) {
        if ("_id".equals(columnName)) {
            return getUniqueIdColumnIndex();
        }

        return super.getColumnIndexOrThrow(columnName);
    }

    @Override
    public int getColumnIndexOrThrow(String columnName) throws IllegalArgumentException {
        if ("_id".equals(columnName)) {
            return getUniqueIdColumnIndex();
        }

        return super.getColumnIndexOrThrow(columnName);
    }

    @Override
    public long getLong(int columnIndex) {
        if (columnIndex == getUniqueIdColumnIndex()) {
            long id = getPerCursorId();
            if (id > MAX_ID) {
                throw new RuntimeException("Sorry, " + this.getClass().getName() +
                        " can only handle '_id' values up to " + SHIFT + " bits.");
            }

            return (((long) activeCursorIndex) << SHIFT) + id;
        }

        return super.getLong(columnIndex);
    }

    private int getUniqueIdColumnIndex() {
        if (columnCount == -1) {
            columnCount = super.getColumnCount();
        }

        return columnCount;
    }

    private long getPerCursorId() {
        if (idColumnIndex == -1) {
            idColumnIndex = super.getColumnIndexOrThrow("_id");
        }

        return super.getLong(idColumnIndex);
    }
}
