package com.fsck.k9.mailstore;


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.UUID;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.support.annotation.NonNull;

import com.fsck.k9.mail.store.imap.ImapFolder;
import timber.log.Timber;

import com.fsck.k9.Account;
import com.fsck.k9.K9;
import com.fsck.k9.activity.MessageReference;
import com.fsck.k9.activity.Search;
import com.fsck.k9.helper.FileHelper;
import com.fsck.k9.helper.Utility;
import com.fsck.k9.mail.Address;
import com.fsck.k9.mail.Body;
import com.fsck.k9.mail.BodyPart;
import com.fsck.k9.mail.BoundaryGenerator;
import com.fsck.k9.mail.FetchProfile;
import com.fsck.k9.mail.Flag;
import com.fsck.k9.mail.Folder;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.Message.RecipientType;
import com.fsck.k9.mail.MessageRetrievalListener;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.Multipart;
import com.fsck.k9.mail.Part;
import com.fsck.k9.mail.filter.CountingOutputStream;
import com.fsck.k9.mail.internet.BinaryTempFileBody;
import com.fsck.k9.mail.internet.MimeHeader;
import com.fsck.k9.mail.internet.MimeMessage;
import com.fsck.k9.mail.internet.MimeMultipart;
import com.fsck.k9.mail.internet.MimeUtility;
import com.fsck.k9.mail.internet.SizeAware;
import com.fsck.k9.mail.message.MessageHeaderParser;
import com.fsck.k9.mailstore.LockableDatabase.DbCallback;
import com.fsck.k9.mailstore.LockableDatabase.WrappedException;
import com.fsck.k9.message.extractors.AttachmentCounter;
import com.fsck.k9.message.extractors.AttachmentInfoExtractor;
import com.fsck.k9.message.extractors.MessageFulltextCreator;
import com.fsck.k9.message.extractors.MessagePreviewCreator;
import com.fsck.k9.message.extractors.PreviewResult;
import com.fsck.k9.message.extractors.PreviewResult.PreviewType;
import com.fsck.k9.preferences.Storage;
import com.fsck.k9.preferences.StorageEditor;
import org.apache.commons.io.IOUtils;
import org.apache.james.mime4j.util.MimeUtil;


public class LocalFolder extends Folder<LocalMessage> implements Serializable {

    private static final long serialVersionUID = -1973296520918624767L;
    private static final int MAX_BODY_SIZE_FOR_DATABASE = 16 * 1024;
    static final long INVALID_MESSAGE_PART_ID = -1;

    private final LocalStore localStore;
    private final AttachmentInfoExtractor attachmentInfoExtractor;

    private String mName = null;
    private long mFolderId = -1;
    private int mVisibleLimit = -1;
    private String prefId = null;
    private long uidValidity = ImapFolder.INVALID_UID_VALIDITY;
    private long highestModSeq = ImapFolder.INVALID_HIGHEST_MOD_SEQ;
    private FolderClass mDisplayClass = FolderClass.NO_CLASS;
    private FolderClass mSyncClass = FolderClass.INHERITED;
    private FolderClass mPushClass = FolderClass.SECOND_CLASS;
    private FolderClass mNotifyClass = FolderClass.INHERITED;
    private boolean mInTopGroup = false;
    private String mPushState = null;
    private boolean mIntegrate = false;
    // mLastUid is used during syncs. It holds the highest UID within the local folder so we
    // know whether or not an unread message added to the local folder is actually "new" or not.
    private Integer mLastUid = null;
    private MoreMessages moreMessages = MoreMessages.UNKNOWN;

    public LocalFolder(LocalStore localStore, String name) {
        super();
        this.localStore = localStore;
        this.mName = name;
        attachmentInfoExtractor = localStore.attachmentInfoExtractor;

        if (getAccount().getInboxFolderName().equals(getName())) {
            mSyncClass =  FolderClass.FIRST_CLASS;
            mPushClass =  FolderClass.FIRST_CLASS;
            mInTopGroup = true;
        }
    }

    public LocalFolder(LocalStore localStore, long id) {
        super();
        this.localStore = localStore;
        this.mFolderId = id;
        attachmentInfoExtractor = localStore.attachmentInfoExtractor;
    }

    public long getId() {
        return mFolderId;
    }

    public String getAccountUuid()
    {
        return getAccount().getUuid();
    }

    public boolean getSignatureUse() {
        return getAccount().getSignatureUse();
    }

    public void setLastSelectedFolderName(String destFolderName) {
        getAccount().setLastSelectedFolderName(destFolderName);
    }

    public boolean syncRemoteDeletions() {
        return getAccount().syncRemoteDeletions();
    }

    @Override
    public void open(final int mode) throws MessagingException {

        if (isOpen() && (getMode() == mode || mode == OPEN_MODE_RO)) {
            return;
        } else if (isOpen()) {
            //previously opened in READ_ONLY and now requesting READ_WRITE
            //so close connection and reopen
            close();
        }

        try {
            this.localStore.database.execute(false, new DbCallback<Void>() {
                @Override
                public Void doDbWork(final SQLiteDatabase db) throws WrappedException {
                    Cursor cursor = null;
                    try {
                        String baseQuery = "SELECT " + LocalStore.GET_FOLDER_COLS + " FROM folders ";

                        if (mName != null) {
                            cursor = db.rawQuery(baseQuery + "where folders.name = ?", new String[] { mName });
                        } else {
                            cursor = db.rawQuery(baseQuery + "where folders.id = ?", new String[] { Long.toString(mFolderId) });
                        }

                        if (cursor.moveToFirst() && !cursor.isNull(LocalStore.FOLDER_ID_INDEX)) {
                            int folderId = cursor.getInt(LocalStore.FOLDER_ID_INDEX);
                            if (folderId > 0) {
                                open(cursor);
                            }
                        } else {
                            Timber.w("Creating folder %s with existing id %d", getName(), getId());
                            create(FolderType.HOLDS_MESSAGES);
                            open(mode);
                        }
                    } catch (MessagingException e) {
                        throw new WrappedException(e);
                    } finally {
                        Utility.closeQuietly(cursor);
                    }
                    return null;
                }
            });
        } catch (WrappedException e) {
            throw(MessagingException) e.getCause();
        }
    }

    public void open(Cursor cursor) throws MessagingException {
        mFolderId = cursor.getInt(LocalStore.FOLDER_ID_INDEX);
        mName = cursor.getString(LocalStore.FOLDER_NAME_INDEX);
        mVisibleLimit = cursor.getInt(LocalStore.FOLDER_VISIBLE_LIMIT_INDEX);

        //The index check here is because of an issue that occurs while upgrading the db to V55 - at that point, the
        //uid_validity and highest_mod_Seq columns do not exist yet
        int uidUalidityIndex = cursor.getColumnIndex("uid_validity");
        if (uidUalidityIndex != -1 && !cursor.isNull(uidUalidityIndex)) {
            uidValidity = cursor.getLong(uidUalidityIndex);
        }

        int highestModSeqIndex = cursor.getColumnIndex("highest_mod_seq");
        if (highestModSeqIndex != -1 && !cursor.isNull(highestModSeqIndex)) {
            highestModSeq = cursor.getLong(highestModSeqIndex);
        }

        mPushState = cursor.getString(LocalStore.FOLDER_PUSH_STATE_INDEX);
        super.setStatus(cursor.getString(LocalStore.FOLDER_STATUS_INDEX));
        // Only want to set the local variable stored in the super class.  This class
        // does a DB update on setLastChecked
        super.setLastChecked(cursor.getLong(LocalStore.FOLDER_LAST_CHECKED_INDEX));
        super.setLastPush(cursor.getLong(LocalStore.FOLDER_LAST_PUSHED_INDEX));
        mInTopGroup = cursor.getInt(LocalStore.FOLDER_TOP_GROUP_INDEX) == 1;
        mIntegrate = cursor.getInt(LocalStore.FOLDER_INTEGRATE_INDEX) == 1;
        String noClass = FolderClass.NO_CLASS.toString();
        String displayClass = cursor.getString(LocalStore.FOLDER_DISPLAY_CLASS_INDEX);
        mDisplayClass = Folder.FolderClass.valueOf((displayClass == null) ? noClass : displayClass);
        String notifyClass = cursor.getString(LocalStore.FOLDER_NOTIFY_CLASS_INDEX);
        mNotifyClass = Folder.FolderClass.valueOf((notifyClass == null) ? noClass : notifyClass);
        String pushClass = cursor.getString(LocalStore.FOLDER_PUSH_CLASS_INDEX);
        mPushClass = Folder.FolderClass.valueOf((pushClass == null) ? noClass : pushClass);
        String syncClass = cursor.getString(LocalStore.FOLDER_SYNC_CLASS_INDEX);
        mSyncClass = Folder.FolderClass.valueOf((syncClass == null) ? noClass : syncClass);
        String moreMessagesValue = cursor.getString(LocalStore.MORE_MESSAGES_INDEX);
        moreMessages = MoreMessages.fromDatabaseName(moreMessagesValue);
    }

    @Override
    public boolean isOpen() {
        return (mFolderId != -1 && mName != null);
    }

    @Override
    public int getMode() {
        return OPEN_MODE_RW;
    }

    @Override
    public String getName() {
        return mName;
    }

