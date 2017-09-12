package com.fsck.k9.mailstore.migrations;


import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.text.TextUtils;

import com.fsck.k9.helper.Utility;
import com.fsck.k9.mail.Folder;
import com.fsck.k9.mailstore.LockableDatabase;
import com.fsck.k9.mailstore.LockableDatabase.DbCallback;
import com.fsck.k9.mailstore.LockableDatabase.WrappedException;
import timber.log.Timber;

import com.fsck.k9.K9;
import com.fsck.k9.mail.FetchProfile;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mailstore.LocalFolder;
import com.fsck.k9.mailstore.LocalMessage;
import com.fsck.k9.mailstore.LocalStore;
import com.fsck.k9.message.extractors.MessageFulltextCreator;


class MigrationTo55 {
    static void createFtsSearchTable(SQLiteDatabase db, MigrationsHelper migrationsHelper) {
        db.execSQL("CREATE VIRTUAL TABLE messages_fulltext USING fts4 (fulltext)");

        LocalStore localStore = migrationsHelper.getLocalStore();
        MessageFulltextCreator fulltextCreator = localStore.getMessageFulltextCreator();

        try {
            List<LocalFolder> folders = getPersonalNamespaces(localStore);
            ContentValues cv = new ContentValues();
            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.BODY);
            for (LocalFolder folder : folders) {
                List<String> messageUids = folder.getAllMessageUids();
                for (String messageUid : messageUids) {
                    LocalMessage localMessage = folder.getMessage(messageUid);
                    folder.fetch(Collections.singletonList(localMessage), fp, null);

                    String fulltext = fulltextCreator.createFulltext(localMessage);
                    if (!TextUtils.isEmpty(fulltext)) {
                        Timber.d("fulltext for msg id %d is %d chars long", localMessage.getId(), fulltext.length());
                        cv.clear();
                        cv.put("docid", localMessage.getId());
                        cv.put("fulltext", fulltext);
                        db.insert("messages_fulltext", null, cv);
                    } else {
                        Timber.d("no fulltext for msg id %d :(", localMessage.getId());
                    }
                }
            }
        } catch (MessagingException e) {
            Timber.e(e, "error indexing fulltext - skipping rest, fts index is incomplete!");
        }
    }

    private static List<LocalFolder> getPersonalNamespaces(final LocalStore localStore) throws MessagingException {
        final String FOLDER_COLS = "folders.id, name, visible_limit, last_updated, status, push_state, last_pushed, " +
                "integrate, top_group, poll_class, push_class, display_class, notify_class, more_messages";
        final List<LocalFolder> folders = new LinkedList<>();
        LockableDatabase database = localStore.getDatabase();
        try {
            database.execute(false, new DbCallback< List <? extends Folder>>() {
                @Override
                public List <? extends Folder> doDbWork(final SQLiteDatabase db) throws WrappedException {
                    Cursor cursor = null;

                    try {
                        cursor = db.rawQuery("SELECT " + FOLDER_COLS + " FROM folders " +
                                "ORDER BY name ASC", null);
                        while (cursor.moveToNext()) {
                            if (cursor.isNull(0)) {
                                continue;
                            }
                            String folderName = cursor.getString(1);
                            LocalFolder folder = new LocalFolder(localStore, folderName);
                            folder.open(cursor);

                            folders.add(folder);
                        }
                        return folders;
                    } catch (MessagingException e) {
                        throw new WrappedException(e);
                    } finally {
                        Utility.closeQuietly(cursor);
                    }
                }
            });
        } catch (WrappedException e) {
            throw(MessagingException) e.getCause();
        }
        return folders;
    }
}
