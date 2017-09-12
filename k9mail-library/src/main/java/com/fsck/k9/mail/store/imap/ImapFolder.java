package com.fsck.k9.mail.store.imap;


import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import android.text.TextUtils;

import com.fsck.k9.mail.Body;
import com.fsck.k9.mail.BodyFactory;
import com.fsck.k9.mail.FetchProfile;
import com.fsck.k9.mail.Flag;
import com.fsck.k9.mail.Folder;
import com.fsck.k9.mail.K9MailLib;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.MessageRetrievalListener;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.Part;
import com.fsck.k9.mail.filter.EOLConvertingOutputStream;
import com.fsck.k9.mail.internet.MimeBodyPart;
import com.fsck.k9.mail.internet.MimeHeader;
import com.fsck.k9.mail.internet.MimeMessageHelper;
import com.fsck.k9.mail.internet.MimeMultipart;
import com.fsck.k9.mail.internet.MimeUtility;
import com.fsck.k9.mail.store.imap.selectedstate.command.UidCopyCommand;
import com.fsck.k9.mail.store.imap.selectedstate.command.UidFetchCommand;
import com.fsck.k9.mail.store.imap.selectedstate.command.UidSearchCommand;
import com.fsck.k9.mail.store.imap.selectedstate.command.UidStoreCommand;
import com.fsck.k9.mail.store.imap.selectedstate.response.UidCopyResponse;
import com.fsck.k9.mail.store.imap.selectedstate.response.UidSearchResponse;
import timber.log.Timber;