    @Override
    public boolean exists() throws MessagingException {
        return this.localStore.database.execute(false, new DbCallback<Boolean>() {
            @Override
            public Boolean doDbWork(final SQLiteDatabase db) throws WrappedException {
                Cursor cursor = null;
                try {
                    cursor = db.rawQuery("SELECT id FROM folders where folders.name = ?",
                            new String[] { LocalFolder.this.getName() });
                    if (cursor.moveToFirst()) {
                        int folderId = cursor.getInt(0);
                        return (folderId > 0);
                    }

                    return false;
                } finally {
                    Utility.closeQuietly(cursor);
                }
            }
        });
    }

    @Override
    public boolean create(FolderType type) throws MessagingException {
        return create(type, getAccount().getDisplayCount());
    }

    @Override
    public boolean create(FolderType type, final int visibleLimit) throws MessagingException {
        if (exists()) {
            throw new MessagingException("Folder " + mName + " already exists.");
        }
        List<LocalFolder> foldersToCreate = new ArrayList<>(1);
        foldersToCreate.add(this);
        this.localStore.createFolders(foldersToCreate, visibleLimit);

        return true;
    }

    class PreferencesHolder {
        FolderClass displayClass = mDisplayClass;
        FolderClass syncClass = mSyncClass;
        FolderClass notifyClass = mNotifyClass;
        FolderClass pushClass = mPushClass;
        boolean inTopGroup = mInTopGroup;
        boolean integrate = mIntegrate;
    }

    @Override
    public void close() {
        mFolderId = -1;
    }

    @Override
    public int getMessageCount() throws MessagingException {
        try {
            return this.localStore.database.execute(false, new DbCallback<Integer>() {
                @Override
                public Integer doDbWork(final SQLiteDatabase db) throws WrappedException {
                    try {
                        open(OPEN_MODE_RW);
                    } catch (MessagingException e) {
                        throw new WrappedException(e);
                    }
                    Cursor cursor = null;
                    try {
                        cursor = db.rawQuery(
                                "SELECT COUNT(id) FROM messages " +
                                "WHERE empty = 0 AND deleted = 0 and folder_id = ?",
                                new String[] { Long.toString(mFolderId) });
                        cursor.moveToFirst();
                        return cursor.getInt(0);   //messagecount
                    } finally {
                        Utility.closeQuietly(cursor);
                    }
                }
            });
        } catch (WrappedException e) {
            throw (MessagingException) e.getCause();
        }
    }

    @Override
    public int getUnreadMessageCount() throws MessagingException {
        if (mFolderId == -1) {
            open(OPEN_MODE_RW);
        }

        try {
            return this.localStore.database.execute(false, new DbCallback<Integer>() {
                @Override
                public Integer doDbWork(final SQLiteDatabase db) throws WrappedException {
                    int unreadMessageCount = 0;
                    Cursor cursor = db.query("messages", new String[] { "COUNT(id)" },
                            "folder_id = ? AND empty = 0 AND deleted = 0 AND read=0",
                            new String[] { Long.toString(mFolderId) }, null, null, null);

                    try {
                        if (cursor.moveToFirst()) {
                            unreadMessageCount = cursor.getInt(0);
                        }
                    } finally {
                        cursor.close();
                    }

                    return unreadMessageCount;
                }
            });
        } catch (WrappedException e) {
            throw(MessagingException) e.getCause();
        }
    }

    @Override
    public int getFlaggedMessageCount() throws MessagingException {
        if (mFolderId == -1) {
            open(OPEN_MODE_RW);
        }

        try {
            return this.localStore.database.execute(false, new DbCallback<Integer>() {
                @Override
                public Integer doDbWork(final SQLiteDatabase db) throws WrappedException {
                    int flaggedMessageCount = 0;
                    Cursor cursor = db.query("messages", new String[] { "COUNT(id)" },
                            "folder_id = ? AND empty = 0 AND deleted = 0 AND flagged = 1",
                            new String[] { Long.toString(mFolderId) }, null, null, null);

                    try {
                        if (cursor.moveToFirst()) {
                            flaggedMessageCount = cursor.getInt(0);
                        }
                    } finally {
                        cursor.close();
                    }

                    return flaggedMessageCount;
                }
            });
        } catch (WrappedException e) {
            throw(MessagingException) e.getCause();
        }
    }

    @Override
    public void setLastChecked(final long lastChecked) throws MessagingException {
        try {
            open(OPEN_MODE_RW);
            LocalFolder.super.setLastChecked(lastChecked);
        } catch (MessagingException e) {
            throw new WrappedException(e);
        }
        updateFolderColumn("last_updated", lastChecked);
    }

    @Override
    public void setLastPush(final long lastChecked) throws MessagingException {
        try {
            open(OPEN_MODE_RW);
            LocalFolder.super.setLastPush(lastChecked);
        } catch (MessagingException e) {
            throw new WrappedException(e);
        }
        updateFolderColumn("last_pushed", lastChecked);
    }

    public int getVisibleLimit() throws MessagingException {
        open(OPEN_MODE_RW);
        return mVisibleLimit;
    }

    public void purgeToVisibleLimit(MessageRemovalListener listener) throws MessagingException {
        //don't purge messages while a Search is active since it might throw away search results
        if (!Search.isActive()) {
            if (mVisibleLimit == 0) {
                return ;
            }
            open(OPEN_MODE_RW);
            List<? extends Message> messages = getMessages(null, false);
            for (int i = mVisibleLimit; i < messages.size(); i++) {
                if (listener != null) {
                    listener.messageRemoved(messages.get(i));
                }
                messages.get(i).destroy();
            }
        }
    }


    public void setVisibleLimit(final int visibleLimit) throws MessagingException {
        updateMoreMessagesOnVisibleLimitChange(visibleLimit, mVisibleLimit);

        mVisibleLimit = visibleLimit;
        updateFolderColumn("visible_limit", mVisibleLimit);
    }

    private void updateMoreMessagesOnVisibleLimitChange(int newVisibleLimit, int oldVisibleLimit)
            throws MessagingException {

        boolean growVisibleLimit = newVisibleLimit > oldVisibleLimit;
        boolean shrinkVisibleLimit = newVisibleLimit < oldVisibleLimit;
        boolean moreMessagesWereAvailable = getMoreMessages() == MoreMessages.TRUE;

        if (growVisibleLimit || (shrinkVisibleLimit && !moreMessagesWereAvailable)) {
            setMoreMessages(MoreMessages.UNKNOWN);
        }
    }

    @Override
    public void setStatus(final String status) throws MessagingException {
        updateFolderColumn("status", status);
    }

    public void setUidValidity(final long uidValidity) throws MessagingException {
        this.uidValidity = uidValidity;
        updateFolderColumn("uid_validity", uidValidity);
    }

    public boolean hasCachedUidValidity() throws MessagingException {
        return uidValidity != ImapFolder.INVALID_UID_VALIDITY;
    }

    public void setHighestModSeq(final long highestModSeq) throws MessagingException {
        this.highestModSeq = highestModSeq;
        updateFolderColumn("highest_mod_seq", highestModSeq);
    }

    public void invalidateHighestModSeq() throws MessagingException {
        this.highestModSeq = ImapFolder.INVALID_HIGHEST_MOD_SEQ;
        updateFolderColumn("highest_mod_seq", null);
    }

    public boolean isCachedHighestModSeqValid() throws MessagingException {
        return highestModSeq != ImapFolder.INVALID_HIGHEST_MOD_SEQ;
    }

    public void setPushState(final String pushState) throws MessagingException {
        mPushState = pushState;
        updateFolderColumn("push_state", pushState);
    }

    private void updateFolderColumn(final String column, final Object value) throws MessagingException {
        try {
            this.localStore.database.execute(false, new DbCallback<Void>() {
                @Override
                public Void doDbWork(final SQLiteDatabase db) throws WrappedException {
                    try {
                        open(OPEN_MODE_RW);
                    } catch (MessagingException e) {
                        throw new WrappedException(e);
                    }
                    db.execSQL("UPDATE folders SET " + column + " = ? WHERE id = ?", new Object[] { value, mFolderId });
                    return null;
                }
            });
        } catch (WrappedException e) {
            throw(MessagingException) e.getCause();
        }
    }

    public long getUidValidity() {
        return uidValidity;
    }

    public long getHighestModSeq() {
        return highestModSeq;
    }

    public String getPushState() {
        return mPushState;
    }

    @Override
    public FolderClass getDisplayClass() {
        return mDisplayClass;
    }

    @Override
    public FolderClass getSyncClass() {
        return (FolderClass.INHERITED == mSyncClass) ? getDisplayClass() : mSyncClass;
    }

    public FolderClass getRawSyncClass() {
        return mSyncClass;
    }

    public FolderClass getNotifyClass() {
        return (FolderClass.INHERITED == mNotifyClass) ? getPushClass() : mNotifyClass;
    }

    public FolderClass getRawNotifyClass() {
        return mNotifyClass;
    }

    @Override
    public FolderClass getPushClass() {
        return (FolderClass.INHERITED == mPushClass) ? getSyncClass() : mPushClass;
    }

    public FolderClass getRawPushClass() {
        return mPushClass;
    }

    public void setDisplayClass(FolderClass displayClass) throws MessagingException {
        mDisplayClass = displayClass;
        updateFolderColumn("display_class", mDisplayClass.name());
    }

    public void setSyncClass(FolderClass syncClass) throws MessagingException {
        mSyncClass = syncClass;
        updateFolderColumn("poll_class", mSyncClass.name());
    }

    public void setPushClass(FolderClass pushClass) throws MessagingException {
        mPushClass = pushClass;
        updateFolderColumn("push_class", mPushClass.name());
    }

