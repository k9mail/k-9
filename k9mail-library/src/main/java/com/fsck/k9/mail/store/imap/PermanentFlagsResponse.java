package com.fsck.k9.mail.store.imap;


import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import com.fsck.k9.mail.Flag;

import static com.fsck.k9.mail.store.imap.ImapResponseParser.equalsIgnoreCase;


class PermanentFlagsResponse {
    private final Set<Flag> flags;
    private final boolean canCreateKeywords;


    private PermanentFlagsResponse(Set<Flag> flags, boolean canCreateKeywords) {
        this.flags = Collections.unmodifiableSet(flags);
        this.canCreateKeywords = canCreateKeywords;
    }

    public static PermanentFlagsResponse parse(ImapResponse response) {
        if (response.isTagged() || !equalsIgnoreCase(response.get(0), Responses.OK) || !response.isList(1)) {
            return null;
        }

        ImapList responseTextList = response.getList(1);
        if (responseTextList.size() < 2 || !equalsIgnoreCase(responseTextList.get(0), Responses.PERMANENTFLAGS) ||
                !responseTextList.isList(1)) {
            return null;
        }

        ImapList permanentFlagsList = responseTextList.getList(1);
        int size = permanentFlagsList.size();
        Set<Flag> flags = new HashSet<>(size);
        boolean canCreateKeywords = false;

        for (int i = 0; i < size; i++) {
            if (!permanentFlagsList.isString(i)) {
                return null;
            }

            String flag = permanentFlagsList.getString(i);
            String compareFlag = flag.toLowerCase(Locale.US);

            switch (compareFlag) {
                case "\\deleted": {
                    flags.add(Flag.DELETED);
                    break;
                }
                case "\\answered": {
                    flags.add(Flag.ANSWERED);
                    break;
                }
                case "\\seen": {
                    flags.add(Flag.SEEN);
                    break;
                }
                case "\\flagged": {
                    flags.add(Flag.FLAGGED);
                    break;
                }
                case "$forwarded": {
                    flags.add(Flag.FORWARDED);
                    break;
                }
                case "\\*": {
                    canCreateKeywords = true;
                    break;
                }
            }
        }

        return new PermanentFlagsResponse(flags, canCreateKeywords);
    }

    public Set<Flag> getFlags() {
        return flags;
    }

    boolean canCreateKeywords() {
        return canCreateKeywords;
    }
}
