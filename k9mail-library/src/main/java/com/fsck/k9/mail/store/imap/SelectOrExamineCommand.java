package com.fsck.k9.mail.store.imap;


import com.fsck.k9.mail.K9MailLib;

import static com.fsck.k9.mail.Folder.OPEN_MODE_RW;


class SelectOrExamineCommand {
    private static final long INVALID_UIDVALIDITY = -1L;
    private static final long INVALID_HIGHESTMODSEQ = -1L;

    private int mode;
    private String escapedFolderName;
    private boolean useCondstore;
    private long cachedUidValidity;
    private long cachedHighestModSeq;

    static SelectOrExamineCommand create(int mode, String escapedFolderName) {
        return new SelectOrExamineCommand(mode, escapedFolderName, false, INVALID_UIDVALIDITY, INVALID_HIGHESTMODSEQ);
    }

    static SelectOrExamineCommand createWithCondstoreParameter(int mode, String escapedFolderName) {
        return new SelectOrExamineCommand(mode, escapedFolderName, true, INVALID_UIDVALIDITY, INVALID_HIGHESTMODSEQ);
    }

    static SelectOrExamineCommand createWithQresyncParameter(int mode, String escapedFolderName, long cachedUidValidity,
            long cachedHighestModSeq) {
        return new SelectOrExamineCommand(mode, escapedFolderName, true, cachedUidValidity, cachedHighestModSeq);
    }

    private SelectOrExamineCommand(int mode, String escapedFolderName, boolean useCondstore, long cachedUidValidity,
            long cachedHighestModSeq) {
        this.mode = mode;
        this.escapedFolderName = escapedFolderName;
        this.useCondstore = useCondstore;
        this.cachedUidValidity = cachedUidValidity;
        this.cachedHighestModSeq = cachedHighestModSeq;
    }

    String createCommandString() {
        StringBuilder builder = new StringBuilder();
        String openCommand = mode == OPEN_MODE_RW ? "SELECT" : "EXAMINE";
        builder.append(String.format("%s %s", openCommand, escapedFolderName));
        if (useQresync() && ImapConfig.shouldUseQresync()) {
            builder.append(String.format(" (%s (%s %s))", Capabilities.QRESYNC, cachedUidValidity, cachedHighestModSeq));
        } else if (useCondstore && ImapConfig.shouldUseCondstore()) {
            builder.append(String.format(" (%s)", Capabilities.CONDSTORE));
        }
        return builder.toString();
    }

    private boolean useQresync() {
        return cachedUidValidity > 0 && cachedHighestModSeq > 0;
    }
}