    public void setNotifyClass(FolderClass notifyClass) throws MessagingException {
        mNotifyClass = notifyClass;
        updateFolderColumn("notify_class", mNotifyClass.name());
    }

    public boolean isIntegrate() {
        return mIntegrate;
    }

    public void setIntegrate(boolean integrate) throws MessagingException {
        mIntegrate = integrate;
        updateFolderColumn("integrate", mIntegrate ? 1 : 0);
    }

    public boolean hasMoreMessages() {
        return moreMessages != MoreMessages.FALSE;
    }

    public MoreMessages getMoreMessages() {
        return moreMessages;
    }

    public void setMoreMessages(MoreMessages moreMessages) throws MessagingException {
        this.moreMessages = moreMessages;
        updateFolderColumn("more_messages", moreMessages.getDatabaseName());
    }

    private String getPrefId(String name) {
        if (prefId == null) {
            prefId = this.localStore.uUid + "." + name;
        }

        return prefId;
    }

    private String getPrefId() throws MessagingException {
        open(OPEN_MODE_RW);
        return getPrefId(mName);
    }

    public void delete() throws MessagingException {
        String id = getPrefId();

        StorageEditor editor = this.localStore.getStorage().edit();

        editor.remove(id + ".displayMode");
        editor.remove(id + ".syncMode");
        editor.remove(id + ".pushMode");
        editor.remove(id + ".inTopGroup");
        editor.remove(id + ".integrate");

        editor.commit();
    }

    public void save() throws MessagingException {
        StorageEditor editor = this.localStore.getStorage().edit();
        save(editor);
        editor.commit();
    }

    public void save(StorageEditor editor) throws MessagingException {
        String id = getPrefId();

        // there can be a lot of folders.  For the defaults, let's not save prefs, saving space, except for INBOX
        if (mDisplayClass == FolderClass.NO_CLASS && !getAccount().getInboxFolderName().equals(getName())) {
            editor.remove(id + ".displayMode");
        } else {
            editor.putString(id + ".displayMode", mDisplayClass.name());
        }

        if (mSyncClass == FolderClass.INHERITED && !getAccount().getInboxFolderName().equals(getName())) {
            editor.remove(id + ".syncMode");
        } else {
            editor.putString(id + ".syncMode", mSyncClass.name());
        }

        if (mNotifyClass == FolderClass.INHERITED && !getAccount().getInboxFolderName().equals(getName())) {
            editor.remove(id + ".notifyMode");
        } else {
            editor.putString(id + ".notifyMode", mNotifyClass.name());
        }

        if (mPushClass == FolderClass.SECOND_CLASS && !getAccount().getInboxFolderName().equals(getName())) {
            editor.remove(id + ".pushMode");
        } else {
            editor.putString(id + ".pushMode", mPushClass.name());
        }
        editor.putBoolean(id + ".inTopGroup", mInTopGroup);

        editor.putBoolean(id + ".integrate", mIntegrate);

    }

    public void refresh(String name, PreferencesHolder prefHolder) {
        String id = getPrefId(name);

        Storage storage = this.localStore.getStorage();

        try {
            prefHolder.displayClass = FolderClass.valueOf(storage.getString(id + ".displayMode",
                                      prefHolder.displayClass.name()));
        } catch (Exception e) {
            Timber.e(e, "Unable to load displayMode for %s", getName());
        }
        if (prefHolder.displayClass == FolderClass.NONE) {
            prefHolder.displayClass = FolderClass.NO_CLASS;
        }

        try {
            prefHolder.syncClass = FolderClass.valueOf(storage.getString(id  + ".syncMode",
                                   prefHolder.syncClass.name()));
        } catch (Exception e) {
            Timber.e(e, "Unable to load syncMode for %s", getName());

        }
        if (prefHolder.syncClass == FolderClass.NONE) {
            prefHolder.syncClass = FolderClass.INHERITED;
        }

        try {
            prefHolder.notifyClass = FolderClass.valueOf(storage.getString(id  + ".notifyMode",
                                   prefHolder.notifyClass.name()));
        } catch (Exception e) {
            Timber.e(e, "Unable to load notifyMode for %s", getName());
        }
        if (prefHolder.notifyClass == FolderClass.NONE) {
            prefHolder.notifyClass = FolderClass.INHERITED;
        }

        try {
            prefHolder.pushClass = FolderClass.valueOf(storage.getString(id  + ".pushMode",
                                   prefHolder.pushClass.name()));
        } catch (Exception e) {
            Timber.e(e, "Unable to load pushMode for %s", getName());
        }
        if (prefHolder.pushClass == FolderClass.NONE) {
            prefHolder.pushClass = FolderClass.INHERITED;
        }
        prefHolder.inTopGroup = storage.getBoolean(id + ".inTopGroup", prefHolder.inTopGroup);
        prefHolder.integrate = storage.getBoolean(id + ".integrate", prefHolder.integrate);
    }

    @Override
    public void fetch(final List<LocalMessage> messages, final FetchProfile fp, final MessageRetrievalListener<LocalMessage> listener)
            throws MessagingException {
        try {
            this.localStore.database.execute(false, new DbCallback<Void>() {
                @Override
                public Void doDbWork(final SQLiteDatabase db) throws WrappedException {
                    try {
                        open(OPEN_MODE_RW);
                        if (fp.contains(FetchProfile.Item.BODY)) {
                            for (Message message : messages) {
                                LocalMessage localMessage = (LocalMessage) message;

                                loadMessageParts(db, localMessage);
                            }
                        }
                    } catch (MessagingException e) {
                        throw new WrappedException(e);
                    }
                    return null;
                }
            });
        } catch (WrappedException e) {
            throw (MessagingException) e.getCause();
        }
    }

    private void loadMessageParts(SQLiteDatabase db, LocalMessage message) throws MessagingException {
        Map<Long, Part> partById = new HashMap<>();

        String[] columns = {
                "id",                   // 0
                "type",                 // 1
                "parent",               // 2
                "mime_type",            // 3
                "decoded_body_size",    // 4
                "display_name",         // 5
                "header",               // 6
                "encoding",             // 7
                "charset",              // 8
                "data_location",        // 9
                "data",                 // 10
                "preamble",             // 11
                "epilogue",             // 12
                "boundary",             // 13
                "content_id",           // 14
                "server_extra",         // 15
        };
        Cursor cursor = db.query("message_parts", columns, "root = ?",
                new String[] { String.valueOf(message.getMessagePartId()) }, null, null, "seq");
        try {
            while (cursor.moveToNext()) {
                loadMessagePart(message, partById, cursor);
            }
        } finally {
            cursor.close();
        }
    }

    private void loadMessagePart(LocalMessage message, Map<Long, Part> partById, Cursor cursor)
            throws MessagingException {

        long id = cursor.getLong(0);
        long parentId = cursor.getLong(2);
        String mimeType = cursor.getString(3);
        long size = cursor.getLong(4);
        byte[] header = cursor.getBlob(6);
        int dataLocation = cursor.getInt(9);
        String serverExtra = cursor.getString(15);
        // TODO we don't currently cache much of the part data which is computed with AttachmentInfoExtractor,
        // TODO might want to do that at a later point?
        // String displayName = cursor.getString(5);
        // int type = cursor.getInt(1);
        // boolean inlineAttachment = (type == MessagePartType.HIDDEN_ATTACHMENT);

        final Part part;
        if (id == message.getMessagePartId()) {
            part = message;
        } else {
            Part parentPart = partById.get(parentId);
            if (parentPart == null) {
                throw new IllegalStateException("Parent part not found");
            }

            String parentMimeType = parentPart.getMimeType();
            if (MimeUtility.isMultipart(parentMimeType)) {
                BodyPart bodyPart = new LocalBodyPart(getAccountUuid(), message, id, size);
                ((Multipart) parentPart.getBody()).addBodyPart(bodyPart);
                part = bodyPart;
            } else if (MimeUtility.isMessage(parentMimeType)) {
                Message innerMessage = new LocalMimeMessage(getAccountUuid(), message, id);
                parentPart.setBody(innerMessage);
                part = innerMessage;
            } else {
                throw new IllegalStateException("Parent is neither a multipart nor a message");
            }

            parseHeaderBytes(part, header);
        }
        partById.put(id, part);
        part.setServerExtra(serverExtra);

        if (MimeUtility.isMultipart(mimeType)) {
            byte[] preamble = cursor.getBlob(11);
            byte[] epilogue = cursor.getBlob(12);
            String boundary = cursor.getString(13);

            MimeMultipart multipart = new MimeMultipart(mimeType, boundary);
            part.setBody(multipart);
            multipart.setPreamble(preamble);
            multipart.setEpilogue(epilogue);
        } else if (dataLocation == DataLocation.IN_DATABASE) {
            String encoding = cursor.getString(7);
            byte[] data = cursor.getBlob(10);

            Body body = new BinaryMemoryBody(data, encoding);
            part.setBody(body);
        } else if (dataLocation == DataLocation.ON_DISK) {
            String encoding = cursor.getString(7);

            File file = localStore.getAttachmentFile(Long.toString(id));
            if (file.exists()) {
                Body body = new FileBackedBody(file, encoding);
                part.setBody(body);
            }
        }
    }

    private void parseHeaderBytes(Part part, byte[] header) throws MessagingException {
        MessageHeaderParser.parse(part, new ByteArrayInputStream(header));
    }

