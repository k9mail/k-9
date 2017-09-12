package com.fsck.k9.mailstore;


import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Date;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.support.annotation.VisibleForTesting;

import com.fsck.k9.Account;
import com.fsck.k9.BuildConfig;
import com.fsck.k9.activity.MessageReference;
import com.fsck.k9.mail.Address;
import com.fsck.k9.mail.Flag;
import com.fsck.k9.mail.Folder;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.internet.MimeMessage;
import com.fsck.k9.mail.message.MessageHeaderParser;
import com.fsck.k9.mailstore.LockableDatabase.DbCallback;
import com.fsck.k9.mailstore.LockableDatabase.WrappedException;
import com.fsck.k9.message.extractors.PreviewResult.PreviewType;
import timber.log.Timber;


public class LocalMessage extends MimeMessage {
    protected MessageReference mReference;
    private final LocalStore localStore;

    private long mId;
    private int mAttachmentCount;
    private String mSubject;

    private String mPreview = "";

    private long mThreadId;
    private long mRootId;
    private long messagePartId;
    private String mimeType;
    private PreviewType previewType;
    private boolean headerNeedsUpdating = false;


    private LocalMessage(LocalStore localStore) {
        this.localStore = localStore;
    }

    LocalMessage(LocalStore localStore, String uid, Folder folder) {
        this.localStore = localStore;
        this.mUid = uid;
        this.mFolder = folder;
    }


    void populateFromGetMessageCursor(Cursor cursor) throws MessagingException {
        final String subject = cursor.getString(0);
        this.setSubject(subject == null ? "" : subject);

        Address[] from = Address.unpack(cursor.getString(1));
        if (from.length > 0) {
            this.setFrom(from[0]);
        }
        this.setInternalSentDate(new Date(cursor.getLong(2)));
        populateUidAndFlags(cursor);
        this.mId = cursor.getLong(5);
        this.setRecipients(RecipientType.TO, Address.unpack(cursor.getString(6)));
        this.setRecipients(RecipientType.CC, Address.unpack(cursor.getString(7)));
        this.setRecipients(RecipientType.BCC, Address.unpack(cursor.getString(8)));
        this.setReplyTo(Address.unpack(cursor.getString(9)));

        this.mAttachmentCount = cursor.getInt(10);
        this.setInternalDate(new Date(cursor.getLong(11)));
        this.setMessageId(cursor.getString(12));

        String previewTypeString = cursor.getString(24);
        DatabasePreviewType databasePreviewType = DatabasePreviewType.fromDatabaseValue(previewTypeString);
        previewType = databasePreviewType.getPreviewType();
        if (previewType == PreviewType.TEXT) {
            mPreview = cursor.getString(14);
        } else {
            mPreview = "";
        }

        if (this.mFolder == null) {
            LocalFolder f = new LocalFolder(this.localStore, cursor.getInt(13));
            f.open(LocalFolder.OPEN_MODE_RW);
            this.mFolder = f;
        }

        mThreadId = (cursor.isNull(15)) ? -1 : cursor.getLong(15);
        mRootId = (cursor.isNull(16)) ? -1 : cursor.getLong(16);

        setMessagePartId(cursor.getLong(22));
        mimeType = cursor.getString(23);

        byte[] header = cursor.getBlob(25);
        if (header != null) {
            MessageHeaderParser.parse(this, new ByteArrayInputStream(header));
        } else {
            Timber.d("No headers available for this message!");
        }
        
        headerNeedsUpdating = false;
    }

    void populateUidAndFlags(Cursor cursor) throws MessagingException {
        setUid(cursor.getString(cursor.getColumnIndex("uid")));
        String flagList = cursor.getString(cursor.getColumnIndex("flags"));
        if (flagList != null && flagList.length() > 0) {
            String[] flags = flagList.split(",");

            for (String flag : flags) {
                if ("X_BAD_FLAG".equals(flag)) {
                    continue;
                }

                try {
                    setFlagInternal(Flag.valueOf(flag), true);
                }

                catch (Exception e) {
                    Timber.w("Unable to parse flag %s", flag);
                }
            }
        }

        boolean deleted = (cursor.getInt(cursor.getColumnIndex("deleted")) == 1);
        boolean read = (cursor.getInt(cursor.getColumnIndex("read")) == 1);
        boolean flagged = (cursor.getInt(cursor.getColumnIndex("flagged")) == 1);
        boolean answered = (cursor.getInt(cursor.getColumnIndex("answered")) == 1);
        boolean forwarded = (cursor.getInt(cursor.getColumnIndex("forwarded")) == 1);

        setFlagInternal(Flag.DELETED, deleted);
        setFlagInternal(Flag.SEEN, read);
        setFlagInternal(Flag.FLAGGED, flagged);
        setFlagInternal(Flag.ANSWERED, answered);
        setFlagInternal(Flag.FORWARDED, forwarded);
    }