public class ImapFolder extends Folder<ImapMessage> {
    private static final ThreadLocal<SimpleDateFormat> RFC3501_DATE = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("dd-MMM-yyyy", Locale.US);
        }
    };
    private static final int MORE_MESSAGES_WINDOW_SIZE = 500;
    private static final int FETCH_WINDOW_SIZE = 100;
    public static final int INVALID_UID_VALIDITY = -1;
    public static final int INVALID_HIGHEST_MOD_SEQ = -1;


    protected volatile int messageCount = -1;
    protected volatile long uidNext = -1L;
    private long uidValidity = INVALID_UID_VALIDITY;
    private long highestModSeq = INVALID_HIGHEST_MOD_SEQ;
    protected volatile ImapConnection connection;
    protected ImapStore store = null;
    protected Map<Long, String> msgSeqUidMap = new ConcurrentHashMap<Long, String>();
    private final FolderNameCodec folderNameCodec;
    private final String name;
    private int mode;
    private volatile boolean exists;
    private boolean inSearch = false;
    private boolean canCreateKeywords = false;


    public ImapFolder(ImapStore store, String name) {
        this(store, name, store.getFolderNameCodec());
    }

    ImapFolder(ImapStore store, String name, FolderNameCodec folderNameCodec) {
        super();
        this.store = store;
        this.name = name;
        this.folderNameCodec = folderNameCodec;
    }

    private String getPrefixedName() throws MessagingException {
        String prefixedName = "";

        if (!store.getStoreConfig().getInboxFolderName().equalsIgnoreCase(name)) {
            ImapConnection connection;
            synchronized (this) {
                if (this.connection == null) {
                    connection = store.getConnection();
                } else {
                    connection = this.connection;
                }
            }

            try {
                connection.open();
            } catch (IOException ioe) {
                throw new MessagingException("Unable to get IMAP prefix", ioe);
            } finally {
                if (this.connection == null) {
                    store.releaseConnection(connection);
                }
            }

            prefixedName = store.getCombinedPrefix();
        }

        prefixedName += name;

        return prefixedName;
    }

    public List<ImapResponse> executeSimpleCommand(String command) throws MessagingException, IOException {
        return handleUntaggedResponses(connection.executeSimpleCommand(command));
    }

    @Override
    public void open(int mode) throws MessagingException {
        internalOpen(mode, INVALID_UID_VALIDITY, INVALID_HIGHEST_MOD_SEQ);

        if (messageCount == -1) {
            throw new MessagingException("Did not find message count during open");
        }
    }

    public QresyncParamResponse openUsingQresyncParam(int mode, long cachedUidValidity, long cachedHighestModSeq)
            throws MessagingException {
        SelectOrExamineResponse response = internalOpen(mode, cachedUidValidity, cachedHighestModSeq);

        if (messageCount == -1) {
            throw new MessagingException("Did not find message count during open");
        }
        return response.getQresyncParamResponse();
    }

    SelectOrExamineResponse internalOpen(int mode, long cachedUidValidity, long cachedHighestModSeq)
            throws MessagingException {
        if (isOpen() && this.mode == mode) {
            // Make sure the connection is valid. If it's not we'll close it down and continue
            // on to get a new one.
            try {
                return SelectOrExamineResponse.newInstance(executeSimpleCommand(Commands.NOOP), this);
            } catch (IOException ioe) {
                /* don't throw */ ioExceptionHandler(connection, ioe);
            }
        }

        store.releaseConnection(connection);

        synchronized (this) {
            connection = store.getConnection();
        }

        try {
            msgSeqUidMap.clear();

            String encodedFolderName = folderNameCodec.encode(getPrefixedName());
            String escapedFolderName = ImapUtility.encodeString(encodedFolderName);
            SelectOrExamineCommand command;
            if (connection.isQresyncCapable() && ImapConfig.shouldUseQresync()) {
                connection.enableQresync();
                command = SelectOrExamineCommand.createWithQresyncParameter(mode, escapedFolderName, cachedUidValidity,
                        cachedHighestModSeq);
            } else if (connection.isCondstoreCapable() && ImapConfig.shouldUseCondstore()) {
                command = SelectOrExamineCommand.createWithCondstoreParameter(mode, escapedFolderName);
            } else {
                command = SelectOrExamineCommand.create(mode, escapedFolderName);
            }

            SelectOrExamineResponse response = SelectOrExamineResponse.newInstance(
                    executeSimpleCommand(command.createCommandString()), this);

            /*
             * If the command succeeds we expect the folder has been opened read-write unless we
             * are notified otherwise in the responses.
             */
            if (response == null) {
                // This shouldn't happen
                return null;
            }
            this.mode = mode;
            if (response.hasOpenMode()) {
                this.mode = response.getOpenMode();
            }
            exists = true;
            uidValidity = response.getUidValidity();
            highestModSeq = response.getHighestModSeq();
            canCreateKeywords = response.canCreateKeywords();
            return response;
        } catch (IOException ioe) {
            throw ioExceptionHandler(connection, ioe);
        } catch (MessagingException me) {
            Timber.e(me, "Unable to open connection for %s", getLogId());
            throw me;
        }
    }

    @Override
    public boolean isOpen() {
        return connection != null;
    }

    @Override
    public int getMode() {
        return mode;
    }

    @Override
    public void close() {
        messageCount = -1;

        if (!isOpen()) {
            return;
        }

        synchronized (this) {
            // If we are mid-search and we get a close request, we gotta trash the connection.
            if (inSearch && connection != null) {
                Timber.i("IMAP search was aborted, shutting down connection.");
                connection.close();
            } else {
                store.releaseConnection(connection);
            }

            connection = null;
        }
    }

    @Override
    public String getName() {
        return name;
    }

    private boolean exists(String escapedFolderName) throws MessagingException {
        try {
            // Since we don't care about RECENT, we'll use that for the check, because we're checking
            // a folder other than ourself, and don't want any untagged responses to cause a change
            // in our own fields
            connection.executeSimpleCommand(String.format("STATUS %s (RECENT)", escapedFolderName));
            return true;
        } catch (IOException ioe) {
            throw ioExceptionHandler(connection, ioe);
        } catch (NegativeImapResponseException e) {
            return false;
        }
    }

    @Override
    public boolean exists() throws MessagingException {
        if (exists) {
            return true;
        }

        /*
         * This method needs to operate in the unselected mode as well as the selected mode
         * so we must get the connection ourselves if it's not there. We are specifically
         * not calling checkOpen() since we don't care if the folder is open.
         */
        ImapConnection connection;
        synchronized (this) {
            if (this.connection == null) {
                connection = store.getConnection();
            } else {
                connection = this.connection;
            }
        }

        try {
            String encodedFolderName = folderNameCodec.encode(getPrefixedName());
            String escapedFolderName = ImapUtility.encodeString(encodedFolderName);
            connection.executeSimpleCommand(String.format("STATUS %s (UIDVALIDITY)", escapedFolderName));

            exists = true;

            return true;
        } catch (NegativeImapResponseException e) {
            return false;
        } catch (IOException ioe) {
            throw ioExceptionHandler(connection, ioe);
        } finally {
            if (this.connection == null) {
                store.releaseConnection(connection);
            }
        }
    }

    @Override
    public boolean create(FolderType type) throws MessagingException {
        /*
         * This method needs to operate in the unselected mode as well as the selected mode
         * so we must get the connection ourselves if it's not there. We are specifically
         * not calling checkOpen() since we don't care if the folder is open.
         */
        ImapConnection connection;
        synchronized (this) {
            if (this.connection == null) {
                connection = store.getConnection();
            } else {
                connection = this.connection;
            }
        }

        try {
            String encodedFolderName = folderNameCodec.encode(getPrefixedName());
            String escapedFolderName = ImapUtility.encodeString(encodedFolderName);
            connection.executeSimpleCommand(String.format("CREATE %s", escapedFolderName));

            return true;
        } catch (NegativeImapResponseException e) {
            return false;
        } catch (IOException ioe) {
            throw ioExceptionHandler(this.connection, ioe);
        } finally {
            if (this.connection == null) {
                store.releaseConnection(connection);
            }
        }
    }

    /**
     * Copies the given messages to the specified folder.
     *
     * <p>
     * <strong>Note:</strong>
     * Only the UIDs of the given {@link Message} instances are used. It is assumed that all
     * UIDs represent valid messages in this folder.
     * </p>
     *
     * @param messages
     *         The messages to copy to the specified folder.
     * @param folder
     *         The name of the target folder.
     *
     * @return The mapping of original message UIDs to the new server UIDs.
     */
    @Override
    public Map<String, String> copyMessages(List<? extends Message> messages, Folder folder) throws MessagingException {
        if (!(folder instanceof ImapFolder)) {
            throw new MessagingException("ImapFolder.copyMessages passed non-ImapFolder");
        }

        if (messages.isEmpty()) {
            return null;
        }

        ImapFolder imapFolder = (ImapFolder) folder;
        checkOpen(); //only need READ access

        Set<Long> uids = new HashSet<>(messages.size());
        for (int i = 0, count = messages.size(); i < count; i++) {
            uids.add(Long.parseLong(messages.get(i).getUid()));
        }

        String encodedDestinationFolderName = folderNameCodec.encode(imapFolder.getPrefixedName());
        String escapedDestinationFolderName = ImapUtility.encodeString(encodedDestinationFolderName);

        //TODO: Try to copy/move the messages first and only create the folder if the
        //      operation fails. This will save a roundtrip if the folder already exists.
        if (!exists(escapedDestinationFolderName)) {
            if (K9MailLib.isDebug()) {
                Timber.i("ImapFolder.copyMessages: attempting to create remote folder '%s' for %s",
                        escapedDestinationFolderName, getLogId());
            }

            imapFolder.create(FolderType.HOLDS_MESSAGES);
        }

        UidCopyCommand command = UidCopyCommand.createWithUids(uids, escapedDestinationFolderName);
        UidCopyResponse response = command.execute(connection, this);

        return response == null ? null : response.getUidMapping();
    }

    @Override
    public Map<String, String> moveMessages(List<? extends Message> messages, Folder folder) throws MessagingException {
        if (messages.isEmpty()) {
            return null;
        }

        Map<String, String> uidMapping = copyMessages(messages, folder);

        setFlags(messages, Collections.singleton(Flag.DELETED), true);

        return uidMapping;
    }

    @Override
    public void delete(List<? extends Message> messages, String trashFolderName) throws MessagingException {
        if (messages.isEmpty()) {
            return;
        }

        if (trashFolderName == null || getName().equalsIgnoreCase(trashFolderName)) {
            setFlags(messages, Collections.singleton(Flag.DELETED), true);
        } else {
            ImapFolder remoteTrashFolder = getStore().getFolder(trashFolderName);
            String encodedTrashFolderName = folderNameCodec.encode(remoteTrashFolder.getPrefixedName());
            String escapedTrashFolderName = ImapUtility.encodeString(encodedTrashFolderName);

            if (!exists(escapedTrashFolderName)) {
                if (K9MailLib.isDebug()) {
                    Timber.i("IMAPMessage.delete: attempting to create remote '%s' folder for %s",
                            trashFolderName, getLogId());
                }
                remoteTrashFolder.create(FolderType.HOLDS_MESSAGES);
            }

            if (exists(escapedTrashFolderName)) {
                if (K9MailLib.isDebug()) {
                    Timber.d("IMAPMessage.delete: copying remote %d messages to '%s' for %s",
                            messages.size(), trashFolderName, getLogId());
                }

                moveMessages(messages, remoteTrashFolder);
            } else {
                throw new MessagingException("IMAPMessage.delete: remote Trash folder " + trashFolderName +
                        " does not exist and could not be created for " + getLogId(), true);
            }
        }
    }

    @Override
    public int getMessageCount() {
        return messageCount;
    }

    public boolean supportsModSeq() throws MessagingException {
        return !(highestModSeq == INVALID_HIGHEST_MOD_SEQ);
    }

    boolean doesConnectionSupportQresync() throws IOException, MessagingException {
        return connection.isQresyncCapable();
    }

    public long getUidValidity() {
        return uidValidity;
    }

    public long getHighestModSeq() {
        return highestModSeq;
    }

    private int getRemoteMessageCount(Set<Flag> requiredFlags, Set<Flag> forbiddenFlags) throws MessagingException {
        checkOpen();

        UidSearchCommand searchCommand = new UidSearchCommand.Builder()
                .allIds(true)
                .requiredFlags(requiredFlags)
                .forbiddenFlags(forbiddenFlags)
                .build();

        UidSearchResponse searchResponse = searchCommand.execute(connection, this);
        return searchResponse.getNumbers().size();
    }

    @Override
    public int getUnreadMessageCount() throws MessagingException {
        Set<Flag> forbiddenFlags = new HashSet<>(2);
        Collections.addAll(forbiddenFlags, Flag.SEEN, Flag.DELETED);
        return getRemoteMessageCount(null, forbiddenFlags);
    }

    @Override
    public int getFlaggedMessageCount() throws MessagingException {
        Set<Flag> requiredFlags = Collections.singleton(Flag.FLAGGED);
        Set<Flag> forbiddenFlags = Collections.singleton(Flag.DELETED);
        return getRemoteMessageCount(requiredFlags, forbiddenFlags);
    }

    protected long getHighestUid() throws MessagingException {
        try {

            UidSearchCommand searchCommand = new UidSearchCommand.Builder()
                    .onlyHighestId(true)
                    .build();

            UidSearchResponse searchResponse = searchCommand.execute(connection, this);

            return extractHighestUid(searchResponse);
        } catch (NegativeImapResponseException e) {
            return -1L;
        }
    }

    private long extractHighestUid(UidSearchResponse searchResponse) {
        List<Long> uids = searchResponse.getNumbers();
        if (uids.isEmpty()) {
            return -1L;
        }

        if (uids.size() == 1) {
            return uids.get(0);
        }

        Collections.sort(uids, Collections.reverseOrder());

        return uids.get(0);
    }

    @Override
    public void delete(boolean recurse) throws MessagingException {
        throw new Error("ImapFolder.delete() not yet implemented");
    }

    @Override
    public ImapMessage getMessage(String uid) throws MessagingException {
        return new ImapMessage(uid, this);
    }

    @Override
    public List<ImapMessage> getMessages(int start, int end, Date earliestDate,
            MessageRetrievalListener<ImapMessage> listener) throws MessagingException {
        return getMessages(start, end, earliestDate, false, listener);
    }

    public void fetchChangedMessageFlagsUsingCondstore(List<ImapMessage> messages, long modseq,
            MessageRetrievalListener<ImapMessage> listener) throws MessagingException {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        checkOpen();

        List<Long> uids = new ArrayList<>(messages.size());
        HashMap<String, Message> messageMap = new HashMap<>();

        for (ImapMessage message : messages) {
            Long uid = Long.parseLong(message.getUid());
            uids.add(uid);
            messageMap.put(String.valueOf(uid), message);
        }

        FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.FLAGS);

        for (int windowStart = 0; windowStart < messageMap.size(); windowStart += (FETCH_WINDOW_SIZE)) {
            int windowEnd = Math.min(windowStart + FETCH_WINDOW_SIZE, messageMap.size());
            Set<Long> uidWindow = new HashSet<>(uids.subList(windowStart, windowEnd));
            int maximumAutoDownloadMessageSize = store.getStoreConfig()
                    .getMaximumAutoDownloadMessageSize();

            try {
                UidFetchCommand command = UidFetchCommand.createWithMessageParams(uidWindow,
                        maximumAutoDownloadMessageSize, messageMap, fp, modseq);
                sendAndProcessUidFetchCommand(command, messageMap, listener);
            } catch (IOException ioe) {
                throw ioExceptionHandler(connection, ioe);
            }
        }
    }

    protected List<ImapMessage> getMessages(final int start, final int end, Date earliestDate,
            final boolean includeDeleted, final MessageRetrievalListener<ImapMessage> listener)
            throws MessagingException {

        if (start < 1 || end < 1 || end < start) {
            throw new MessagingException(String.format(Locale.US, "Invalid message set %d %d", start, end));
        }

        checkOpen();
        UidSearchCommand searchCommand = new UidSearchCommand.Builder()
                .useUids(false)
                .idGroup((long) start, (long) end)
                .since(earliestDate)
                .forbiddenFlags(includeDeleted ? null : Collections.singleton(Flag.DELETED))
                .build();
                
        return getMessages(searchCommand.execute(connection, this), listener);
    }

    @Override
    public boolean areMoreMessagesAvailable(int indexOfOldestMessage, Date earliestDate) throws IOException,
            MessagingException {

        checkOpen();

        if (indexOfOldestMessage == 1) {
            return false;
        }

        int endIndex = indexOfOldestMessage - 1;

        while (endIndex > 0) {
            int startIndex = Math.max(0, endIndex - MORE_MESSAGES_WINDOW_SIZE) + 1;

            if (existsNonDeletedMessageInRange(startIndex, endIndex, earliestDate)) {
                return true;
            }

            endIndex = endIndex - MORE_MESSAGES_WINDOW_SIZE;
        }

        return false;
    }

    private boolean existsNonDeletedMessageInRange(int startIndex, int endIndex, Date earliestDate)
            throws MessagingException, IOException {

        UidSearchCommand searchCommand = new UidSearchCommand.Builder()
                .useUids(false)
                .idGroup((long) startIndex, (long) endIndex)
                .since(earliestDate)
                .forbiddenFlags(Collections.singleton(Flag.DELETED))
                .build();
        UidSearchResponse response = searchCommand.execute(connection, this);
        return response.getNumbers().size() > 0;
    }

    protected List<ImapMessage> getMessages(final Set<Long> mesgSeqs, final boolean includeDeleted,
            final MessageRetrievalListener<ImapMessage> listener) throws MessagingException {

        checkOpen();
        UidSearchCommand searchCommand = new UidSearchCommand.Builder()
                .useUids(false)
                .idSet(mesgSeqs)
                .forbiddenFlags(includeDeleted ? null : Collections.singleton(Flag.DELETED))
                .build();
        return getMessages(searchCommand.execute(connection, this), listener);
    }

    protected List<ImapMessage> getMessagesFromUids(final List<String> mesgUids) throws MessagingException {

        checkOpen();
        Set<Long> uidSet = new HashSet<>();
        for (String uid : mesgUids) {
            uidSet.add(Long.parseLong(uid));
        }

        UidSearchCommand searchCommand = new UidSearchCommand.Builder()
                .useUids(true)
                .idSet(uidSet)
                .build();
        return getMessages(searchCommand.execute(connection, this), null);
    }

    private List<ImapMessage> getMessages(UidSearchResponse searchResponse, MessageRetrievalListener<ImapMessage> listener)
            throws MessagingException {

        checkOpen();

        List<ImapMessage> messages = new ArrayList<>();
        List<Long> uids = searchResponse.getNumbers();

        // Sort the uids in numerically decreasing order
        // By doing it in decreasing order, we ensure newest messages are dealt with first
        // This makes the most sense when a limit is imposed, and also prevents UI from going
        // crazy adding stuff at the top.
        Collections.sort(uids, Collections.reverseOrder());

        for (int i = 0, count = uids.size(); i < count; i++) {
            String uid = uids.get(i).toString();
            if (listener != null) {
                listener.messageStarted(uid, i, count);
            }

            ImapMessage message = new ImapMessage(uid, this);
            messages.add(message);

            if (listener != null) {
                listener.messageFinished(message, i, count);
            }
        }

        return messages;
    }

    @Override
    public void fetch(List<ImapMessage> messages, FetchProfile fetchProfile,
            MessageRetrievalListener<ImapMessage> listener) throws MessagingException {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        checkOpen();

        List<String> uids = new ArrayList<>(messages.size());
        HashMap<String, Message> messageMap = new HashMap<>();
        for (Message message : messages) {
            String uid = message.getUid();
            uids.add(uid);
            messageMap.put(uid, message);
        }

        for (int windowStart = 0; windowStart < messages.size(); windowStart += (FETCH_WINDOW_SIZE)) {
            int windowEnd = Math.min(windowStart + FETCH_WINDOW_SIZE, messages.size());
            Set<Long> uidSet = new HashSet<>(windowEnd - windowStart);
            for (String uid : uids.subList(windowStart, windowEnd)) {
                uidSet.add(Long.parseLong(uid));
            }

            int maximumAutoDownloadMessageSize = store.getStoreConfig()
                    .getMaximumAutoDownloadMessageSize();

            try {
                UidFetchCommand command = UidFetchCommand.createWithMessageParams(uidSet,
                        maximumAutoDownloadMessageSize, messageMap, fetchProfile, null);
                sendAndProcessUidFetchCommand(command, messageMap, listener);
            } catch (IOException ioe) {
                throw ioExceptionHandler(connection, ioe);
            }
        }
    }

    private void sendAndProcessUidFetchCommand(UidFetchCommand command, HashMap<String, Message> messageMap,
            MessageRetrievalListener<ImapMessage> listener) throws MessagingException, IOException {
        ImapResponse response;
        int messageNumber = 0;

        command.send(connection);

        do {
            response = command.readResponse(connection);

            if (response.getTag() == null && ImapResponseParser.equalsIgnoreCase(response.get(1), "FETCH")) {
                ImapList fetchList = (ImapList) response.getKeyedValue("FETCH");
                String uid = fetchList.getKeyedString("UID");
                long msgSeq = response.getLong(0);
                if (uid != null) {
                    msgSeqUidMap.put(msgSeq, uid);
                    if (K9MailLib.isDebug()) {
                        Timber.v("Stored uid '%s' for msgSeq %d into map", uid, msgSeq);
                    }
                }

                Message message = messageMap.get(uid);
                if (message == null) {
                    if (K9MailLib.isDebug()) {
                        Timber.d("Do not have message in messageMap for UID %s for %s", uid, getLogId());
                    }

                    handleUntaggedResponse(response);
                    continue;
                }

                if (listener != null) {
                    listener.messageStarted(uid, messageNumber, messageMap.size());
                }

                ImapMessage imapMessage = (ImapMessage) message;
                Object literal = handleFetchResponse(imapMessage, fetchList);

                if (literal != null) {
                    if (literal instanceof String) {
                        String bodyString = (String) literal;
                        InputStream bodyStream = new ByteArrayInputStream(bodyString.getBytes());
                        imapMessage.parse(bodyStream);
                    } else if (literal instanceof Integer) {
                        // All the work was done in FetchBodyCallback.foundLiteral()
                    } else {
                        // This shouldn't happen
                        throw new MessagingException("Got FETCH response with bogus parameters");
                    }
                }

                if (listener != null) {
                    listener.messageFinished(imapMessage, messageNumber, messageMap.size());
                }
                messageNumber++;
            } else {
                handleUntaggedResponse(response);
            }
        } while (response.getTag() == null);
    }

    @Override
    public void fetchPart(Message message, Part part, MessageRetrievalListener<Message> listener,
            BodyFactory bodyFactory) throws MessagingException {
        checkOpen();

        Set<Long> uids = Collections.singleton(Long.parseLong(message.getUid()));
        int maximumAutoDownloadMessageSize = store.getStoreConfig()
                .getMaximumAutoDownloadMessageSize();

        try {
            UidFetchCommand command = UidFetchCommand.createWithPartParams(uids,
                    maximumAutoDownloadMessageSize, part, bodyFactory);
            command.send(connection);

            ImapResponse response;
            int messageNumber = 0;

            do {
                response = command.readResponse(connection);

                if (response.getTag() == null && ImapResponseParser.equalsIgnoreCase(response.get(1), "FETCH")) {
                    ImapList fetchList = (ImapList) response.getKeyedValue("FETCH");
                    String uid = fetchList.getKeyedString("UID");

                    if (!message.getUid().equals(uid)) {
                        if (K9MailLib.isDebug()) {
                            Timber.d("Did not ask for UID %s for %s", uid, getLogId());
                        }

                        handleUntaggedResponse(response);
                        continue;
                    }

                    if (listener != null) {
                        listener.messageStarted(uid, messageNumber++, 1);
                    }

                    ImapMessage imapMessage = (ImapMessage) message;

                    Object literal = handleFetchResponse(imapMessage, fetchList);

                    if (literal != null) {
                        if (literal instanceof Body) {
                            // Most of the work was done in FetchAttachmentCallback.foundLiteral()
                            MimeMessageHelper.setBody(part, (Body) literal);
                        } else if (literal instanceof String) {
                            String bodyString = (String) literal;
                            InputStream bodyStream = new ByteArrayInputStream(bodyString.getBytes());

                            String contentTransferEncoding =
                                    part.getHeader(MimeHeader.HEADER_CONTENT_TRANSFER_ENCODING)[0];
                            String contentType = part.getHeader(MimeHeader.HEADER_CONTENT_TYPE)[0];
                            Body body = bodyFactory.createBody(contentTransferEncoding, contentType, bodyStream);
                            MimeMessageHelper.setBody(part, body);
                        } else {
                            // This shouldn't happen
                            throw new MessagingException("Got FETCH response with bogus parameters");
                        }
                    }

                    if (listener != null) {
                        listener.messageFinished(message, messageNumber, 1);
                    }
                } else {
                    handleUntaggedResponse(response);
                }

            } while (response.getTag() == null);
        } catch (IOException ioe) {
            throw ioExceptionHandler(connection, ioe);
        }
    }

    // Returns value of body field
    private Object handleFetchResponse(ImapMessage message, ImapList fetchList) throws MessagingException {
        Object result = null;

        if (fetchList.containsKey("FLAGS")) {
            ImapUtility.setMessageFlags(fetchList, message, store);
        }

        if (fetchList.containsKey("INTERNALDATE")) {
            Date internalDate = fetchList.getKeyedDate("INTERNALDATE");
            message.setInternalDate(internalDate);
        }

        if (fetchList.containsKey("RFC822.SIZE")) {
            int size = fetchList.getKeyedNumber("RFC822.SIZE");
            message.setSize(size);
        }

        if (fetchList.containsKey("BODYSTRUCTURE")) {
            ImapList bs = fetchList.getKeyedList("BODYSTRUCTURE");
            if (bs != null) {
                try {
                    parseBodyStructure(bs, message, "TEXT");
                } catch (MessagingException e) {
                    if (K9MailLib.isDebug()) {
                        Timber.d(e, "Error handling message for %s", getLogId());
                    }
                    message.setBody(null);
                }
            }
        }

        if (fetchList.containsKey("BODY")) {
            int index = fetchList.getKeyIndex("BODY") + 2;
            int size = fetchList.size();
            if (index < size) {
                result = fetchList.getObject(index);

                // Check if there's an origin octet
                if (result instanceof String) {
                    String originOctet = (String) result;
                    if (originOctet.startsWith("<") && (index + 1) < size) {
                        result = fetchList.getObject(index + 1);
                    }
                }
            }
        }

        return result;
    }

    protected List<ImapResponse> handleUntaggedResponses(List<ImapResponse> responses) {
        for (ImapResponse response : responses) {
            handleUntaggedResponse(response);
        }

        return responses;
    }

    protected void handlePossibleUidNext(ImapResponse response) {
        if (ImapResponseParser.equalsIgnoreCase(response.get(0), "OK") && response.size() > 1) {
            Object bracketedObj = response.get(1);
            if (bracketedObj instanceof ImapList) {
                ImapList bracketed = (ImapList) bracketedObj;

                if (bracketed.size() > 1) {
                    Object keyObj = bracketed.get(0);
                    if (keyObj instanceof String) {
                        String key = (String) keyObj;
                        if ("UIDNEXT".equalsIgnoreCase(key)) {
                            uidNext = bracketed.getLong(1);
                            if (K9MailLib.isDebug()) {
                                Timber.d("Got UidNext = %s for %s", uidNext, getLogId());
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Handle an untagged response that the caller doesn't care to handle themselves.
     */
    protected void handleUntaggedResponse(ImapResponse response) {
        if (response.getTag() == null && response.size() > 1) {
            if (ImapResponseParser.equalsIgnoreCase(response.get(1), "EXISTS")) {
                messageCount = response.getNumber(0);
                if (K9MailLib.isDebug()) {
                    Timber.d("Got untagged EXISTS with value %d for %s", messageCount, getLogId());
                }
            }

            handlePossibleUidNext(response);

            if (ImapResponseParser.equalsIgnoreCase(response.get(1), "EXPUNGE") && messageCount > 0) {
                messageCount--;
                if (K9MailLib.isDebug()) {
                    Timber.d("Got untagged EXPUNGE with messageCount %d for %s", messageCount, getLogId());
                }
            }

            if (ImapResponseParser.equalsIgnoreCase(response.get(0), "VANISHED") && messageCount > 0) {
                List<String> vanishedUids = ImapUtility.extractVanishedUids(Collections.singletonList(response));
                messageCount -= vanishedUids.size();
                if (K9MailLib.isDebug()) {
                    Timber.d("Got untagged VANISHED with messageCount %d for %s", messageCount, getLogId());
                }
            }
        }
    }

    private void parseBodyStructure(ImapList bs, Part part, String id) throws MessagingException {
        if (bs.get(0) instanceof ImapList) {
            /*
             * This is a multipart/*
             */
            MimeMultipart mp = MimeMultipart.newInstance();
            for (int i = 0, count = bs.size(); i < count; i++) {
                if (bs.get(i) instanceof ImapList) {
                    /*
                     * For each part in the message we're going to add a new BodyPart and parse
                     * into it.
                     */
                    MimeBodyPart bp = new MimeBodyPart();
                    if (id.equalsIgnoreCase("TEXT")) {
                        parseBodyStructure(bs.getList(i), bp, Integer.toString(i + 1));
                    } else {
                        parseBodyStructure(bs.getList(i), bp, id + "." + (i + 1));
                    }
                    mp.addBodyPart(bp);
                } else {
                    /*
                     * We've got to the end of the children of the part, so now we can find out
                     * what type it is and bail out.
                     */
                    String subType = bs.getString(i);
                    mp.setSubType(subType.toLowerCase(Locale.US));
                    break;
                }
            }
            MimeMessageHelper.setBody(part, mp);
        } else {
            /*
             * This is a body. We need to add as much information as we can find out about
             * it to the Part.
             */

            /*
             *  0| 0  body type
             *  1| 1  body subtype
             *  2| 2  body parameter parenthesized list
             *  3| 3  body id (unused)
             *  4| 4  body description (unused)
             *  5| 5  body encoding
             *  6| 6  body size
             *  -| 7  text lines (only for type TEXT, unused)
             * Extensions (optional):
             *  7| 8  body MD5 (unused)
             *  8| 9  body disposition
             *  9|10  body language (unused)
             * 10|11  body location (unused)
             */

            String type = bs.getString(0);
            String subType = bs.getString(1);
            String mimeType = (type + "/" + subType).toLowerCase(Locale.US);

            ImapList bodyParams = null;
            if (bs.get(2) instanceof ImapList) {
                bodyParams = bs.getList(2);
            }
            String encoding = bs.getString(5);
            int size = bs.getNumber(6);

            if (MimeUtility.isMessage(mimeType)) {
//                  A body type of type MESSAGE and subtype RFC822
//                  contains, immediately after the basic fields, the
//                  envelope structure, body structure, and size in
//                  text lines of the encapsulated message.
//                    [MESSAGE, RFC822, [NAME, Fwd: [#HTR-517941]:  update plans at 1am Friday - Memory allocation - displayware.eml], NIL, NIL, 7BIT, 5974, NIL, [INLINE, [FILENAME*0, Fwd: [#HTR-517941]:  update plans at 1am Friday - Memory all, FILENAME*1, ocation - displayware.eml]], NIL]
                /*
                 * This will be caught by fetch and handled appropriately.
                 */
                throw new MessagingException("BODYSTRUCTURE message/rfc822 not yet supported.");
            }

            /*
             * Set the content type with as much information as we know right now.
             */
            StringBuilder contentType = new StringBuilder();
            contentType.append(mimeType);

            if (bodyParams != null) {
                /*
                 * If there are body params we might be able to get some more information out
                 * of them.
                 */
                for (int i = 0, count = bodyParams.size(); i < count; i += 2) {
                    String paramName = bodyParams.getString(i);
                    String paramValue = bodyParams.getString(i + 1);
                    contentType.append(String.format(";\r\n %s=\"%s\"", paramName, paramValue));
                }
            }

            part.setHeader(MimeHeader.HEADER_CONTENT_TYPE, contentType.toString());

            // Extension items
            ImapList bodyDisposition = null;
            if ("text".equalsIgnoreCase(type) && bs.size() > 9 && bs.get(9) instanceof ImapList) {
                bodyDisposition = bs.getList(9);
            } else if (!("text".equalsIgnoreCase(type)) && bs.size() > 8 && bs.get(8) instanceof ImapList) {
                bodyDisposition = bs.getList(8);
            }

            StringBuilder contentDisposition = new StringBuilder();

            if (bodyDisposition != null && !bodyDisposition.isEmpty()) {
                if (!"NIL".equalsIgnoreCase(bodyDisposition.getString(0))) {
                    contentDisposition.append(bodyDisposition.getString(0).toLowerCase(Locale.US));
                }

                if (bodyDisposition.size() > 1 && bodyDisposition.get(1) instanceof ImapList) {
                    ImapList bodyDispositionParams = bodyDisposition.getList(1);
                    /*
                     * If there is body disposition information we can pull some more information
                     * about the attachment out.
                     */
                    for (int i = 0, count = bodyDispositionParams.size(); i < count; i += 2) {
                        String paramName = bodyDispositionParams.getString(i).toLowerCase(Locale.US);
                        String paramValue = bodyDispositionParams.getString(i + 1);
                        contentDisposition.append(String.format(";\r\n %s=\"%s\"", paramName, paramValue));
                    }
                }
            }

            if (MimeUtility.getHeaderParameter(contentDisposition.toString(), "size") == null) {
                contentDisposition.append(String.format(Locale.US, ";\r\n size=%d", size));
            }

            /*
             * Set the content disposition containing at least the size. Attachment
             * handling code will use this down the road.
             */
            part.setHeader(MimeHeader.HEADER_CONTENT_DISPOSITION, contentDisposition.toString());

            /*
             * Set the Content-Transfer-Encoding header. Attachment code will use this
             * to parse the body.
             */
            part.setHeader(MimeHeader.HEADER_CONTENT_TRANSFER_ENCODING, encoding);

            if (part instanceof ImapMessage) {
                ((ImapMessage) part).setSize(size);
            }

            part.setServerExtra(id);
        }
    }

    /**
     * Appends the given messages to the selected folder.
     *
     * <p>
     * This implementation also determines the new UIDs of the given messages on the IMAP
     * server and changes the messages' UIDs to the new server UIDs.
     * </p>
     *
     * @param messages
     *         The messages to append to the folder.
     *
     * @return The mapping of original message UIDs to the new server UIDs.
     */
    @Override
    public Map<String, String> appendMessages(List<? extends Message> messages) throws MessagingException {
        open(OPEN_MODE_RW);
        checkOpen();

        try {
            Map<String, String> uidMap = new HashMap<>();
            for (Message message : messages) {
                long messageSize = message.calculateSize();

                String encodeFolderName = folderNameCodec.encode(getPrefixedName());
                String escapedFolderName = ImapUtility.encodeString(encodeFolderName);
                String combinedFlags = ImapUtility.combineFlags(message.getFlags(),
                        canCreateKeywords || store.getPermanentFlagsIndex().contains(Flag.FORWARDED));
                String command = String.format(Locale.US, "APPEND %s (%s) {%d}", escapedFolderName,
                        combinedFlags, messageSize);
                connection.sendCommand(command, false);

                ImapResponse response;
                do {
                    response = connection.readResponse();

                    handleUntaggedResponse(response);

                    if (response.isContinuationRequested()) {
                        EOLConvertingOutputStream eolOut = new EOLConvertingOutputStream(connection.getOutputStream());
                        message.writeTo(eolOut);
                        eolOut.write('\r');
                        eolOut.write('\n');
                        eolOut.flush();
                    }
                } while (response.getTag() == null);

                if (response.size() > 1) {
                    /*
                     * If the server supports UIDPLUS, then along with the APPEND response it
                     * will return an APPENDUID response code, e.g.
                     *
                     * 11 OK [APPENDUID 2 238268] APPEND completed
                     *
                     * We can use the UID included in this response to update our records.
                     */
                    Object responseList = response.get(1);

                    if (responseList instanceof ImapList) {
                        ImapList appendList = (ImapList) responseList;
                        if (appendList.size() >= 3 && appendList.getString(0).equals("APPENDUID")) {
                            String newUid = appendList.getString(2);

                            if (!TextUtils.isEmpty(newUid)) {
                                message.setUid(newUid);
                                uidMap.put(message.getUid(), newUid);
                                continue;
                            }
                        }
                    }
                }

                /*
                 * This part is executed in case the server does not support UIDPLUS or does
                 * not implement the APPENDUID response code.
                 */
                String newUid = getUidFromMessageId(message);
                if (K9MailLib.isDebug()) {
                    Timber.d("Got UID %s for message for %s", newUid, getLogId());
                }

                if (!TextUtils.isEmpty(newUid)) {
                    uidMap.put(message.getUid(), newUid);
                    message.setUid(newUid);
                }
            }

            /*
             * We need uidMap to be null if new UIDs are not available to maintain consistency
             * with the behavior of other similar methods (copyMessages, moveMessages) which
             * return null.
             */
            return (uidMap.isEmpty()) ? null : uidMap;
        } catch (IOException ioe) {
            throw ioExceptionHandler(connection, ioe);
        }
    }

    @Override
    public String getUidFromMessageId(Message message) throws MessagingException {
        /*
        * Try to find the UID of the message we just appended using the
        * Message-ID header.
        */
        String[] messageIdHeader = message.getHeader("Message-ID");

        if (messageIdHeader.length == 0) {
            if (K9MailLib.isDebug()) {
                Timber.d("Did not get a message-id in order to search for UID  for %s", getLogId());
            }
            return null;
        }

        String messageId = messageIdHeader[0];
        if (K9MailLib.isDebug()) {
            Timber.d("Looking for UID for message with message-id %s for %s", messageId, getLogId());
        }

        UidSearchCommand searchCommand = new UidSearchCommand.Builder()
                .messageId(messageId)
                .build();

        List<Long> uids = searchCommand.execute(connection, this).getNumbers();
        if (uids.size() > 0) {
            return Long.toString(uids.get(0));
        }

        return null;
    }

    @Override
    public void expunge() throws MessagingException {
        open(OPEN_MODE_RW);
        checkOpen();

        try {
            executeSimpleCommand("EXPUNGE");
        } catch (IOException ioe) {
            throw ioExceptionHandler(connection, ioe);
        }
    }

    public List<String> expungeUsingQresync() throws MessagingException {
        open(OPEN_MODE_RW);
        checkOpen();

        try {
            List<ImapResponse> expungeResponses = executeSimpleCommand("EXPUNGE");
            updateHighestModSeqFromResponses(expungeResponses);
            return ImapUtility.extractVanishedUids(expungeResponses);
        } catch (IOException ioe) {
            throw ioExceptionHandler(connection, ioe);
        }
    }

    private void updateHighestModSeqFromResponses(List<ImapResponse> imapResponses) {
        for (ImapResponse imapResponse : imapResponses) {
            Long highestModSeq = ImapUtility.extractHighestModSeq(imapResponse);
            if (highestModSeq != null) {
                this.highestModSeq = highestModSeq;
            }
        }
    }

    @Override
    public void setFlags(Set<Flag> flags, boolean value) throws MessagingException {
        open(OPEN_MODE_RW);
        checkOpen();

        boolean canCreateForwardedFlag = canCreateKeywords ||
                store.getPermanentFlagsIndex().contains(Flag.FORWARDED);


        UidStoreCommand command = UidStoreCommand.createWithAllUids(value, flags,
                canCreateForwardedFlag);
        command.execute(connection, this);
    }

    @Override
    public String getNewPushState(String oldSerializedPushState, Message message) {
        try {
            String uid = message.getUid();
            long messageUid = Long.parseLong(uid);

            ImapPushState oldPushState = ImapPushState.parse(oldSerializedPushState);

            if (messageUid >= oldPushState.uidNext) {
                long uidNext = messageUid + 1;
                ImapPushState newPushState = new ImapPushState(uidNext);

                return newPushState.toString();
            } else {
                return null;
            }
        } catch (Exception e) {
            Timber.e(e, "Exception while updated push state for %s", getLogId());
            return null;
        }
    }

    @Override
    public void setFlags(List<? extends Message> messages, final Set<Flag> flags, boolean value)
            throws MessagingException {
        open(OPEN_MODE_RW);
        checkOpen();

        Set<Long> uids = new HashSet<>(messages.size());
        for (Message message : messages) {
            uids.add(Long.parseLong(message.getUid()));
        }

        boolean canCreateForwardedFlag = canCreateKeywords ||
                store.getPermanentFlagsIndex().contains(Flag.FORWARDED);

        UidStoreCommand command = UidStoreCommand.createWithUids(uids, value, flags,
                canCreateForwardedFlag);
        command.execute(connection, this);
    }

    private void checkOpen() throws MessagingException {
        if (!isOpen()) {
            throw new MessagingException("Folder " + getPrefixedName() + " is not open.");
        }
    }

    public MessagingException ioExceptionHandler(ImapConnection connection, IOException ioe) {
        Timber.e(ioe, "IOException for %s", getLogId());

        if (connection != null) {
            connection.close();
        }

        close();

        return new MessagingException("IO Error", ioe);
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof ImapFolder) {
            ImapFolder otherFolder = (ImapFolder) other;
            return otherFolder.getName().equalsIgnoreCase(getName());
        }

        return super.equals(other);
    }

    @Override
    public int hashCode() {
        return getName().hashCode();
    }

    private ImapStore getStore() {
        return store;
    }

    Set<Flag> getPermanentFlags() {
        return getStore().getPermanentFlagsIndex();
    }

    protected String getLogId() {
        String id = store.getStoreConfig().toString() + ":" + getName() + "/" + Thread.currentThread().getName();
        if (connection != null) {
            id += "/" + connection.getLogId();
        }

        return id;
    }

    /**
     * Search the remote ImapFolder.
     * @param queryString String to query for.
     * @param requiredFlags Mandatory flags
     * @param forbiddenFlags Flags to exclude
     * @return List of messages found
     * @throws MessagingException On any error.
     */
    @Override
    public List<ImapMessage> search(final String queryString, final Set<Flag> requiredFlags,
            final Set<Flag> forbiddenFlags) throws MessagingException {

        if (!store.getStoreConfig().allowRemoteSearch()) {
            throw new MessagingException("Your settings do not allow remote searching of this account");
        }

        try {
            open(OPEN_MODE_RO);
            checkOpen();

            inSearch = true;

            UidSearchCommand searchCommand = new UidSearchCommand.Builder()
                    .queryString(queryString)
                    .performFullTextSearch(store.getStoreConfig().isRemoteSearchFullText())
                    .requiredFlags(requiredFlags)
                    .forbiddenFlags(forbiddenFlags)
                    .build();
            return getMessages(searchCommand.execute(connection, this), null);
        } finally {
            inSearch = false;
        }
    }
}