    @Override
    public List<LocalMessage> getMessages(int start, int end, Date earliestDate, MessageRetrievalListener<LocalMessage> listener)
            throws MessagingException {
        open(OPEN_MODE_RW);
        throw new MessagingException(
                "LocalStore.getMessages(int, int, MessageRetrievalListener) not yet implemented");
    }

    @Override
    public boolean areMoreMessagesAvailable(int indexOfOldestMessage, Date earliestDate)
            throws IOException, MessagingException {
        throw new IllegalStateException("Not implemented");
    }

    public String getMessageUidById(final long id) throws MessagingException {
        try {
            return this.localStore.database.execute(false, new DbCallback<String>() {
                @Override
                public String doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException {
                    try {
                        open(OPEN_MODE_RW);
                        Cursor cursor = null;

                        try {
                            cursor = db.rawQuery(
                                    "SELECT uid FROM messages WHERE id = ? AND folder_id = ?",
                                    new String[] { Long.toString(id), Long.toString(mFolderId) });
                            if (!cursor.moveToNext()) {
                                return null;
                            }
                            return cursor.getString(0);
                        } finally {
                            Utility.closeQuietly(cursor);
                        }
                    } catch (MessagingException e) {
                        throw new WrappedException(e);
                    }
                }
            });
        } catch (WrappedException e) {
            throw(MessagingException) e.getCause();
        }
    }

    @Override
    public LocalMessage getMessage(final String uid) throws MessagingException {
        try {
            return this.localStore.database.execute(false, new DbCallback<LocalMessage>() {
                @Override
                public LocalMessage doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException {
                    try {
                        open(OPEN_MODE_RW);
                        LocalMessage message = new LocalMessage(LocalFolder.this.localStore, uid, LocalFolder.this);
                        Cursor cursor = null;

                        try {
                            cursor = db.rawQuery(
                                    "SELECT " +
                                            LocalStore.GET_MESSAGES_COLS +
                                            "FROM messages " +
                                            "LEFT JOIN message_parts ON (message_parts.id = messages.message_part_id) " +
                                            "LEFT JOIN threads ON (threads.message_id = messages.id) " +
                                            "WHERE uid = ? AND folder_id = ?",
                                    new String[] { message.getUid(), Long.toString(mFolderId) });

                            if (!cursor.moveToNext()) {
                                return null;
                            }
                            message.populateFromGetMessageCursor(cursor);
                        } finally {
                            Utility.closeQuietly(cursor);
                        }
                        return message;
                    } catch (MessagingException e) {
                        throw new WrappedException(e);
                    }
                }
            });
        } catch (WrappedException e) {
            throw(MessagingException) e.getCause();
        }
    }

    public LocalMessage getMessageUidAndFlags(final String uid) throws MessagingException {
        try {
            return this.localStore.database.execute(false, new DbCallback<LocalMessage>() {
                @Override
                public LocalMessage doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException {
                    try {
                        open(OPEN_MODE_RW);
                        LocalMessage message = new LocalMessage(LocalFolder.this.localStore, uid, LocalFolder.this);
                        Cursor cursor = null;

                        try {
                            cursor = db.rawQuery(
                                    "SELECT " +
                                            "uid, flags, deleted, read, flagged, answered, forwarded " +
                                            "FROM messages " +
                                            "WHERE uid = ? AND folder_id = ?",
                                    new String[] { message.getUid(), Long.toString(mFolderId) });

                            if (!cursor.moveToNext()) {
                                return null;
                            }
                            message.populateUidAndFlags(cursor);
                        } finally {
                            Utility.closeQuietly(cursor);
                        }
                        return message;
                    } catch (MessagingException e) {
                        throw new WrappedException(e);
                    }
                }
            });
        } catch (WrappedException e) {
            throw(MessagingException) e.getCause();
        }
    }

    public Map<String,Long> getAllMessagesAndEffectiveDates() throws MessagingException {
        try {
            return  localStore.database.execute(false, new DbCallback<Map<String, Long>>() {
                @Override
                public Map<String, Long> doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException {
                    Cursor cursor = null;
                    HashMap<String, Long> result = new HashMap<>();

                    try {
                        open(OPEN_MODE_RO);

                        cursor = db.rawQuery(
                                "SELECT uid, date " +
                                        "FROM messages " +
                                        "WHERE empty = 0 AND deleted = 0 AND " +
                                        "folder_id = ? ORDER BY date DESC",
                                new String[] { Long.toString(mFolderId) });

                        while (cursor.moveToNext()) {
                            String uid = cursor.getString(0);
                            Long date = cursor.isNull(1) ? null : cursor.getLong(1);
                            result.put(uid, date);
                        }
                    } catch (MessagingException e) {
                        throw new WrappedException(e);
                    } finally {
                        Utility.closeQuietly(cursor);
                    }

                    return result;
                }
            });
        } catch (WrappedException e) {
            throw(MessagingException) e.getCause();
        }
    }

    public List<LocalMessage> getMessages(MessageRetrievalListener<LocalMessage> listener) throws MessagingException {
        return getMessages(listener, true);
    }

    public List<LocalMessage> getMessages(final MessageRetrievalListener<LocalMessage> listener,
            final boolean includeDeleted) throws MessagingException {
        try {
            return  localStore.database.execute(false, new DbCallback<List<LocalMessage>>() {
                @Override
                public List<LocalMessage> doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException {
                    try {
                        open(OPEN_MODE_RW);
                        return LocalFolder.this.localStore.getMessages(listener, LocalFolder.this,
                                "SELECT " + LocalStore.GET_MESSAGES_COLS +
                                        "FROM messages " +
                                        "LEFT JOIN message_parts ON (message_parts.id = messages.message_part_id) " +
                                        "LEFT JOIN threads ON (threads.message_id = messages.id) " +
                                        "WHERE empty = 0 AND " +
                                        (includeDeleted ? "" : "deleted = 0 AND ") +
                                        "folder_id = ? ORDER BY date DESC",
                                new String[] { Long.toString(mFolderId) });
                    } catch (MessagingException e) {
                        throw new WrappedException(e);
                    }
                }
            });
        } catch (WrappedException e) {
            throw(MessagingException) e.getCause();
        }
    }

    public List<String> getAllMessageUids() throws MessagingException {
        try {
            return  localStore.database.execute(false, new DbCallback<List<String>>() {
                @Override
                public List<String> doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException {
                    Cursor cursor = null;
                    ArrayList<String> result = new ArrayList<>();

                    try {
                        open(OPEN_MODE_RO);

                        cursor = db.rawQuery(
                                "SELECT uid " +
                                        "FROM messages " +
                                        "WHERE empty = 0 AND deleted = 0 AND " +
                                        "folder_id = ? ORDER BY date DESC",
                                new String[] { Long.toString(mFolderId) });

                        while (cursor.moveToNext()) {
                            String uid = cursor.getString(0);
                            result.add(uid);
                        }
                    } catch (MessagingException e) {
                        throw new WrappedException(e);
                    } finally {
                        Utility.closeQuietly(cursor);
                    }

                    return result;
                }
            });
        } catch (WrappedException e) {
            throw(MessagingException) e.getCause();
        }
    }

