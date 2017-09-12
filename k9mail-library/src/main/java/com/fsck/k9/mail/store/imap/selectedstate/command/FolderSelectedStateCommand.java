package com.fsck.k9.mail.store.imap.selectedstate.command;


import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.store.imap.ImapConnection;
import com.fsck.k9.mail.store.imap.ImapFolder;
import com.fsck.k9.mail.store.imap.ImapResponse;
import com.fsck.k9.mail.store.imap.ImapUtility;
import com.fsck.k9.mail.store.imap.selectedstate.response.SelectedStateResponse;


abstract class FolderSelectedStateCommand {
    /* The below limits are 20 octets less than the recommended limits, in order to compensate for
    the length of the command tag, the space after the tag and the CRLF at the end of the command
    (these are not taken into account when calculating the length of the command). For more
    information, refer to section 4 of RFC 7162.

    The length limit for servers supporting the CONDSTORE extension is large in order to support
    the QRESYNC parameter to the SELECT/EXAMINE commands, which accept a list of known message
    sequence numbers as well as their corresponding UIDs.
     */
    private static final int LENGTH_LIMIT_WITHOUT_CONDSTORE = 980;
    private static final int LENGTH_LIMIT_WITH_CONDSTORE = 8172;
  
    private Set<Long> idSet;
    private List<ContiguousIdGroup> idGroups;

    FolderSelectedStateCommand(Set<Long> idSet) {
        this.idSet = new TreeSet<>(idSet);
        this.idGroups = new ArrayList<>(0);
    }

    abstract String createCommandString();

    String createCombinedIdString() {
        if (idSet.isEmpty() && idGroups.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();

        builder.append(ImapUtility.join(",", idSet));
        if (!idSet.isEmpty() && !idGroups.isEmpty()) {
            builder.append(",");
        }
        builder.append(ImapUtility.join(",", idGroups));

        builder.append(" ");
        return builder.toString();
    }

    public SelectedStateResponse execute(ImapConnection connection, ImapFolder folder) throws MessagingException {
        return null;
    }

    List<List<ImapResponse>> executeInternal(ImapConnection connection, ImapFolder folder)
            throws IOException, MessagingException {
        ImapCommandSplitter.optimizeGroupings(this);
        List<String> commands;
        String commandString = createCommandString();

        if (commandString.length() > getCommandLengthLimit(connection)) {
            commands = ImapCommandSplitter.splitCommand(this, getCommandLengthLimit(connection));
        } else {
            commands = Collections.singletonList(commandString);
        }

        List<List<ImapResponse>> responses = new ArrayList<>();
        for (String command : commands) {
            responses.add(folder.executeSimpleCommand(command));
        }
        return responses;
    }

    Set<Long> getIdSet() {
        return idSet;
    }

    void setIdSet(Set<Long> idSet) {
        this.idSet = new TreeSet<>(idSet);
    }

    List<ContiguousIdGroup> getIdGroups() {
        return idGroups;
    }

    private int getCommandLengthLimit(ImapConnection connection) throws IOException, MessagingException  {
        boolean condstoreSupported = connection.isCondstoreCapable();
        if (condstoreSupported) {
            return LENGTH_LIMIT_WITH_CONDSTORE;
        } else {
            return LENGTH_LIMIT_WITHOUT_CONDSTORE;
        }
    }

    void useAllIds(boolean useAllIds) {
        if (useAllIds) {
            idSet = Collections.emptySet();
            idGroups = Collections.singletonList(new ContiguousIdGroup(ContiguousIdGroup.FIRST_ID,
                    ContiguousIdGroup.LAST_ID));
        }
    }

    void useOnlyHighestId(boolean useOnlyHighestId) {
        if (useOnlyHighestId) {
            idSet = Collections.emptySet();
            idGroups = Collections.singletonList(new ContiguousIdGroup(ContiguousIdGroup.LAST_ID,
                    ContiguousIdGroup.LAST_ID));
        }
    }

    void addId(Long id) {
        idSet.add(id);
    }

    void addIdGroup(Long start, Long end) {
        if (start != null && end != null) {
            idGroups.add(new ContiguousIdGroup(start, end));
        }
    }

    void clearIds() {
        idSet.clear();
        idGroups.clear();
    }

    static class ContiguousIdGroup {
        static final long FIRST_ID = 1L;
        static final long LAST_ID = Long.MAX_VALUE;

        private Long start;
        private Long end;

        ContiguousIdGroup(Long start, Long end) {
            if (start <= end) {
                this.start = start;
                this.end = end;
            } else {
                this.start = end;
                this.end = start;
            }
        }

        public Long getStart() {
            return start;
        }

        public Long getEnd() {
            return end;
        }

        @Override
        public String toString() {
            //The highest UID is queried as *:*, see the note in RFC 3501, page 60
            if (start == LAST_ID && end == LAST_ID) {
                return "*:*";
            }
            if (start != LAST_ID && end == LAST_ID) {
                return start + ":" + "*";
            }
            return start + ":" + end;
        }
    }
}
