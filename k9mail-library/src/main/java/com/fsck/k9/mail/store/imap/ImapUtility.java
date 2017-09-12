/*
 * Copyright (C) 2012 The K-9 Dog Walkers
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.fsck.k9.mail.store.imap;


import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.fsck.k9.mail.Flag;
import com.fsck.k9.mail.MessagingException;
import timber.log.Timber;


/**
 * Utility methods for use with IMAP.
 */
public class ImapUtility {
    /**
     * Gets all of the values in a sequence set per RFC 3501.
     *
     * <p>
     * Any ranges are expanded into a list of individual numbers.
     * </p>
     *
     * <pre>
     * sequence-number = nz-number / "*"
     * sequence-range  = sequence-number ":" sequence-number
     * sequence-set    = (sequence-number / sequence-range) *("," sequence-set)
     * </pre>
     *
     * @param set
     *         The sequence set string as received by the server.
     *
     * @return The list of IDs as strings in this sequence set. If the set is invalid, an empty
     *         list is returned.
     */
    public static List<String> getImapSequenceValues(String set) {
        List<String> list = new ArrayList<String>();
        if (set != null) {
            String[] setItems = set.split(",");
            for (String item : setItems) {
                if (item.indexOf(':') == -1) {
                    // simple item
                    if (isNumberValid(item)) {
                        list.add(item);
                    }
                } else {
                    // range
                    list.addAll(getImapRangeValues(item));
                }
            }
        }

        return list;
    }

    /**
     * Expand the given number range into a list of individual numbers.
     *
     * <pre>
     * sequence-number = nz-number / "*"
     * sequence-range  = sequence-number ":" sequence-number
     * sequence-set    = (sequence-number / sequence-range) *("," sequence-set)
     * </pre>
     *
     * @param range
     *         The range string as received by the server.
     *
     * @return The list of IDs as strings in this range. If the range is not valid, an empty list
     *         is returned.
     */
    public static List<String> getImapRangeValues(String range) {
        List<String> list = new ArrayList<String>();
        try {
            if (range != null) {
                int colonPos = range.indexOf(':');
                if (colonPos > 0) {
                    long first  = Long.parseLong(range.substring(0, colonPos));
                    long second = Long.parseLong(range.substring(colonPos + 1));
                    if (is32bitValue(first) && is32bitValue(second)) {
                        if (first < second) {
                            for (long i = first; i <= second; i++) {
                                list.add(Long.toString(i));
                            }
                        } else {
                            for (long i = first; i >= second; i--) {
                                list.add(Long.toString(i));
                            }
                        }
                    } else {
                        Timber.d("Invalid range: %s", range);
                    }
                }
            }
        } catch (NumberFormatException e) {
            Timber.d(e, "Invalid range value: %s", range);
        }

        return list;
    }

    private static boolean isNumberValid(String number) {
        try {
            long value = Long.parseLong(number);
            if (is32bitValue(value)) {
                return true;
            }
        } catch (NumberFormatException e) {
            // do nothing
        }

        Timber.d("Invalid UID value: %s", number);

        return false;
    }

    private static boolean is32bitValue(long value) {
        return ((value & ~0xFFFFFFFFL) == 0L);
    }