    public Long getSmallestMessageUid() throws MessagingException {
        try {
            return localStore.database.execute(false, new DbCallback<Long>() {
                @Override
                public Long doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException {
                    Cursor cursor = null;
                    try {
                        open(OPEN_MODE_RO);

                        cursor = db.rawQuery(
                                "SELECT min(uid) " +
                                        "FROM messages " +
                                        "WHERE empty = 0 AND deleted = 0 AND " +
                                        "folder_id = ? ORDER BY date DESC",
                                new String[] { Long.toString(mFolderId) });

                        if (!cursor.moveToFirst()) {
                            return null;
                        }
                        return cursor.getLong(0);
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
    }

    public List<LocalMessage> getMessagesByUids(@NonNull Collection<String> uids) throws MessagingException {
        open(OPEN_MODE_RW);
        List<LocalMessage> messages = new ArrayList<>();
        for (String uid : uids) {
            LocalMessage message = getMessage(uid);
            if (message != null) {
                messages.add(message);
            }
        }
        return messages;
    }

    public List<LocalMessage> getMessagesByReference(@NonNull List<MessageReference> messageReferences)
            throws MessagingException {
        open(OPEN_MODE_RW);

        String accountUuid = getAccountUuid();
        String folderName = getName();

        List<LocalMessage> messages = new ArrayList<>();
        for (MessageReference messageReference : messageReferences) {
            if (!accountUuid.equals(messageReference.getAccountUuid())) {
                throw new IllegalArgumentException("all message references must belong to this Account!");
            }
            if (!folderName.equals(messageReference.getFolderName())) {
                throw new IllegalArgumentException("all message references must belong to this LocalFolder!");
            }

            LocalMessage message = getMessage(messageReference.getUid());
            if (message != null) {
                messages.add(message);
            }
        }
        return messages;
    }

    @Override
    public Map<String, String> copyMessages(List<? extends Message> msgs, Folder folder) throws MessagingException {
        if (!(folder instanceof LocalFolder)) {
            throw new MessagingException("copyMessages called with incorrect Folder");
        }
        return ((LocalFolder) folder).appendMessages(msgs, true);
    }

    @Override
    public Map<String, String> moveMessages(final List<? extends Message> msgs, final Folder destFolder) throws MessagingException {
        if (!(destFolder instanceof LocalFolder)) {
            throw new MessagingException("moveMessages called with non-LocalFolder");
        }

        final LocalFolder lDestFolder = (LocalFolder)destFolder;

        final Map<String, String> uidMap = new HashMap<>();

        try {
            this.localStore.database.execute(false, new DbCallback<Void>() {
                @Override
                public Void doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException {
                    try {
                        lDestFolder.open(OPEN_MODE_RW);
                        for (Message message : msgs) {
                            LocalMessage lMessage = (LocalMessage)message;

                            String oldUID = message.getUid();

                            Timber.d("Updating folder_id to %s for message with UID %s, " +
                                            "id %d currently in folder %s",
                                    lDestFolder.getId(),
                                    message.getUid(),
                                    lMessage.getId(),
                                    getName());

                            String newUid = K9.LOCAL_UID_PREFIX + UUID.randomUUID().toString();
                            message.setUid(newUid);

                            uidMap.put(oldUID, newUid);

                            // Message threading in the target folder
                            ThreadInfo threadInfo = lDestFolder.doMessageThreading(db, message);

                            /*
                             * "Move" the message into the new folder
                             */
                            long msgId = lMessage.getId();
                            String[] idArg = new String[] { Long.toString(msgId) };

                            ContentValues cv = new ContentValues();
                            cv.put("folder_id", lDestFolder.getId());
                            cv.put("uid", newUid);

                            db.update("messages", cv, "id = ?", idArg);

                            // Create/update entry in 'threads' table for the message in the
                            // target folder
                            cv.clear();
                            cv.put("message_id", msgId);
                            if (threadInfo.threadId == -1) {
                                if (threadInfo.rootId != -1) {
                                    cv.put("root", threadInfo.rootId);
                                }

                                if (threadInfo.parentId != -1) {
                                    cv.put("parent", threadInfo.parentId);
                                }

                                db.insert("threads", null, cv);
                            } else {
                                db.update("threads", cv, "id = ?",
                                        new String[] { Long.toString(threadInfo.threadId) });
                            }

                            /*
                             * Add a placeholder message so we won't download the original
                             * message again if we synchronize before the remote move is
                             * complete.
                             */

                            // We need to open this folder to get the folder id
                            open(OPEN_MODE_RW);

                            cv.clear();
                            cv.put("uid", oldUID);
                            cv.putNull("flags");
                            cv.put("read", 1);
                            cv.put("deleted", 1);
                            cv.put("folder_id", mFolderId);
                            cv.put("empty", 0);

                            String messageId = message.getMessageId();
                            if (messageId != null) {
                                cv.put("message_id", messageId);
                            }

                            final long newId;
                            if (threadInfo.msgId != -1) {
                                // There already existed an empty message in the target folder.
                                // Let's use it as placeholder.

                                newId = threadInfo.msgId;

                                db.update("messages", cv, "id = ?",
                                        new String[] { Long.toString(newId) });
                            } else {
                                newId = db.insert("messages", null, cv);
                            }

                            /*
                             * Update old entry in 'threads' table to point to the newly
                             * created placeholder.
                             */

                            cv.clear();
                            cv.put("message_id", newId);
                            db.update("threads", cv, "id = ?",
                                    new String[] { Long.toString(lMessage.getThreadId()) });
                        }
                    } catch (MessagingException e) {
                        throw new WrappedException(e);
                    }
                    return null;
                }
            });

            this.localStore.notifyChange();

            return uidMap;
        } catch (WrappedException e) {
            throw(MessagingException) e.getCause();
        }

    }

    /**
     * Convenience transaction wrapper for storing a message and set it as fully downloaded. Implemented mainly to speed up DB transaction commit.
     *
     * @param message Message to store. Never <code>null</code>.
     * @param runnable What to do before setting {@link Flag#X_DOWNLOADED_FULL}. Never <code>null</code>.
     * @return The local version of the message. Never <code>null</code>.
     * @throws MessagingException
     */
    public LocalMessage storeSmallMessage(final Message message, final Runnable runnable) throws MessagingException {
        return this.localStore.database.execute(true, new DbCallback<LocalMessage>() {
            @Override
            public LocalMessage doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException {
                try {
                    appendMessages(Collections.singletonList(message));
                    final String uid = message.getUid();
                    final LocalMessage result = getMessage(uid);
                    runnable.run();
                    // Set a flag indicating this message has now be fully downloaded
                    result.setFlag(Flag.X_DOWNLOADED_FULL, true);
                    return result;
                } catch (MessagingException e) {
                    throw new WrappedException(e);
                }
            }
        });
    }

    /**
     * The method differs slightly from the contract; If an incoming message already has a uid
     * assigned and it matches the uid of an existing message then this message will replace the
     * old message. It is implemented as a delete/insert. This functionality is used in saving
     * of drafts and re-synchronization of updated server messages.
     *
     * NOTE that although this method is located in the LocalStore class, it is not guaranteed
     * that the messages supplied as parameters are actually {@link LocalMessage} instances (in
     * fact, in most cases, they are not). Therefore, if you want to make local changes only to a
     * message, retrieve the appropriate local message instance first (if it already exists).
     */
    @Override
    public Map<String, String> appendMessages(List<? extends Message> messages) throws MessagingException {
        return appendMessages(messages, false);
    }

    public void destroyMessages(final List<? extends Message> messages) {
        try {
            this.localStore.database.execute(true, new DbCallback<Void>() {
                @Override
                public Void doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException {
                    for (Message message : messages) {
                        try {
                            message.destroy();
                        } catch (MessagingException e) {
                            throw new WrappedException(e);
                        }
                    }
                    return null;
                }
            });
        } catch (MessagingException e) {
            throw new WrappedException(e);
        }
    }

    private ThreadInfo getThreadInfo(SQLiteDatabase db, String messageId, boolean onlyEmpty) {
        if (messageId == null) {
            return null;
        }

        String sql = "SELECT t.id, t.message_id, t.root, t.parent " +
                "FROM messages m " +
                "LEFT JOIN threads t ON (t.message_id = m.id) " +
                "WHERE m.folder_id = ? AND m.message_id = ? " +
                ((onlyEmpty) ? "AND m.empty = 1 " : "") +
                "ORDER BY m.id LIMIT 1";
        String[] selectionArgs = { Long.toString(mFolderId), messageId };
        Cursor cursor = db.rawQuery(sql, selectionArgs);

        if (cursor != null) {
            try {
                if (cursor.getCount() > 0) {
                    cursor.moveToFirst();
                    long threadId = cursor.getLong(0);
                    long msgId = cursor.getLong(1);
                    long rootId = (cursor.isNull(2)) ? -1 : cursor.getLong(2);
                    long parentId = (cursor.isNull(3)) ? -1 : cursor.getLong(3);

                    return new ThreadInfo(threadId, msgId, messageId, rootId, parentId);
                }
            } finally {
                cursor.close();
            }
        }

        return null;
    }

    /**
     * The method differs slightly from the contract; If an incoming message already has a uid
     * assigned and it matches the uid of an existing message then this message will replace
     * the old message. This functionality is used in saving of drafts and re-synchronization
     * of updated server messages.
     *
     * NOTE that although this method is located in the LocalStore class, it is not guaranteed
     * that the messages supplied as parameters are actually {@link LocalMessage} instances (in
     * fact, in most cases, they are not). Therefore, if you want to make local changes only to a
     * message, retrieve the appropriate local message instance first (if it already exists).
     * @return uidMap of srcUids -> destUids
     */
    private Map<String, String> appendMessages(final List<? extends Message> messages, final boolean copy)
            throws MessagingException {
        open(OPEN_MODE_RW);
        try {
            final Map<String, String> uidMap = new HashMap<>();
            this.localStore.database.execute(true, new DbCallback<Void>() {
                @Override
                public Void doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException {
                    try {
                        for (Message message : messages) {
                            saveMessage(db, message, copy, uidMap);
                        }
                    } catch (MessagingException e) {
                        throw new WrappedException(e);
                    }
                    return null;
                }
            });

            this.localStore.notifyChange();

            return uidMap;
        } catch (WrappedException e) {
            throw (MessagingException) e.getCause();
        }
    }

    protected void saveMessage(SQLiteDatabase db, Message message, boolean copy, Map<String, String> uidMap)
            throws MessagingException {
        if (!(message instanceof MimeMessage)) {
            throw new Error("LocalStore can only store Messages that extend MimeMessage");
        }

        long oldMessageId = -1;
        String uid = message.getUid();
        boolean shouldCreateNewMessage = uid == null || copy;
        if (shouldCreateNewMessage) {
            String randomLocalUid = K9.LOCAL_UID_PREFIX + UUID.randomUUID().toString();

            if (copy) {
                // Save mapping: source UID -> target UID
                uidMap.put(uid, randomLocalUid);
            } else {
                // Modify the Message instance to reference the new UID
                message.setUid(randomLocalUid);
            }

            // The message will be saved with the newly generated UID
            uid = randomLocalUid;
        } else {
            LocalMessage oldMessage = getMessage(uid);

            if (oldMessage != null) {
                oldMessageId = oldMessage.getId();

                long oldRootMessagePartId = oldMessage.getMessagePartId();
                deleteMessagePartsAndDataFromDisk(oldRootMessagePartId);
            }
        }

        long rootId = -1;
        long parentId = -1;
        long msgId;

        if (oldMessageId == -1) {
            // This is a new message. Do the message threading.
            ThreadInfo threadInfo = doMessageThreading(db, message);
            oldMessageId = threadInfo.msgId;
            rootId = threadInfo.rootId;
            parentId = threadInfo.parentId;
        }

        try {
            MessagePreviewCreator previewCreator = localStore.getMessagePreviewCreator();
            PreviewResult previewResult = previewCreator.createPreview(message);
            PreviewType previewType = previewResult.getPreviewType();
            DatabasePreviewType databasePreviewType = DatabasePreviewType.fromPreviewType(previewType);

            MessageFulltextCreator fulltextCreator = localStore.getMessageFulltextCreator();
            String fulltext = fulltextCreator.createFulltext(message);

            AttachmentCounter attachmentCounter = localStore.getAttachmentCounter();
            int attachmentCount = attachmentCounter.getAttachmentCount(message);

            long rootMessagePartId = saveMessageParts(db, message);

            ContentValues cv = new ContentValues();
            cv.put("message_part_id", rootMessagePartId);
            cv.put("uid", uid);
            cv.put("subject", message.getSubject());
            cv.put("sender_list", Address.pack(message.getFrom()));
            cv.put("date", message.getSentDate() == null
                    ? System.currentTimeMillis() : message.getSentDate().getTime());
            cv.put("flags", this.localStore.serializeFlags(message.getFlags()));
            cv.put("deleted", message.isSet(Flag.DELETED) ? 1 : 0);
            cv.put("read", message.isSet(Flag.SEEN) ? 1 : 0);
            cv.put("flagged", message.isSet(Flag.FLAGGED) ? 1 : 0);
            cv.put("answered", message.isSet(Flag.ANSWERED) ? 1 : 0);
            cv.put("forwarded", message.isSet(Flag.FORWARDED) ? 1 : 0);
            cv.put("folder_id", mFolderId);
            cv.put("to_list", Address.pack(message.getRecipients(RecipientType.TO)));
            cv.put("cc_list", Address.pack(message.getRecipients(RecipientType.CC)));
            cv.put("bcc_list", Address.pack(message.getRecipients(RecipientType.BCC)));
            cv.put("reply_to_list", Address.pack(message.getReplyTo()));
            cv.put("attachment_count", attachmentCount);
            cv.put("internal_date", message.getInternalDate() == null
                    ? System.currentTimeMillis() : message.getInternalDate().getTime());
            cv.put("mime_type", message.getMimeType());
            cv.put("empty", 0);

            cv.put("preview_type", databasePreviewType.getDatabaseValue());
            if (previewResult.isPreviewTextAvailable()) {
                cv.put("preview", previewResult.getPreviewText());
            } else {
                cv.putNull("preview");
            }

            String messageId = message.getMessageId();
            if (messageId != null) {
                cv.put("message_id", messageId);
            }

            if (oldMessageId == -1) {
                msgId = db.insert("messages", "uid", cv);

                // Create entry in 'threads' table
                cv.clear();
                cv.put("message_id", msgId);

                if (rootId != -1) {
                    cv.put("root", rootId);
                }
                if (parentId != -1) {
                    cv.put("parent", parentId);
                }

                db.insert("threads", null, cv);
            } else {
                msgId = oldMessageId;
                db.update("messages", cv, "id = ?", new String[] { Long.toString(oldMessageId) });
            }

            if (fulltext != null) {
                cv.clear();
                cv.put("docid", msgId);
                cv.put("fulltext", fulltext);
                db.replace("messages_fulltext", null, cv);
            }
        } catch (Exception e) {
            throw new MessagingException("Error appending message: " + message.getSubject(), e);
        }
    }

    private long saveMessageParts(SQLiteDatabase db, Message message) throws IOException, MessagingException {
        long rootMessagePartId = saveMessagePart(db, new PartContainer(-1, message), -1, 0);

        Stack<PartContainer> partsToSave = new Stack<>();
        addChildrenToStack(partsToSave, message, rootMessagePartId);

        int order = 1;
        while (!partsToSave.isEmpty()) {
            PartContainer partContainer = partsToSave.pop();
            long messagePartId = saveMessagePart(db, partContainer, rootMessagePartId, order);
            order++;

            addChildrenToStack(partsToSave, partContainer.part, messagePartId);
        }

        return rootMessagePartId;
    }

    private long saveMessagePart(SQLiteDatabase db, PartContainer partContainer, long rootMessagePartId, int order)
            throws IOException, MessagingException {

        Part part = partContainer.part;

        ContentValues cv = new ContentValues();
        if (rootMessagePartId != -1) {
            cv.put("root", rootMessagePartId);
        }
        cv.put("parent", partContainer.parent);
        cv.put("seq", order);
        cv.put("server_extra", part.getServerExtra());

        return updateOrInsertMessagePart(db, cv, part, INVALID_MESSAGE_PART_ID);
    }

    private void moveTemporaryFile(File tempFile, String messagePartId) throws IOException {
        File destinationFile = localStore.getAttachmentFile(messagePartId);
        FileHelper.renameOrMoveByCopying(tempFile, destinationFile);
    }

    private long updateOrInsertMessagePart(SQLiteDatabase db, ContentValues cv, Part part, long existingMessagePartId)
            throws IOException, MessagingException {
        byte[] headerBytes = getHeaderBytes(part);

        cv.put("mime_type", part.getMimeType());
        cv.put("header", headerBytes);
        cv.put("type", MessagePartType.UNKNOWN);

        File file = null;
        Body body = part.getBody();
        if (body instanceof Multipart) {
            multipartToContentValues(cv, (Multipart) body);
        } else if (body == null) {
            missingPartToContentValues(cv, part);
        } else if (body instanceof Message) {
            messageMarkerToContentValues(cv);
        } else {
            file = leafPartToContentValues(cv, part, body);
        }

        long messagePartId;
        if (existingMessagePartId != INVALID_MESSAGE_PART_ID) {
            messagePartId = existingMessagePartId;
            db.update("message_parts", cv, "id = ?", new String[] { Long.toString(messagePartId) });
        } else {
            messagePartId = db.insertOrThrow("message_parts", null, cv);
        }

        if (file != null) {
            moveTemporaryFile(file, Long.toString(messagePartId));
        }

        return messagePartId;
    }

    private void multipartToContentValues(ContentValues cv, Multipart multipart) {
        cv.put("data_location", DataLocation.CHILD_PART_CONTAINS_DATA);
        cv.put("preamble", multipart.getPreamble());
        cv.put("epilogue", multipart.getEpilogue());
        cv.put("boundary", multipart.getBoundary());
    }

    private void missingPartToContentValues(ContentValues cv, Part part) throws MessagingException {
        AttachmentViewInfo attachment = attachmentInfoExtractor.extractAttachmentInfoForDatabase(part);
        cv.put("display_name", attachment.displayName);
        cv.put("data_location", DataLocation.MISSING);
        cv.put("decoded_body_size", attachment.size);

        if (MimeUtility.isMultipart(part.getMimeType())) {
            cv.put("boundary", BoundaryGenerator.getInstance().generateBoundary());
        }
    }

    private void messageMarkerToContentValues(ContentValues cv) throws MessagingException {
        cv.put("data_location", DataLocation.CHILD_PART_CONTAINS_DATA);
    }

    private File leafPartToContentValues(ContentValues cv, Part part, Body body)
            throws MessagingException, IOException {
        AttachmentViewInfo attachment = attachmentInfoExtractor.extractAttachmentInfoForDatabase(part);
        cv.put("display_name", attachment.displayName);

        String encoding = getTransferEncoding(part);

        if (!(body instanceof SizeAware)) {
            throw new IllegalStateException("Body needs to implement SizeAware");
        }

        SizeAware sizeAwareBody = (SizeAware) body;
        long fileSize = sizeAwareBody.getSize();

        File file = null;
        int dataLocation;
        if (fileSize > MAX_BODY_SIZE_FOR_DATABASE) {
            dataLocation = DataLocation.ON_DISK;

            file = writeBodyToDiskIfNecessary(part);

            long size = decodeAndCountBytes(file, encoding, fileSize);
            cv.put("decoded_body_size", size);
        } else {
            dataLocation = DataLocation.IN_DATABASE;

            byte[] bodyData = getBodyBytes(body);
            cv.put("data", bodyData);

            long size = decodeAndCountBytes(bodyData, encoding, bodyData.length);
            cv.put("decoded_body_size", size);
        }
        cv.put("data_location", dataLocation);
        cv.put("encoding", encoding);
        cv.put("content_id", part.getContentId());

        return file;
    }

    private File writeBodyToDiskIfNecessary(Part part) throws MessagingException, IOException {
        Body body = part.getBody();
        if (body instanceof BinaryTempFileBody) {
            return ((BinaryTempFileBody) body).getFile();
        } else {
            return writeBodyToDisk(body);
        }
    }

    private File writeBodyToDisk(Body body) throws IOException, MessagingException {
        File file = File.createTempFile("body", null, BinaryTempFileBody.getTempDirectory());
        OutputStream out = new FileOutputStream(file);
        try {
            body.writeTo(out);
        } finally {
            out.close();
        }

        return file;
    }

    private long decodeAndCountBytes(byte[] bodyData, String encoding, long fallbackValue) {
        ByteArrayInputStream rawInputStream = new ByteArrayInputStream(bodyData);
        return decodeAndCountBytes(rawInputStream, encoding, fallbackValue);
    }

    private long decodeAndCountBytes(File file, String encoding, long fallbackValue)
            throws MessagingException, IOException {
        InputStream inputStream = new FileInputStream(file);
        try {
            return decodeAndCountBytes(inputStream, encoding, fallbackValue);
        } finally {
            inputStream.close();
        }
    }

    private long decodeAndCountBytes(InputStream rawInputStream, String encoding, long fallbackValue) {
        InputStream decodingInputStream = localStore.getDecodingInputStream(rawInputStream, encoding);
        try {
            CountingOutputStream countingOutputStream = new CountingOutputStream();
            try {
                IOUtils.copy(decodingInputStream, countingOutputStream);

                return countingOutputStream.getCount();
            } catch (IOException e) {
                return fallbackValue;
            }
        } finally {
            try {
                decodingInputStream.close();
            } catch (IOException e) { /* ignore */ }
        }
    }

    private byte[] getHeaderBytes(Part part) throws IOException, MessagingException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        part.writeHeaderTo(output);
        return output.toByteArray();
    }

    private byte[] getBodyBytes(Body body) throws IOException, MessagingException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        body.writeTo(output);
        return output.toByteArray();
    }

    private String getTransferEncoding(Part part) {
        String[] contentTransferEncoding = part.getHeader(MimeHeader.HEADER_CONTENT_TRANSFER_ENCODING);
        if (contentTransferEncoding.length > 0) {
            return contentTransferEncoding[0].toLowerCase(Locale.US);
        }

        return MimeUtil.ENC_7BIT;
    }

    private void addChildrenToStack(Stack<PartContainer> stack, Part part, long parentMessageId) {
        Body body = part.getBody();
        if (body instanceof Multipart) {
            Multipart multipart = (Multipart) body;
            for (int i = multipart.getCount() - 1; i >= 0; i--) {
                BodyPart childPart = multipart.getBodyPart(i);
                stack.push(new PartContainer(parentMessageId, childPart));
            }
        } else if (body instanceof Message) {
            Message innerMessage = (Message) body;
            stack.push(new PartContainer(parentMessageId, innerMessage));
        }
    }

    private static class PartContainer {
        public final long parent;
        public final Part part;

        PartContainer(long parent, Part part) {
            this.parent = parent;
            this.part = part;
        }
    }

    public void addPartToMessage(final LocalMessage message, final Part part) throws MessagingException {
        open(OPEN_MODE_RW);

        localStore.database.execute(false, new DbCallback<Void>() {
            @Override
            public Void doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException {
                long messagePartId;

                Cursor cursor = db.query("message_parts", new String[] { "id" }, "root = ? AND server_extra = ?",
                        new String[] { Long.toString(message.getMessagePartId()), part.getServerExtra() },
                        null, null, null);
                try {
                    if (!cursor.moveToFirst()) {
                        throw new IllegalStateException("Message part not found");
                    }

                    messagePartId = cursor.getLong(0);
                } finally {
                    cursor.close();
                }

                try {
                    updateOrInsertMessagePart(db, new ContentValues(), part, messagePartId);
                } catch (Exception e) {
                    Timber.e(e, "Error writing message part");
                }

                return null;
            }
        });

        localStore.notifyChange();
    }

    /**
     * Changes the stored uid of the given message (using it's internal id as a key) to
     * the uid in the message.
     * @throws com.fsck.k9.mail.MessagingException
     */
    public void changeUid(final LocalMessage message) throws MessagingException {
        open(OPEN_MODE_RW);
        final ContentValues cv = new ContentValues();
        cv.put("uid", message.getUid());
        this.localStore.database.execute(false, new DbCallback<Void>() {
            @Override
            public Void doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException {
                db.update("messages", cv, "id = ?", new String[]
                        { Long.toString(message.getId()) });
                return null;
            }
        });

        //TODO: remove this once the UI code exclusively uses the database id
        this.localStore.notifyChange();
    }

    @Override
    public void setFlags(final List<? extends Message> messages, final Set<Flag> flags, final boolean value)
            throws MessagingException {
        open(OPEN_MODE_RW);

        // Use one transaction to set all flags
        try {
            this.localStore.database.execute(true, new DbCallback<Void>() {
                @Override
                public Void doDbWork(final SQLiteDatabase db) throws WrappedException,
                        UnavailableStorageException {

                    for (Message message : messages) {
                        try {
                            message.setFlags(flags, value);
                        } catch (MessagingException e) {
                            Timber.e(e, "Something went wrong while setting flag");
                        }
                    }

                    return null;
                }
            });
        } catch (WrappedException e) {
            throw(MessagingException) e.getCause();
        }
    }

    @Override
    public void setFlags(final Set<Flag> flags, boolean value)
            throws MessagingException {
        open(OPEN_MODE_RW);
        for (Message message : getMessages(null)) {
            message.setFlags(flags, value);
        }
    }

    @Override
    public String getUidFromMessageId(Message message) throws MessagingException {
        throw new MessagingException("Cannot call getUidFromMessageId on LocalFolder");
    }

    public void clearMessagesOlderThan(long cutoff) throws MessagingException {
        open(OPEN_MODE_RO);

        List<? extends Message> messages  = this.localStore.getMessages(null, this,
                "SELECT " + LocalStore.GET_MESSAGES_COLS +
                        "FROM messages " +
                        "LEFT JOIN message_parts ON (message_parts.id = messages.message_part_id) " +
                        "LEFT JOIN threads ON (threads.message_id = messages.id) " +
                        "WHERE empty = 0 AND (folder_id = ? and date < ?)",
                new String[] { Long.toString(mFolderId), Long.toString(cutoff) });

        for (Message message : messages) {
            message.destroy();
        }

        this.localStore.notifyChange();
    }

    public void clearAllMessages() throws MessagingException {
        final String[] folderIdArg = new String[] { Long.toString(mFolderId) };

        open(OPEN_MODE_RO);

        try {
            this.localStore.database.execute(false, new DbCallback<Void>() {
                @Override
                public Void doDbWork(final SQLiteDatabase db) throws WrappedException {
                    try {
                        Cursor cursor = db.query("messages", new String[] { "message_part_id" },
                                "folder_id = ? AND empty = 0",
                                folderIdArg, null, null, null);
                        try {
                            while (cursor.moveToNext()) {
                                long messagePartId = cursor.getLong(0);
                                deleteMessageDataFromDisk(messagePartId);
                            }
                        } finally {
                            cursor.close();
                        }

                        db.execSQL("DELETE FROM threads WHERE message_id IN " +
                                "(SELECT id FROM messages WHERE folder_id = ?)", folderIdArg);
                        db.execSQL("DELETE FROM messages WHERE folder_id = ?", folderIdArg);

                        setMoreMessages(MoreMessages.UNKNOWN);

                        return null;
                    } catch (MessagingException e) {
                        throw new WrappedException(e);
                    }
                }
            });
        } catch (WrappedException e) {
            throw(MessagingException) e.getCause();
        }

        this.localStore.notifyChange();

        setPushState(null);
        setLastPush(0);
        setLastChecked(0);
        setVisibleLimit(getAccount().getDisplayCount());
    }

    @Override
    public void delete(final boolean recurse) throws MessagingException {
        try {
            this.localStore.database.execute(false, new DbCallback<Void>() {
                @Override
                public Void doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException {
                    try {
                        // We need to open the folder first to make sure we've got it's id
                        open(OPEN_MODE_RO);
                        List<LocalMessage> messages = getMessages(null);
                        for (LocalMessage message : messages) {
                            deleteMessageDataFromDisk(message.getMessagePartId());
                        }
                    } catch (MessagingException e) {
                        throw new WrappedException(e);
                    }
                    db.execSQL("DELETE FROM folders WHERE id = ?", new Object[]
                            { Long.toString(mFolderId), });
                    return null;
                }
            });
        } catch (WrappedException e) {
            throw(MessagingException) e.getCause();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof LocalFolder) {
            return ((LocalFolder)o).mName.equals(mName);
        }
        return super.equals(o);
    }

    @Override
    public int hashCode() {
        return mName.hashCode();
    }

    void deleteMessagePartsAndDataFromDisk(final long rootMessagePartId) throws MessagingException {
        deleteMessageDataFromDisk(rootMessagePartId);
        deleteMessageParts(rootMessagePartId);
    }

    private void deleteMessageParts(final long rootMessagePartId) throws MessagingException {
        localStore.database.execute(false, new DbCallback<Void>() {
            @Override
            public Void doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException {
                db.delete("message_parts", "root = ?", new String[] { Long.toString(rootMessagePartId) });
                return null;
            }
        });
    }

    private void deleteMessageDataFromDisk(final long rootMessagePartId) throws MessagingException {
        localStore.database.execute(false, new DbCallback<Void>() {
            @Override
            public Void doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException {
                deleteMessagePartsFromDisk(db, rootMessagePartId);
                return null;
            }
        });
    }

    private void deleteMessagePartsFromDisk(SQLiteDatabase db, long rootMessagePartId) {
        Cursor cursor = db.query("message_parts", new String[] { "id" },
                "root = ? AND data_location = " + DataLocation.ON_DISK,
                new String[] { Long.toString(rootMessagePartId) }, null, null, null);
        try {
            while (cursor.moveToNext()) {
                String messagePartId = cursor.getString(0);
                File file = localStore.getAttachmentFile(messagePartId);
                if (file.exists()) {
                    if (!file.delete() && K9.isDebug()) {
                        Timber.d("Couldn't delete message part file: %s", file.getAbsolutePath());
                    }
                }
            }
        } finally {
            cursor.close();
        }
    }

    @Override
    public boolean isInTopGroup() {
        return mInTopGroup;
    }

    public void setInTopGroup(boolean inTopGroup) throws MessagingException {
        mInTopGroup = inTopGroup;
        updateFolderColumn("top_group", mInTopGroup ? 1 : 0);
    }

    public Integer getLastUid() {
        return mLastUid;
    }

    /**
     * <p>Fetches the most recent <b>numeric</b> UID value in this folder.  This is used by
     * {@link com.fsck.k9.controller.MessagingController#shouldNotifyForMessage} to see if messages being
     * fetched are new and unread.  Messages are "new" if they have a UID higher than the most recent UID prior
     * to synchronization.</p>
     *
     * <p>This only works for protocols with numeric UIDs (like IMAP). For protocols with
     * alphanumeric UIDs (like POP), this method quietly fails and shouldNotifyForMessage() will
     * always notify for unread messages.</p>
     *
     * <p>Once Issue 1072 has been fixed, this method and shouldNotifyForMessage() should be
     * updated to use internal dates rather than UIDs to determine new-ness. While this doesn't
     * solve things for POP (which doesn't have internal dates), we can likely use this as a
     * framework to examine send date in lieu of internal date.</p>
     * @throws MessagingException
     */
    public void updateLastUid() throws MessagingException {
        Integer lastUid = this.localStore.database.execute(false, new DbCallback<Integer>() {
            @Override
            public Integer doDbWork(final SQLiteDatabase db) {
                Cursor cursor = null;
                try {
                    open(OPEN_MODE_RO);
                    cursor = db.rawQuery("SELECT MAX(uid) FROM messages WHERE folder_id=?", new String[] { Long.toString(mFolderId) });
                    if (cursor.getCount() > 0) {
                        cursor.moveToFirst();
                        return cursor.getInt(0);
                    }
                } catch (Exception e) {
                    Timber.e(e, "Unable to updateLastUid: ");
                } finally {
                    Utility.closeQuietly(cursor);
                }
                return null;
            }
        });

        Timber.d("Updated last UID for folder %s to %s", mName, lastUid);
        mLastUid = lastUid;
    }

    public Long getOldestMessageDate() throws MessagingException {
        return this.localStore.database.execute(false, new DbCallback<Long>() {
            @Override
            public Long doDbWork(final SQLiteDatabase db) {
                Cursor cursor = null;
                try {
                    open(OPEN_MODE_RO);
                    cursor = db.rawQuery("SELECT MIN(date) FROM messages WHERE folder_id=?", new String[] { Long.toString(mFolderId) });
                    if (cursor.getCount() > 0) {
                        cursor.moveToFirst();
                        return cursor.getLong(0);
                    }
                } catch (Exception e) {
                    Timber.e(e, "Unable to fetch oldest message date: ");
                } finally {
                    Utility.closeQuietly(cursor);
                }
                return null;
            }
        });
    }

    private ThreadInfo doMessageThreading(SQLiteDatabase db, Message message)
            throws MessagingException {
        long rootId = -1;
        long parentId = -1;

        String messageId = message.getMessageId();

        // If there's already an empty message in the database, update that
        ThreadInfo msgThreadInfo = getThreadInfo(db, messageId, true);

        // Get the message IDs from the "References" header line
        String[] referencesArray = message.getHeader("References");
        List<String> messageIds = null;
        if (referencesArray.length > 0) {
            messageIds = Utility.extractMessageIds(referencesArray[0]);
        }

        // Append the first message ID from the "In-Reply-To" header line
        String[] inReplyToArray = message.getHeader("In-Reply-To");
        String inReplyTo;
        if (inReplyToArray.length > 0) {
            inReplyTo = Utility.extractMessageId(inReplyToArray[0]);
            if (inReplyTo != null) {
                if (messageIds == null) {
                    messageIds = new ArrayList<>(1);
                    messageIds.add(inReplyTo);
                } else if (!messageIds.contains(inReplyTo)) {
                    messageIds.add(inReplyTo);
                }
            }
        }

        if (messageIds == null) {
            // This is not a reply, nothing to do for us.
            return (msgThreadInfo != null) ?
                    msgThreadInfo : new ThreadInfo(-1, -1, messageId, -1, -1);
        }

        for (String reference : messageIds) {
            ThreadInfo threadInfo = getThreadInfo(db, reference, false);

            if (threadInfo == null) {
                // Create placeholder message in 'messages' table
                ContentValues cv = new ContentValues();
                cv.put("message_id", reference);
                cv.put("folder_id", mFolderId);
                cv.put("empty", 1);

                long newMsgId = db.insert("messages", null, cv);

                // Create entry in 'threads' table
                cv.clear();
                cv.put("message_id", newMsgId);
                if (rootId != -1) {
                    cv.put("root", rootId);
                }
                if (parentId != -1) {
                    cv.put("parent", parentId);
                }

                parentId = db.insert("threads", null, cv);
                if (rootId == -1) {
                    rootId = parentId;
                }
            } else {
                if (rootId != -1 && threadInfo.rootId == -1 && rootId != threadInfo.threadId) {
                    // We found an existing root container that is not
                    // the root of our current path (References).
                    // Connect it to the current parent.

                    // Let all children know who's the new root
                    ContentValues cv = new ContentValues();
                    cv.put("root", rootId);
                    db.update("threads", cv, "root = ?",
                            new String[] { Long.toString(threadInfo.threadId) });

                    // Connect the message to the current parent
                    cv.put("parent", parentId);
                    db.update("threads", cv, "id = ?",
                            new String[] { Long.toString(threadInfo.threadId) });
                } else {
                    rootId = (threadInfo.rootId == -1) ?
                            threadInfo.threadId : threadInfo.rootId;
                }
                parentId = threadInfo.threadId;
            }
        }

        //TODO: set in-reply-to "link" even if one already exists

        long threadId;
        long msgId;
        if (msgThreadInfo != null) {
            threadId = msgThreadInfo.threadId;
            msgId = msgThreadInfo.msgId;
        } else {
            threadId = -1;
            msgId = -1;
        }

        return new ThreadInfo(threadId, msgId, messageId, rootId, parentId);
    }

    public List<Message> extractNewMessages(final List<Message> messages)
            throws MessagingException {

        try {
            return this.localStore.database.execute(false, new DbCallback<List<Message>>() {
                @Override
                public List<Message> doDbWork(final SQLiteDatabase db) throws WrappedException {
                    try {
                        open(OPEN_MODE_RW);
                    } catch (MessagingException e) {
                        throw new WrappedException(e);
                    }

                    List<Message> result = new ArrayList<>();

                    List<String> selectionArgs = new ArrayList<>();
                    Set<String> existingMessages = new HashSet<>();
                    int start = 0;

                    while (start < messages.size()) {
                        StringBuilder selection = new StringBuilder();

                        selection.append("folder_id = ? AND UID IN (");
                        selectionArgs.add(Long.toString(mFolderId));

                        int count = Math.min(messages.size() - start, LocalStore.UID_CHECK_BATCH_SIZE);

                        for (int i = start, end = start + count; i < end; i++) {
                            if (i > start) {
                                selection.append(",?");
                            } else {
                                selection.append("?");
                            }

                            selectionArgs.add(messages.get(i).getUid());
                        }

                        selection.append(")");

                        Cursor cursor = db.query("messages", LocalStore.UID_CHECK_PROJECTION,
                                selection.toString(), selectionArgs.toArray(LocalStore.EMPTY_STRING_ARRAY),
                                null, null, null);

                        try {
                            while (cursor.moveToNext()) {
                                String uid = cursor.getString(0);
                                existingMessages.add(uid);
                            }
                        } finally {
                            Utility.closeQuietly(cursor);
                        }

                        for (int i = start, end = start + count; i < end; i++) {
                            Message message = messages.get(i);
                            if (!existingMessages.contains(message.getUid())) {
                                result.add(message);
                            }
                        }

                        existingMessages.clear();
                        selectionArgs.clear();
                        start += count;
                    }

                    return result;
                }
            });
        } catch (WrappedException e) {
            throw(MessagingException) e.getCause();
        }
    }

    private Account getAccount() {
        return localStore.getAccount();
    }

    // Note: The contents of the 'message_parts' table depend on these values.
    // TODO currently unused, might be for caching at a later point
    static class MessagePartType {
        static final int UNKNOWN = 0;
        static final int ALTERNATIVE_PLAIN = 1;
        static final int ALTERNATIVE_HTML = 2;
        static final int TEXT = 3;
        static final int RELATED = 4;
        static final int ATTACHMENT = 5;
        static final int HIDDEN_ATTACHMENT = 6;
    }

    // Note: The contents of the 'message_parts' table depend on these values.
    static class DataLocation {
        static final int MISSING = 0;
        static final int IN_DATABASE = 1;
        static final int ON_DISK = 2;
        static final int CHILD_PART_CONTAINS_DATA = 3;
    }

    public enum MoreMessages {
        UNKNOWN("unknown"),
        FALSE("false"),
        TRUE("true");

        private final String databaseName;

        MoreMessages(String databaseName) {
            this.databaseName = databaseName;
        }

        public static MoreMessages fromDatabaseName(String databaseName) {
            for (MoreMessages value : MoreMessages.values()) {
                if (value.databaseName.equals(databaseName)) {
                    return value;
                }
            }

            throw new IllegalArgumentException("Unknown value: " + databaseName);
        }

        public String getDatabaseName() {
            return databaseName;
        }
    }
}
