package com.fsck.k9.mailstore.migrations;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.fsck.k9.Account;
import com.fsck.k9.controller.MessagingController;
import com.fsck.k9.controller.MessagingControllerCommands.PendingCommand;
import com.fsck.k9.controller.MessagingControllerCommands.PendingAppend;
import com.fsck.k9.controller.PendingCommandSerializer;
import com.fsck.k9.helper.Utility;
import com.fsck.k9.mail.MessagingException;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import timber.log.Timber;

class MigrationTo61 {

    static void migratePendingAppends(SQLiteDatabase db) {
        List<PendingAppend> newCommands = new ArrayList<>();

        try {
            for (V60PendingAppend oldCommand : getPendingAppends(db)) {
                PendingAppend newPendingAppend = PendingAppend.create(oldCommand.folder,
                        Collections.singletonList(oldCommand.uid));
                newPendingAppend.databaseId = oldCommand.databaseId;
                newCommands.add(newPendingAppend);
            }
        } catch (IOException e) {
            Timber.e(e, "Could not fetch pending appends while updating database to V61");
        }

        ContentValues cv = new ContentValues();
        PendingCommandSerializer pendingCommandSerializer = PendingCommandSerializer.getInstance();

        for (PendingAppend newCommand : newCommands) {
            cv.clear();
            cv.put("data", pendingCommandSerializer.serialize(newCommand));
            db.update("pending_commands", cv, "id = ?",
                    new String[] { Long.toString(newCommand.databaseId) });
        }
    }

    private static List<V60PendingAppend> getPendingAppends(SQLiteDatabase db) throws IOException {
        Cursor cursor = null;
        try {
            Moshi moshi = new Moshi.Builder().build();
            JsonAdapter<V60PendingAppend> adapter = moshi.adapter(V60PendingAppend.class);
            cursor = db.query("pending_commands", new String[] { "id", "command", "data" },
                    "command = ?", new String[] { "append" }, null, null, "id ASC");
            List<V60PendingAppend> commands = new ArrayList<>();
            while (cursor.moveToNext()) {
                V60PendingAppend command = adapter.fromJson(cursor.getString(2));
                commands.add(command);
            }
            return commands;
        } finally {
            Utility.closeQuietly(cursor);
        }
    }

    static class V60PendingAppend extends PendingCommand {
        public final String folder;
        public final String uid;

        public static V60PendingAppend create(String folderName, String uid) {
            return new V60PendingAppend(folderName, uid);
        }

        private V60PendingAppend(String folder, String uid) {
            this.folder = folder;
            this.uid = uid;
        }

        @Override
        public String getCommandName() {
            return "append";
        }

        @Override
        public void execute(MessagingController controller, Account account) throws MessagingException {
            throw new IllegalStateException("Stub");
        }
    }
}