    @VisibleForTesting
    void setMessagePartId(long messagePartId) {
        this.messagePartId = messagePartId;
    }

    public long getMessagePartId() {
        return messagePartId;
    }

    @Override
    public String getMimeType() {
        return mimeType;
    }

    /* Custom version of writeTo that updates the MIME message based on localMessage
     * changes.
     */

    public PreviewType getPreviewType() {
        return previewType;
    }

    public String getPreview() {
        return mPreview;
    }

    @Override
    public String getSubject() {
        return mSubject;
    }


    @Override
    public void setSubject(String subject) {
        mSubject = subject;
        headerNeedsUpdating = true;
    }


    @Override
    public void setMessageId(String messageId) {
        mMessageId = messageId;
        headerNeedsUpdating = true;
    }

    @Override
    public void setUid(String uid) {
        super.setUid(uid);
        this.mReference = null;
    }

    @Override
    public boolean hasAttachments() {
        return (mAttachmentCount > 0);
    }

    public int getAttachmentCount() {
        return mAttachmentCount;
    }

    @Override
    public void setFrom(Address from) {
        this.mFrom = new Address[] { from };
        headerNeedsUpdating = true;
    }


    @Override
    public void setReplyTo(Address[] replyTo) {
        if (replyTo == null || replyTo.length == 0) {
            mReplyTo = null;
        } else {
            mReplyTo = replyTo;
        }

        headerNeedsUpdating = true;
    }


    /*
     * For performance reasons, we add headers instead of setting them (see super implementation)
     * which removes (expensive) them before adding them
     */
    @Override
    public void setRecipients(RecipientType type, Address[] addresses) {
        if (type == RecipientType.TO) {
            if (addresses == null || addresses.length == 0) {
                this.mTo = null;
            } else {
                this.mTo = addresses;
            }
        } else if (type == RecipientType.CC) {
            if (addresses == null || addresses.length == 0) {
                this.mCc = null;
            } else {
                this.mCc = addresses;
            }
        } else if (type == RecipientType.BCC) {
            if (addresses == null || addresses.length == 0) {
                this.mBcc = null;
            } else {
                this.mBcc = addresses;
            }
        } else {
            throw new IllegalArgumentException("Unrecognized recipient type.");
        }

        headerNeedsUpdating = true;
    }

    public void setFlagInternal(Flag flag, boolean set) throws MessagingException {
        super.setFlag(flag, set);
    }

    @Override
    public long getId() {
        return mId;
    }

    @Override
    public void setFlag(final Flag flag, final boolean set) throws MessagingException {

        try {
            this.localStore.database.execute(true, new DbCallback<Void>() {
                @Override
                public Void doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException {
                    try {
                        if (flag == Flag.DELETED && set) {
                            delete();
                        }

                        LocalMessage.super.setFlag(flag, set);
                    } catch (MessagingException e) {
                        throw new WrappedException(e);
                    }
                    /*
                     * Set the flags on the message.
                     */
                    ContentValues cv = new ContentValues();
                    cv.put("flags", LocalMessage.this.localStore.serializeFlags(getFlags()));
                    cv.put("read", isSet(Flag.SEEN) ? 1 : 0);
                    cv.put("flagged", isSet(Flag.FLAGGED) ? 1 : 0);
                    cv.put("answered", isSet(Flag.ANSWERED) ? 1 : 0);
                    cv.put("forwarded", isSet(Flag.FORWARDED) ? 1 : 0);

                    db.update("messages", cv, "id = ?", new String[] { Long.toString(mId) });

                    return null;
                }
            });
        } catch (WrappedException e) {
            throw(MessagingException) e.getCause();
        }

        this.localStore.notifyChange();
    }

