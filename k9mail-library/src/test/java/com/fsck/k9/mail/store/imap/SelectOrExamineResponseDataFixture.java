package com.fsck.k9.mail.store.imap;


import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fsck.k9.mail.Flag;

import static com.fsck.k9.mail.Folder.OPEN_MODE_RW;
import static com.fsck.k9.mail.store.imap.ImapResponseHelper.createImapResponse;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonMap;


class SelectOrExamineResponseDataFixture {

    static final int TEST_MESSAGE_COUNT = 23;
    static final long TEST_CURRENT_UID_VALIDITY = 1234567L;
    static final long TEST_CURRENT_HIGHEST_MOD_SEQ = 10045679000L;
    static final List<String> TEST_EXPUNGED_UIDS = asList("10056", "10078");
    static final String TEST_MODIFIED_MESSAGE_UID = "10099";
    static final Set<Flag> TEST_MODIFIED_MESSAGE_FLAGS = new HashSet<>(asList(Flag.SEEN, Flag.FLAGGED));
    static final Map<String, Set<Flag>> TEST_MODIFIED_MESSAGE_DATA = singletonMap(TEST_MODIFIED_MESSAGE_UID,
            TEST_MODIFIED_MESSAGE_FLAGS);

    private int messageCount = TEST_MESSAGE_COUNT;
    private Long currentUidValidity = TEST_CURRENT_UID_VALIDITY;
    private Long currentHighestModSeq = TEST_CURRENT_HIGHEST_MOD_SEQ;
    private List<String> expungedUids = TEST_EXPUNGED_UIDS;
    private Map<String, Set<Flag>> modifiedMessageData = TEST_MODIFIED_MESSAGE_DATA;

    static SelectOrExamineResponseDataFixture getDefaultInstance() {
        return new SelectOrExamineResponseDataFixture();
    }

    private SelectOrExamineResponseDataFixture() {
    }

    List<ImapResponse> create(int openMode) throws IOException {
        currentHighestModSeq = null;
        expungedUids = null;
        modifiedMessageData = null;
        return createWithMode(openMode);
    }

    List<ImapResponse> createForCondstoreParam(int openMode, boolean noModSeq) throws IOException {
        if (noModSeq) {
            currentHighestModSeq = null;
        }
        expungedUids = null;
        modifiedMessageData = null;
        return createWithMode(openMode);
    }

    List<ImapResponse> createForQresyncParam(int openMode, boolean noModSeq) throws IOException {
        if (noModSeq) {
            currentHighestModSeq = null;
        }
        return createWithMode(openMode);
    }

    List<ImapResponse> createWithTaggedResponse(String lastLine) throws IOException {
        List<ImapResponse> response = createUntaggedOpenResponse();
        response.add(createImapResponse(lastLine));
        return response;
    }

    private List<ImapResponse> createWithMode(int openMode) throws IOException {
        List<ImapResponse> response = createUntaggedOpenResponse();
        ImapResponse finishedResponse = (openMode == OPEN_MODE_RW) ?
                createImapResponse("2 OK [READ-WRITE] Select completed.") :
                createImapResponse("2 OK [READ-ONLY] Examine completed.");
        response.add(finishedResponse);
        return response;
    }

    private List<ImapResponse> createUntaggedOpenResponse() throws IOException {
        List<ImapResponse> openResponse = new ArrayList<>(asList(
                createImapResponse("* FLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft NonJunk $MDNSent)"),
                createImapResponse("* OK [PERMANENTFLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft NonJunk " +
                        "$MDNSent \\*)] Flags permitted."),
                createImapResponse(String.format(Locale.US, "* %d EXISTS", messageCount)),
                createImapResponse("* 0 RECENT"),
                createImapResponse(String.format(Locale.US, "* OK [UIDVALIDITY %d] UIDs valid", currentUidValidity)),
                createImapResponse("* OK [UIDNEXT 57576] Predicted next UID")
        ));

        String highestModSeqResponse;
        if (currentHighestModSeq == null) {
            highestModSeqResponse = "* OK [NOMODSEQ] Sorry, this mailbox format doesn't support modsequences";
        } else {
            highestModSeqResponse = String.format(Locale.US,
                    "* OK [HIGHESTMODSEQ %d] Highest mailbox mod-sequence", currentHighestModSeq);
        }
        openResponse.add(createImapResponse(highestModSeqResponse));

        if (expungedUids != null || modifiedMessageData != null) {
            if (expungedUids != null) {
                String vanishedUidsString = ImapUtility.join(",", expungedUids);
                openResponse.add(createImapResponse(String.format("* VANISHED (EARLIER) %s", vanishedUidsString)));
            }

            if (currentHighestModSeq != null && modifiedMessageData != null) {
                int seqNum = 30;
                for (Map.Entry<String, Set<Flag>> modifiedMessage : modifiedMessageData.entrySet()) {
                    String fetchResponseString = String.format(Locale.US, "* %d FETCH (UID %s FLAGS (%s) MODSEQ (%d))",
                            ++seqNum, modifiedMessage.getKey(),
                            ImapUtility.combineFlags(modifiedMessage.getValue(), false), currentHighestModSeq--);
                    openResponse.add(createImapResponse(fetchResponseString));
                }
            }
        }

        return openResponse;
    }
}
