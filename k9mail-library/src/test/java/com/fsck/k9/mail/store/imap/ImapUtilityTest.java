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


import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fsck.k9.mail.Flag;
import com.fsck.k9.mail.K9LibRobolectricTestRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

import static com.fsck.k9.mail.store.imap.ImapResponseHelper.createImapResponse;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@RunWith(K9LibRobolectricTestRunner.class)
public class ImapUtilityTest  {
    @Test
    public void testGetImapSequenceValues() {
        String[] expected;
        List<String> actual;

        // Test valid sets
        expected = new String[] {"1"};
        actual = ImapUtility.getImapSequenceValues("1");
        assertArrayEquals(expected, actual.toArray());

        expected = new String[] {"2147483648"};     // Integer.MAX_VALUE + 1
        actual = ImapUtility.getImapSequenceValues("2147483648");
        assertArrayEquals(expected, actual.toArray());

        expected = new String[] {"4294967295"};     // 2^32 - 1
        actual = ImapUtility.getImapSequenceValues("4294967295");
        assertArrayEquals(expected, actual.toArray());

        expected = new String[] {"1", "3", "2"};
        actual = ImapUtility.getImapSequenceValues("1,3,2");
        assertArrayEquals(expected, actual.toArray());

        expected = new String[] {"4", "5", "6"};
        actual = ImapUtility.getImapSequenceValues("4:6");
        assertArrayEquals(expected, actual.toArray());

        expected = new String[] {"9", "8", "7"};
        actual = ImapUtility.getImapSequenceValues("9:7");
        assertArrayEquals(expected, actual.toArray());

        expected = new String[] {"1", "2", "3", "4", "9", "8", "7"};
        actual = ImapUtility.getImapSequenceValues("1,2:4,9:7");
        assertArrayEquals(expected, actual.toArray());

        // Test numbers larger than Integer.MAX_VALUE (2147483647)
        expected = new String[] {"2147483646", "2147483647", "2147483648"};
        actual = ImapUtility.getImapSequenceValues("2147483646:2147483648");
        assertArrayEquals(expected, actual.toArray());

        // Test partially invalid sets
        expected = new String[] { "1", "5" };
        actual = ImapUtility.getImapSequenceValues("1,x,5");
        assertArrayEquals(expected, actual.toArray());

        expected = new String[] { "1", "2", "3" };
        actual = ImapUtility.getImapSequenceValues("a:d,1:3");
        assertArrayEquals(expected, actual.toArray());

        // Test invalid sets
        expected = new String[0];
        actual = ImapUtility.getImapSequenceValues("");
        assertArrayEquals(expected, actual.toArray());

        expected = new String[0];
        actual = ImapUtility.getImapSequenceValues(null);
        assertArrayEquals(expected, actual.toArray());

        expected = new String[0];
        actual = ImapUtility.getImapSequenceValues("a");
        assertArrayEquals(expected, actual.toArray());

        expected = new String[0];
        actual = ImapUtility.getImapSequenceValues("1:x");
        assertArrayEquals(expected, actual.toArray());

        // Test values larger than 2^32 - 1
        expected = new String[0];
        actual = ImapUtility.getImapSequenceValues("4294967296:4294967297");
        assertArrayEquals(expected, actual.toArray());

        expected = new String[0];
        actual = ImapUtility.getImapSequenceValues("4294967296");     // 2^32
        assertArrayEquals(expected, actual.toArray());
    }

    @Test public void testGetImapRangeValues() {
        String[] expected;
        List<String> actual;

        // Test valid ranges
        expected = new String[] {"1", "2", "3"};
        actual = ImapUtility.getImapRangeValues("1:3");
        assertArrayEquals(expected, actual.toArray());

        expected = new String[] {"16", "15", "14"};
        actual = ImapUtility.getImapRangeValues("16:14");
        assertArrayEquals(expected, actual.toArray());

        // Test in-valid ranges
        expected = new String[0];
        actual = ImapUtility.getImapRangeValues("");
        assertArrayEquals(expected, actual.toArray());

        expected = new String[0];
        actual = ImapUtility.getImapRangeValues(null);
        assertArrayEquals(expected, actual.toArray());

        expected = new String[0];
        actual = ImapUtility.getImapRangeValues("a");
        assertArrayEquals(expected, actual.toArray());

        expected = new String[0];
        actual = ImapUtility.getImapRangeValues("6");
        assertArrayEquals(expected, actual.toArray());

        expected = new String[0];
        actual = ImapUtility.getImapRangeValues("1:3,6");
        assertArrayEquals(expected, actual.toArray());

        expected = new String[0];
        actual = ImapUtility.getImapRangeValues("1:x");
        assertArrayEquals(expected, actual.toArray());

        expected = new String[0];
        actual = ImapUtility.getImapRangeValues("1:*");
        assertArrayEquals(expected, actual.toArray());
    }

    @Test
    public void setMessageFlags() throws Exception {
        ImapList fetchList = createImapResponse("(UID 4 MODSEQ (65402) FLAGS (\\Seen $Forwarded))");
        ImapMessage message = mock(ImapMessage.class);
        ImapStore store = mock(ImapStore.class);
        Set<Flag> permanentFlagsIndex = new HashSet<>(0);
        when(store.getPermanentFlagsIndex()).thenReturn(permanentFlagsIndex);

        ImapUtility.setMessageFlags(fetchList, message, store);

        verify(message).setFlagInternal(Flag.SEEN, true);
        verify(message).setFlagInternal(Flag.FORWARDED, true);
        assertEquals(permanentFlagsIndex, Collections.singleton(Flag.FORWARDED));
    }

    @Test
    public void extractHighestModSeq_withHighestModSeqResponse_shouldReturnRespectiveValue() throws Exception {
        ImapResponse response = createImapResponse("* OK [HIGHESTMODSEQ 715194045007]");

        Long highestModSeq = ImapUtility.extractHighestModSeq(response);

        assertNotNull(highestModSeq);
        assertEquals(highestModSeq.longValue(), 715194045007L);
    }

    @Test
    public void extractHighestModSeq_withNoModSeqResponse_shouldReturnNull() throws Exception {
        ImapResponse response = createImapResponse("* OK [NOMODSEQ] Sorry, this mailbox doesn't support modsequences");

        Long highestModSeq = ImapUtility.extractHighestModSeq(response);

        assertNull(highestModSeq);
    }

    @Test
    public void extractHighestModSeq_withoutModSeqResponse_shouldReturnNull() throws Exception {
        ImapResponse response = createImapResponse("* OK [UIDNEXT 4392] Predicted next UID");

        Long highestModSeq = ImapUtility.extractHighestModSeq(response);

        assertNull(highestModSeq);
    }

    @Test
    public void extractVanishedUids_withVanishedResponse_shouldReturnRespectiveValues() throws Exception {
        List<ImapResponse> response = singletonList(createImapResponse("* VANISHED 505,507,510,625"));

        List<String> expungedUids = ImapUtility.extractVanishedUids(response);

        assertEquals(expungedUids, asList("505", "507", "510", "625"));
    }

    @Test
    public void extractVanishedUids_withVanishedEarlierResponse_shouldReturnRespectiveValues() throws Exception {
        List<ImapResponse> response = singletonList(createImapResponse("* VANISHED (EARLIER) 505,507,510,625"));

        List<String> expungedUids = ImapUtility.extractVanishedUids(response);

        assertEquals(expungedUids, asList("505", "507", "510", "625"));
    }
}