    /*
     * If a message is being marked as deleted we want to clear out its content. Delete will not actually remove the
     * row since we need to retain the UID for synchronization purposes.
     */
    private void delete() throws MessagingException {
        try {
            localStore.database.execute(true, new DbCallback<Void>() {
                @Override
                public Void doDbWork(final SQLiteDatabase db) throws WrappedException, UnavailableStorageException {
                    ContentValues cv = new ContentValues();
                    cv.put("deleted", 1);
                    cv.put("empty", 1);
                    cv.putNull("subject");
                    cv.putNull("sender_list");
                    cv.putNull("date");
                    cv.putNull("to_list");
                    cv.putNull("cc_list");
                    cv.putNull("bcc_list");
                    cv.putNull("preview");
                    cv.putNull("reply_to_list");
                    cv.putNull("message_part_id");

                    db.update("messages", cv, "id = ?", new String[] { Long.toString(mId) });

                    try {
                        ((LocalFolder) mFolder).deleteMessagePartsAndDataFromDisk(messagePartId);
                    } catch (MessagingException e) {
                        throw new WrappedException(e);
                    }

                    deleteFulltextIndexEntry(db, mId);

                    return null;
                }
            });
        } catch (WrappedException e) {
            throw (MessagingException) e.getCause();
        }

        localStore.notifyChange();
    }

    public void debugClearLocalData() throws MessagingException {
        if (!BuildConfig.DEBUG) {
            throw new AssertionError("method must only be used in debug build!");
        }

        try {
            localStore.database.execute(true, new DbCallback<Void>() {
                @Override
                public Void doDbWork(final SQLiteDatabase db) throws WrappedException, MessagingException {
                    ContentValues cv = new ContentValues();
                    cv.putNull("message_part_id");

                    db.update("messages", cv, "id = ?", new String[] { Long.toString(mId) });

                    try {
                        ((LocalFolder) mFolder).deleteMessagePartsAndDataFromDisk(messagePartId);
                    } catch (MessagingException e) {
                        throw new WrappedException(e);
                    }

                    setFlag(Flag.X_DOWNLOADED_FULL, false);
                    setFlag(Flag.X_DOWNLOADED_PARTIAL, false);

                    return null;
                }
            });
        } catch (WrappedException e) {
            throw (MessagingException) e.getCause();
        }

        localStore.notifyChange();
    }

    /*
     * Completely remove a message from the local database
     *
     * TODO: document how this updates the thread structure
     */
    @Override
    public void destroy() throws MessagingException {
        try {
            this.localStore.database.execute(true, new DbCallback<Void>() {
                @Override
                public Void doDbWork(final SQLiteDatabase db) throws WrappedException,
                    UnavailableStorageException {
                    try {
                        LocalFolder localFolder = (LocalFolder) mFolder;

                        localFolder.deleteMessagePartsAndDataFromDisk(messagePartId);

                        deleteFulltextIndexEntry(db, mId);

                        if (hasThreadChildren(db, mId)) {
                            // This message has children in the thread structure so we need to
                            // make it an empty message.
                            ContentValues cv = new ContentValues();
                            cv.put("id", mId);
                            cv.put("folder_id", localFolder.getId());
                            cv.put("deleted", 0);
                            cv.put("message_id", getMessageId());
                            cv.put("empty", 1);

                            db.replace("messages", null, cv);

                            // Nothing else to do
                            return null;
                        }

                        // Get the message ID of the parent message if it's empty
                        long currentId = getEmptyThreadParent(db, mId);

                        // Delete the placeholder message
                        deleteMessageRow(db, mId);

                        /*
                         * Walk the thread tree to delete all empty parents without children
                         */

                        while (currentId != -1) {
                            if (hasThreadChildren(db, currentId)) {
                                // We made sure there are no empty leaf nodes and can stop now.
                                break;
                            }

                            // Get ID of the (empty) parent for the next iteration
                            long newId = getEmptyThreadParent(db, currentId);

                            // Delete the empty message
                            deleteMessageRow(db, currentId);

                            currentId = newId;
                        }

                    } catch (MessagingException e) {
                        throw new WrappedException(e);
                    }
                    return null;
                }
            });
        } catch (WrappedException e) {
            throw(MessagingException) e.getCause();
        }

        this.localStore.notifyChange();
    }

    /**
     * Get ID of the the given message's parent if the parent is an empty message.
     *
     * @param db
     *         {@link SQLiteDatabase} instance to access the database.
     * @param messageId
     *         The database ID of the message to get the parent for.
     *
     * @return Message ID of the parent message if there exists a parent and it is empty.
     *         Otherwise {@code -1}.
     */
    private long getEmptyThreadParent(SQLiteDatabase db, long messageId) {
        Cursor cursor = db.rawQuery(
                "SELECT m.id " +
                "FROM threads t1 " +
                "JOIN threads t2 ON (t1.parent = t2.id) " +
                "LEFT JOIN messages m ON (t2.message_id = m.id) " +
                "WHERE t1.message_id = ? AND m.empty = 1",
                new String[] { Long.toString(messageId) });

        try {
            return (cursor.moveToFirst() && !cursor.isNull(0)) ? cursor.getLong(0) : -1;
        } finally {
            cursor.close();
        }
    }

