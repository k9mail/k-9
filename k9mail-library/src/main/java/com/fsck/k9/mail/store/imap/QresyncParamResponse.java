package com.fsck.k9.mail.store.imap;


import java.util.ArrayList;
import java.util.List;

import com.fsck.k9.mail.MessagingException;


public class QresyncParamResponse {

    private List<String> expungedUids;
    private List<ImapMessage> modifiedMessages;

    private QresyncParamResponse(List<ImapResponse> imapResponses, ImapFolder folder) throws MessagingException {
        expungedUids = new ArrayList<>();
        modifiedMessages = new ArrayList<>();
        parse(imapResponses, folder);
    }

    static QresyncParamResponse fromSelectOrExamineResponse(List<ImapResponse> imapResponses, ImapFolder folder)
            throws MessagingException {
        return new QresyncParamResponse(imapResponses, folder);
    }

    private void parse(List<ImapResponse> imapResponses, ImapFolder folder) throws MessagingException {
        for (ImapResponse imapResponse : imapResponses) {
            parseExpungedUids(imapResponse);
            parseModifiedMessages(imapResponse, folder);
        }
    }

    private void parseExpungedUids(ImapResponse imapResponse) {
        if (imapResponse.getTag() == null && ImapResponseParser.equalsIgnoreCase(imapResponse.get(0), "VANISHED") &&
                imapResponse.isString(2)) {
            this.expungedUids.addAll(ImapUtility.getImapSequenceValues(imapResponse.getString(2)));
        }
    }

    private void parseModifiedMessages(ImapResponse imapResponse, ImapFolder folder) throws MessagingException {
        if (imapResponse.getTag() == null && ImapResponseParser.equalsIgnoreCase(imapResponse.get(1), "FETCH")) {
            ImapList fetchList = (ImapList) imapResponse.getKeyedValue("FETCH");
            String uid = fetchList.getKeyedString("UID");
            ImapMessage message = new ImapMessage(uid, folder);
            ImapUtility.setMessageFlags(fetchList, message, folder.store);
            this.modifiedMessages.add(message);
        }
    }

    public List<String> getExpungedUids() {
        return expungedUids;
    }

    public List<ImapMessage> getModifiedMessages() {
        return modifiedMessages;
    }
}
