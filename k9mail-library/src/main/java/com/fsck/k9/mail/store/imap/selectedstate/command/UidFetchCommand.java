package com.fsck.k9.mail.store.imap.selectedstate.command;


import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fsck.k9.mail.BodyFactory;
import com.fsck.k9.mail.FetchProfile;
import com.fsck.k9.mail.K9MailLib;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.Part;
import com.fsck.k9.mail.store.imap.Commands;
import com.fsck.k9.mail.store.imap.FetchBodyCallback;
import com.fsck.k9.mail.store.imap.FetchPartCallback;
import com.fsck.k9.mail.store.imap.ImapConnection;
import com.fsck.k9.mail.store.imap.ImapResponse;
import com.fsck.k9.mail.store.imap.ImapResponseCallback;
import com.fsck.k9.mail.store.imap.ImapUtility;


public class UidFetchCommand extends FolderSelectedStateCommand {
    private int maximumAutoDownloadMessageSize;
    private Map<String, Message> messageMap;
    private FetchProfile fetchProfile;
    private Part part;
    private BodyFactory bodyFactory;
    private Long changedSince;

    private UidFetchCommand(Set<Long> uids, int maximumAutoDownloadMessageSize) {
        super(uids);
        this.maximumAutoDownloadMessageSize = maximumAutoDownloadMessageSize;
    }

    @Override
    String createCommandString() {
        StringBuilder builder = new StringBuilder(Commands.UID_FETCH).append(" ");
        builder.append(createCombinedIdString());
        addDataItems(builder);
        if (changedSince != null) {
            builder.append(String.format("(CHANGEDSINCE %s) ", String.valueOf(changedSince)));
        }
        return builder.toString().trim();
    }

    public void send(ImapConnection connection) throws IOException, MessagingException {
        ImapCommandSplitter.optimizeGroupings(this);
        connection.sendCommand(createCommandString(), false);
    }

    public ImapResponse readResponse(ImapConnection connection) throws IOException {
        ImapResponseCallback callback = null;

        if (fetchProfile != null && messageMap != null) {
            if (fetchProfile.contains(FetchProfile.Item.BODY) || fetchProfile.contains(FetchProfile.Item.BODY_SANE)) {
                callback = new FetchBodyCallback(messageMap);
            }
        } else if (part != null) {
            callback = new FetchPartCallback(part, bodyFactory);
        }

        return connection.readResponse(callback);
    }

    private void addDataItems(StringBuilder builder) {
        if (fetchProfile != null && messageMap != null) {

            Set<String> fetchFields = new LinkedHashSet<>();
            fetchFields.add("UID");

            if (fetchProfile.contains(FetchProfile.Item.FLAGS)) {
                fetchFields.add("FLAGS");
            }

            if (fetchProfile.contains(FetchProfile.Item.ENVELOPE)) {
                fetchFields.add("INTERNALDATE");
                fetchFields.add("RFC822.SIZE");
                fetchFields.add("BODY.PEEK[HEADER.FIELDS (date subject from content-type to cc " +
                        "reply-to message-id references in-reply-to " + K9MailLib.IDENTITY_HEADER + ")]");
            }

            if (fetchProfile.contains(FetchProfile.Item.STRUCTURE)) {
                fetchFields.add("BODYSTRUCTURE");
            }

            if (fetchProfile.contains(FetchProfile.Item.BODY_SANE)) {
                if (maximumAutoDownloadMessageSize > 0) {
                    fetchFields.add(String.format(Locale.US, "BODY.PEEK[]<0.%d>", maximumAutoDownloadMessageSize));
                } else {
                    fetchFields.add("BODY.PEEK[]");
                }
            }

            if (fetchProfile.contains(FetchProfile.Item.BODY)) {
                fetchFields.add("BODY.PEEK[]");
            }

            String spaceSeparatedFetchFields = ImapUtility.join(" ", fetchFields);
            builder.append("(").append(spaceSeparatedFetchFields).append(") ");
        } else if (part != null) {
            String partId = part.getServerExtra();

            String fetch;
            if ("TEXT".equalsIgnoreCase(partId)) {
                fetch = String.format(Locale.US, "BODY.PEEK[TEXT]<0.%d>", maximumAutoDownloadMessageSize);
            } else {
                fetch = String.format("BODY.PEEK[%s]", partId);
            }

            builder.append("(UID ").append(fetch).append(")");
        }
    }

    public static UidFetchCommand createWithMessageParams(Set<Long> uids,
                                                          int maximumAutoDownloadMessageSize,
                                                          Map<String, Message> messageMap,
                                                          FetchProfile fetchProfile,
                                                          Long changedSince) {
        UidFetchCommand command = new UidFetchCommand(uids, maximumAutoDownloadMessageSize);
        command.messageMap = messageMap;
        command.fetchProfile = fetchProfile;
        command.changedSince = changedSince;
        return command;
    }

    public static UidFetchCommand createWithPartParams(Set<Long> uids,
                                                       int maximumAutoDownloadMessageSize,
                                                       Part part,
                                                       BodyFactory bodyFactory) {
        UidFetchCommand command = new UidFetchCommand(uids, maximumAutoDownloadMessageSize);
        command.part = part;
        command.bodyFactory = bodyFactory;
        return command;
    }
}