    /**
     * Check whether or not a message has child messages in the thread structure.
     *
     * @param db
     *         {@link SQLiteDatabase} instance to access the database.
     * @param messageId
     *         The database ID of the message to get the children for.
     *
     * @return {@code true} if the message has children. {@code false} otherwise.
     */
    private boolean hasThreadChildren(SQLiteDatabase db, long messageId) {
        Cursor cursor = db.rawQuery(
                "SELECT COUNT(t2.id) " +
                "FROM threads t1 " +
                "JOIN threads t2 ON (t2.parent = t1.id) " +
                "WHERE t1.message_id = ?",
                new String[] { Long.toString(messageId) });

        try {
            return (cursor.moveToFirst() && !cursor.isNull(0) && cursor.getLong(0) > 0L);
        } finally {
            cursor.close();
        }
    }

    private void deleteFulltextIndexEntry(SQLiteDatabase db, long messageId) {
        String[] idArg = { Long.toString(messageId) };
        db.delete("messages_fulltext", "docid = ?", idArg);
    }

    /**
     * Delete a message from the 'messages' and 'threads' tables.
     *
     * @param db
     *         {@link SQLiteDatabase} instance to access the database.
     * @param messageId
     *         The database ID of the message to delete.
     */
    private void deleteMessageRow(SQLiteDatabase db, long messageId) {
        String[] idArg = { Long.toString(messageId) };

        // Delete the message
        db.delete("messages", "id = ?", idArg);

        // Delete row in 'threads' table
        // TODO: create trigger for 'messages' table to get rid of the row in 'threads' table
        db.delete("threads", "message_id = ?", idArg);
    }

    @Override
    public LocalMessage clone() {
        LocalMessage message = new LocalMessage(localStore);
        super.copy(message);

        message.mReference = mReference;
        message.mId = mId;
        message.mAttachmentCount = mAttachmentCount;
        message.mSubject = mSubject;
        message.mPreview = mPreview;
        message.mThreadId = mThreadId;
        message.mRootId = mRootId;
        message.messagePartId = messagePartId;
        message.mimeType = mimeType;
        message.previewType = previewType;
        message.headerNeedsUpdating = headerNeedsUpdating;

        return message;
    }

    public long getThreadId() {
        return mThreadId;
    }

    public long getRootId() {
        return mRootId;
    }

    public Account getAccount() {
        return localStore.getAccount();
    }

    public MessageReference makeMessageReference() {
        if (mReference == null) {
            mReference = new MessageReference(getFolder().getAccountUuid(), getFolder().getName(), mUid, null);
        }
        return mReference;
    }

    @Override
    public LocalFolder getFolder() {
        return (LocalFolder) super.getFolder();
    }

    public String getUri() {
        return "email://messages/" +  getAccount().getAccountNumber() + "/" + getFolder().getName() + "/" + getUid();
    }

    @Override
    public void writeTo(OutputStream out) throws IOException, MessagingException {
        if (headerNeedsUpdating) {
            updateHeader();
        }
        
        super.writeTo(out);
    }

    private void updateHeader() {
        super.setSubject(mSubject);
        super.setReplyTo(mReplyTo);
        super.setRecipients(RecipientType.TO, mTo);
        super.setRecipients(RecipientType.CC, mCc);
        super.setRecipients(RecipientType.BCC, mBcc);

        if (mFrom != null && mFrom.length > 0) {
            super.setFrom(mFrom[0]);
        }

        if (mMessageId != null) {
            super.setMessageId(mMessageId);
        }
        
        headerNeedsUpdating = false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }

        // distinguish by account uuid, in addition to what Message.equals() does above
        String thisAccountUuid = getAccountUuid();
        String thatAccountUuid = ((LocalMessage) o).getAccountUuid();
        return thisAccountUuid != null ? thisAccountUuid.equals(thatAccountUuid) : thatAccountUuid == null;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + (getAccountUuid() != null ? getAccountUuid().hashCode() : 0);
        return result;
    }

    private String getAccountUuid() {
        return getAccount().getUuid();
    }

    public boolean isBodyMissing() {
        return getBody() == null;
    }
}