    /**
     * Encode a string to be able to use it in an IMAP command.
     *
     * "A quoted string is a sequence of zero or more 7-bit characters,
     *  excluding CR and LF, with double quote (<">) characters at each
     *  end." - Section 4.3, RFC 3501
     *
     * Double quotes and backslash are escaped by prepending a backslash.
     *
     * @param str
     *         The input string (only 7-bit characters allowed).
     *
     * @return The string encoded as quoted (IMAP) string.
     */
    //TODO use a literal string
    public static String encodeString(String str) {
        return "\"" + str.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public static ImapResponse getLastResponse(List<ImapResponse> responses) {
        int lastIndex = responses.size() - 1;

        return responses.get(lastIndex);
    }

    public static String combineFlags(Iterable<Flag> flags, boolean canCreateForwardedFlag) {
        List<String> flagNames = new ArrayList<String>();
        for (Flag flag : flags) {
            if (flag == Flag.SEEN) {
                flagNames.add("\\Seen");
            } else if (flag == Flag.DELETED) {
                flagNames.add("\\Deleted");
            } else if (flag == Flag.ANSWERED) {
                flagNames.add("\\Answered");
            } else if (flag == Flag.FLAGGED) {
                flagNames.add("\\Flagged");
            } else if (flag == Flag.FORWARDED && canCreateForwardedFlag) {
                flagNames.add("$Forwarded");
            }
        }

        return ImapUtility.join(" ", flagNames);
    }

    public static String join(String delimiter, Collection<? extends Object> tokens) {
        if (tokens == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        boolean firstTime = true;
        for (Object token: tokens) {
            if (firstTime) {
                firstTime = false;
            } else {
                sb.append(delimiter);
            }
            sb.append(token);
        }
        return sb.toString();
    }

    static void setMessageFlags(ImapList fetchList, ImapMessage message, ImapStore store) throws MessagingException {
        ImapList flags = fetchList.getKeyedList("FLAGS");
        if (flags != null) {
            for (int i = 0, count = flags.size(); i < count; i++) {
                String flag = flags.getString(i);
                if (flag.equalsIgnoreCase("\\Deleted")) {
                    message.setFlagInternal(Flag.DELETED, true);
                } else if (flag.equalsIgnoreCase("\\Answered")) {
                    message.setFlagInternal(Flag.ANSWERED, true);
                } else if (flag.equalsIgnoreCase("\\Seen")) {
                    message.setFlagInternal(Flag.SEEN, true);
                } else if (flag.equalsIgnoreCase("\\Flagged")) {
                    message.setFlagInternal(Flag.FLAGGED, true);
                } else if (flag.equalsIgnoreCase("$Forwarded")) {
                    message.setFlagInternal(Flag.FORWARDED, true);
                    /* a message contains FORWARDED FLAG -> so we can also create them */
                    store.getPermanentFlagsIndex().add(Flag.FORWARDED);
                }
            }
        }
    }

    static Long extractHighestModSeq(ImapResponse imapResponse) {
        for (Object token : imapResponse) {
            if (token instanceof ImapList) {
                ImapList list = (ImapList) token;
                if (list.size() < 2 || !(ImapResponseParser.equalsIgnoreCase(list.get(0), Responses.HIGHESTMODSEQ)
                        || ImapResponseParser.equalsIgnoreCase(list.get(0), Responses.NOMODSEQ)) ||
                        !list.isString(1)) {
                    continue;
                }

                if (ImapResponseParser.equalsIgnoreCase(list.get(0), Responses.HIGHESTMODSEQ)) {
                    return Long.parseLong(list.getString(1));
                }
            }
        }
        return null;
    }

    static List<String> extractVanishedUids(List<ImapResponse> imapResponses) {
        List<String> uids = new ArrayList<>();
        for (ImapResponse imapResponse : imapResponses) {
            if (imapResponse.getTag() == null && ImapResponseParser.equalsIgnoreCase(imapResponse.get(0), "VANISHED")) {

                //For VANISHED responses. ex : * VANISHED 505,507,510,625
                if (imapResponse.isString(1)) {
                    uids = ImapUtility.getImapSequenceValues(imapResponse.getString(1));
                }

                //For VANISHED (EARLIER) responses. ex : * VANISHED (EARLIER) 300:310,405,411
                if (imapResponse.isList(1) && imapResponse.getList(1).getString(0).equals("EARLIER")
                        && imapResponse.isString(2)) {
                    uids = ImapUtility.getImapSequenceValues(imapResponse.getString(2));
                }
            }
        }
        return uids;
    }
}
