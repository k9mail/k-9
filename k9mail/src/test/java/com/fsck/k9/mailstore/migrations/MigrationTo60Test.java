package com.fsck.k9.mailstore.migrations;


import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.fsck.k9.mailstore.migrations.MigrationTo61.V60PendingAppend;
import com.fsck.k9.controller.MessagingControllerCommands.PendingCommand;
import com.fsck.k9.controller.MessagingControllerCommands.PendingEmptyTrash;
import com.fsck.k9.controller.MessagingControllerCommands.PendingExpunge;
import com.fsck.k9.controller.MessagingControllerCommands.PendingMarkAllAsRead;
import com.fsck.k9.controller.MessagingControllerCommands.PendingMoveOrCopy;
import com.fsck.k9.controller.MessagingControllerCommands.PendingSetFlag;
import com.fsck.k9.mail.Flag;
import com.fsck.k9.mailstore.migrations.MigrationTo60.OldPendingCommand;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;


@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class MigrationTo60Test {
    private static final String PENDING_COMMAND_MOVE_OR_COPY = "com.fsck.k9.MessagingController.moveOrCopy";
    private static final String PENDING_COMMAND_MOVE_OR_COPY_BULK = "com.fsck.k9.MessagingController.moveOrCopyBulk";
    private static final String PENDING_COMMAND_MOVE_OR_COPY_BULK_NEW = "com.fsck.k9.MessagingController.moveOrCopyBulkNew";
    private static final String PENDING_COMMAND_EMPTY_TRASH = "com.fsck.k9.MessagingController.emptyTrash";
    private static final String PENDING_COMMAND_SET_FLAG_BULK = "com.fsck.k9.MessagingController.setFlagBulk";
    private static final String PENDING_COMMAND_SET_FLAG = "com.fsck.k9.MessagingController.setFlag";
    private static final String PENDING_COMMAND_APPEND = "com.fsck.k9.MessagingController.append";
    private static final String PENDING_COMMAND_MARK_ALL_AS_READ = "com.fsck.k9.MessagingController.markAllAsRead";
    private static final String PENDING_COMMAND_EXPUNGE = "com.fsck.k9.MessagingController.expunge";

    private static final String SOURCE_FOLDER = "source_folder";
    private static final String DEST_FOLDER = "dest_folder";
    private static final boolean IS_COPY = true;
    private static final String[] UID_ARRAY = new String[] { "uid_1", "uid_2" };
    private static final boolean FLAG_STATE = true;
    private static final Flag FLAG = Flag.X_DESTROYED;
    private static final String UID = "uid";
    private static final HashMap<String, String> UID_MAP = new HashMap<>();

    static {
        UID_MAP.put("uid_1", "uid_other_1");
        UID_MAP.put("uid_2", "uid_other_2");
    }


    @Test
    public void migratePendingCommands_shouldChangeTableStructure() {
        SQLiteDatabase database = createV59Table();

        MigrationTo60.migratePendingCommands(database);

        List<String> columns = getColumnList(database, "pending_commands");
        assertEquals(asList("id", "command", "data"), columns);
    }

    @Test
    public void migratePendingCommands_withMultipleRuns_shouldNotThrow() {
        SQLiteDatabase database = createV59Table();
        MigrationTo60.migratePendingCommands(database);

        MigrationTo60.migratePendingCommands(database);
    }

    @Test
    public void migrateMoveOrCopy_withUidArray() {
        OldPendingCommand command = queueMoveOrCopy(SOURCE_FOLDER, DEST_FOLDER, IS_COPY, UID_ARRAY);

        PendingMoveOrCopy pendingCommand = (PendingMoveOrCopy) MigrationTo60.migratePendingCommand(command);

        assertEquals(SOURCE_FOLDER, pendingCommand.srcFolder);
        assertEquals(DEST_FOLDER, pendingCommand.destFolder);
        assertEquals(IS_COPY, pendingCommand.isCopy);
        assertEquals(asList(UID_ARRAY), pendingCommand.uids);
        assertNull(pendingCommand.newUidMap);
    }

    @Test
    public void migrateMoveOrCopy_withUidMap() {
        OldPendingCommand command = queueMoveOrCopy(SOURCE_FOLDER, DEST_FOLDER, IS_COPY, UID_MAP);

        PendingMoveOrCopy pendingCommand = (PendingMoveOrCopy) MigrationTo60.migratePendingCommand(command);

        assertEquals(SOURCE_FOLDER, pendingCommand.srcFolder);
        assertEquals(DEST_FOLDER, pendingCommand.destFolder);
        assertEquals(IS_COPY, pendingCommand.isCopy);
        assertEquals(UID_MAP, pendingCommand.newUidMap);
        assertNull(pendingCommand.uids);
    }

    @Test
    public void migrateMoveOrCopy_withOldFormat() {
        OldPendingCommand command = queueMoveOrCopyOld(SOURCE_FOLDER, DEST_FOLDER, IS_COPY, UID_ARRAY);

        PendingMoveOrCopy pendingCommand = (PendingMoveOrCopy) MigrationTo60.migratePendingCommand(command);

        assertEquals(SOURCE_FOLDER, pendingCommand.srcFolder);
        assertEquals(DEST_FOLDER, pendingCommand.destFolder);
        assertEquals(IS_COPY, pendingCommand.isCopy);
        assertEquals(asList(UID_ARRAY), pendingCommand.uids);
        assertNull(pendingCommand.newUidMap);
    }

    @Test
    public void migrateMoveOrCopy__withEvenOlderFormat() {
        OldPendingCommand command = queueMoveOrCopyEvenOlder(SOURCE_FOLDER, DEST_FOLDER, UID, IS_COPY);

        PendingMoveOrCopy pendingCommand = (PendingMoveOrCopy) MigrationTo60.migratePendingCommand(command);

        assertEquals(SOURCE_FOLDER, pendingCommand.srcFolder);
        assertEquals(DEST_FOLDER, pendingCommand.destFolder);
        assertEquals(IS_COPY, pendingCommand.isCopy);
        assertEquals(Collections.singletonList(UID), pendingCommand.uids);
        assertNull(pendingCommand.newUidMap);
    }

    @Test
    public void migrateSetFlag() {
        OldPendingCommand command = queueSetFlagBulk(SOURCE_FOLDER, FLAG_STATE, FLAG, UID_ARRAY);

        PendingSetFlag pendingCommand = (PendingSetFlag) MigrationTo60.migratePendingCommand(command);

        assertEquals(SOURCE_FOLDER, pendingCommand.folder);
        assertEquals(FLAG_STATE, pendingCommand.newState);
        assertEquals(FLAG, pendingCommand.flag);
        assertEquals(asList(UID_ARRAY), pendingCommand.uids);
    }

    @Test
    public void migrateSetFlag_oldFormat() {
        OldPendingCommand command = queueSetFlagOld(SOURCE_FOLDER, FLAG_STATE, FLAG, UID);

        PendingSetFlag pendingCommand = (PendingSetFlag) MigrationTo60.migratePendingCommand(command);

        assertEquals(SOURCE_FOLDER, pendingCommand.folder);
        assertEquals(FLAG_STATE, pendingCommand.newState);
        assertEquals(FLAG, pendingCommand.flag);
        assertEquals(Collections.singletonList(UID), pendingCommand.uids);
    }

    @Test
    public void migrateExpunge() {
        OldPendingCommand command = queueExpunge(SOURCE_FOLDER);

        PendingExpunge pendingCommand = (PendingExpunge) MigrationTo60.migratePendingCommand(command);

        assertEquals(SOURCE_FOLDER, pendingCommand.folder);
    }

    @Test
    public void migrateEmptyTrash() {
        OldPendingCommand command = queueEmptyTrash();

        PendingCommand pendingCommand = MigrationTo60.migratePendingCommand(command);

        assertTrue(pendingCommand instanceof PendingEmptyTrash);
    }

    @Test
    public void migrateMarkAllMessagesRead() {
        OldPendingCommand command = queueMarkAllMessagesRead(SOURCE_FOLDER);

        PendingMarkAllAsRead pendingCommand = (PendingMarkAllAsRead) MigrationTo60.migratePendingCommand(command);

        assertEquals(SOURCE_FOLDER, pendingCommand.folder);
    }

    @Test
    public void migrateAppend() {
        OldPendingCommand command = queueAppend(SOURCE_FOLDER, UID);

        V60PendingAppend pendingCommand = (V60PendingAppend) MigrationTo60.migratePendingCommand(command);

        assertEquals(SOURCE_FOLDER, pendingCommand.folder);
        assertEquals(UID, pendingCommand.uid);
    }

    OldPendingCommand queueAppend(String srcFolder, String uid) {
        OldPendingCommand command = new OldPendingCommand();
        command.command = PENDING_COMMAND_APPEND;
        command.arguments = new String[] { srcFolder, uid };
        return command;
    }

    OldPendingCommand queueMoveOrCopy(String srcFolder, String destFolder, boolean isCopy, String uids[]) {
        OldPendingCommand command = new OldPendingCommand();
        command.command = PENDING_COMMAND_MOVE_OR_COPY_BULK_NEW;

        int length = 4 + uids.length;
        command.arguments = new String[length];
        command.arguments[0] = srcFolder;
        command.arguments[1] = destFolder;
        command.arguments[2] = Boolean.toString(isCopy);
        command.arguments[3] = Boolean.toString(false);
        System.arraycopy(uids, 0, command.arguments, 4, uids.length);
        return command;
    }

    OldPendingCommand queueMoveOrCopy(String srcFolder, String destFolder, boolean isCopy, Map<String, String> uidMap) {
        OldPendingCommand command = new OldPendingCommand();
        command.command = PENDING_COMMAND_MOVE_OR_COPY_BULK_NEW;

        int length = 4 + uidMap.keySet().size() + uidMap.values().size();
        command.arguments = new String[length];
        command.arguments[0] = srcFolder;
        command.arguments[1] = destFolder;
        command.arguments[2] = Boolean.toString(isCopy);
        command.arguments[3] = Boolean.toString(true);
        Set<String> strings = uidMap.keySet();
        System.arraycopy(strings.toArray(new String[strings.size()]), 0, command.arguments, 4, uidMap.keySet().size());
        Collection<String> values = uidMap.values();
        System.arraycopy(values.toArray(new String[values.size()]), 0, command.arguments, 4 + uidMap.keySet().size(),
                uidMap.values().size());
        return command;
    }

    OldPendingCommand queueMoveOrCopyOld(String srcFolder, String destFolder, boolean isCopy, String uids[]) {
        OldPendingCommand command = new OldPendingCommand();
        command.command = PENDING_COMMAND_MOVE_OR_COPY_BULK;

        int length = 3 + uids.length;
        command.arguments = new String[length];
        command.arguments[0] = srcFolder;
        command.arguments[1] = destFolder;
        command.arguments[2] = Boolean.toString(isCopy);
        System.arraycopy(uids, 0, command.arguments, 3, uids.length);
        return command;
    }

    OldPendingCommand queueMoveOrCopyEvenOlder(String srcFolder, String destFolder, String uid, boolean isCopy) {
        OldPendingCommand command = new OldPendingCommand();
        command.command = PENDING_COMMAND_MOVE_OR_COPY;

        command.arguments = new String[4];
        command.arguments[0] = srcFolder;
        command.arguments[1] = uid;
        command.arguments[2] = destFolder;
        command.arguments[3] = Boolean.toString(isCopy);
        return command;
    }

    OldPendingCommand queueSetFlagBulk(String folderName, boolean newState, Flag flag, String[] uids) {
        OldPendingCommand command = new OldPendingCommand();
        command.command = PENDING_COMMAND_SET_FLAG_BULK;
        int length = 3 + uids.length;
        command.arguments = new String[length];
        command.arguments[0] = folderName;
        command.arguments[1] = Boolean.toString(newState);
        command.arguments[2] = flag.toString();
        System.arraycopy(uids, 0, command.arguments, 3, uids.length);
        return command;
    }

    OldPendingCommand queueSetFlagOld(String folderName, boolean newState, Flag flag, String uid) {
        OldPendingCommand command = new OldPendingCommand();
        command.command = PENDING_COMMAND_SET_FLAG;
        command.arguments = new String[4];
        command.arguments[0] = folderName;
        command.arguments[1] = uid;
        command.arguments[2] = Boolean.toString(newState);
        command.arguments[3] = flag.toString();
        return command;
    }

    OldPendingCommand queueExpunge(String folderName) {
        OldPendingCommand command = new OldPendingCommand();
        command.command = PENDING_COMMAND_EXPUNGE;
        command.arguments = new String[1];
        command.arguments[0] = folderName;
        return command;
    }

    OldPendingCommand queueEmptyTrash() {
        OldPendingCommand command = new OldPendingCommand();
        command.command = PENDING_COMMAND_EMPTY_TRASH;
        command.arguments = new String[0];
        return command;
    }

    OldPendingCommand queueMarkAllMessagesRead(final String folder) {
        OldPendingCommand command = new OldPendingCommand();
        command.command = PENDING_COMMAND_MARK_ALL_AS_READ;
        command.arguments = new String[] { folder };
        return command;
    }

    private List<String> getColumnList(SQLiteDatabase db, String table) {
        List<String> columns = new ArrayList<>();
        Cursor columnCursor = db.rawQuery("PRAGMA table_info(" + table + ")", null);
        try {
            while (columnCursor.moveToNext()) {
                columns.add(columnCursor.getString(1));
            }
        } finally {
            columnCursor.close();
        }
        return columns;
    }

    private SQLiteDatabase createV59Table() {
        SQLiteDatabase database = SQLiteDatabase.create(null);
        database.execSQL("CREATE TABLE pending_commands (" +
                "id INTEGER PRIMARY KEY, " +
                "command TEXT, " +
                "arguments TEXT" +
                ")");
        return database;
    }
}
