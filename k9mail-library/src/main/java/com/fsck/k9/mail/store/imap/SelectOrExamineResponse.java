package com.fsck.k9.mail.store.imap;


import java.io.IOException;
import java.util.List;
import java.util.Set;

import com.fsck.k9.mail.Flag;
import com.fsck.k9.mail.Folder;
import com.fsck.k9.mail.K9MailLib;
import com.fsck.k9.mail.MessagingException;

import static com.fsck.k9.mail.store.imap.ImapResponseParser.equalsIgnoreCase;


class SelectOrExamineResponse {

    private static final long INVALID_HIGHEST_MOD_SEQ = -1L;

    private long uidValidity;
    private long highestModSeq = INVALID_HIGHEST_MOD_SEQ;
    private boolean canCreateKeywords;
    private QresyncParamResponse qresyncParamResponse;
    private Boolean readWriteMode;

    private SelectOrExamineResponse(List<ImapResponse> imapResponses, ImapFolder folder) throws IOException,
            MessagingException {
        parse(imapResponses, folder);
    }

    public static SelectOrExamineResponse newInstance(List<ImapResponse> imapResponses, ImapFolder folder) throws
            IOException, MessagingException {
        if (!isResponseValid(imapResponses)) {
            return null;
        }
        return new SelectOrExamineResponse(imapResponses, folder);
    }

    private void parse(List<ImapResponse> imapResponses, ImapFolder folder) throws IOException, MessagingException {
        for (ImapResponse imapResponse : imapResponses) {
            if (imapResponse.isTagged() || !equalsIgnoreCase(imapResponse.get(0), Responses.OK)
                    || !imapResponse.isList(1)) {
                continue;
            }
            parseUidValidity(imapResponse);
            parseHighestModSeq(imapResponse);
            parsePermanentFlags(imapResponse, folder.getPermanentFlags());
        }
        this.readWriteMode = isModeReadWriteIfAvailable(ImapUtility.getLastResponse(imapResponses));
        if (folder.doesConnectionSupportQresync() && highestModSeq != INVALID_HIGHEST_MOD_SEQ
                && ImapConfig.shouldUseQresync()) {
            this.qresyncParamResponse = QresyncParamResponse.fromSelectOrExamineResponse(imapResponses, folder);
        } else {
            this.qresyncParamResponse = null;
        }
    }

    private void parseUidValidity(ImapResponse imapResponse) {
        ImapList responseTextList = imapResponse.getList(1);
        if (responseTextList.size() < 2 || !(equalsIgnoreCase(responseTextList.get(0), Responses.UIDVALIDITY))) {
            return;
        }
        this.uidValidity = Long.parseLong(responseTextList.getString(1));
    }

    private void parseHighestModSeq(ImapResponse imapResponse) throws IOException, MessagingException {
        Long highestModSeq = ImapUtility.extractHighestModSeq(imapResponse);
        if (highestModSeq != null) {
            this.highestModSeq = highestModSeq;
        }
    }

    private void parsePermanentFlags(ImapResponse imapResponse, Set<Flag> permanentFlags) {
        PermanentFlagsResponse permanentFlagsResponse = PermanentFlagsResponse.parse(imapResponse);
        if (permanentFlagsResponse == null) {
            return;
        }

        permanentFlags.addAll(permanentFlagsResponse.getFlags());
        this.canCreateKeywords = permanentFlagsResponse.canCreateKeywords();
    }

    private static Boolean isModeReadWriteIfAvailable(ImapResponse imapResponse) {
        if (!imapResponse.isList(1)) {
            return null;
        }

        ImapList responseTextList = imapResponse.getList(1);
        if (!responseTextList.isString(0)) {
            return null;
        }

        String responseCode = responseTextList.getString(0);
        if ("READ-ONLY".equalsIgnoreCase(responseCode)) {
            return Boolean.FALSE;
        } else if ("READ-WRITE".equalsIgnoreCase(responseCode)) {
            return Boolean.TRUE;
        }

        return null;
    }

    private static boolean isResponseValid(List<ImapResponse> imapResponses) {
        ImapResponse lastResponse = ImapUtility.getLastResponse(imapResponses);
        if (!lastResponse.isTagged() || !equalsIgnoreCase(lastResponse.get(0), Responses.OK)) {
            return false;
        }
        return true;
    }

    long getUidValidity() {
        return uidValidity;
    }

    long getHighestModSeq() {
        return highestModSeq;
    }

    boolean canCreateKeywords() {
        return canCreateKeywords;
    }

    QresyncParamResponse getQresyncParamResponse() {
        return qresyncParamResponse;
    }

    boolean hasOpenMode() {
        return readWriteMode != null;
    }

    int getOpenMode() {
        if (!hasOpenMode()) {
            throw new IllegalStateException("Called getOpenMode() despite hasOpenMode() returning false");
        }

        return readWriteMode ? Folder.OPEN_MODE_RW : Folder.OPEN_MODE_RO;
    }
}
