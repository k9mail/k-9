package com.fsck.k9.mail.store.imap;


import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fsck.k9.mail.Flag;
import org.junit.Before;
import org.junit.Test;

import static com.fsck.k9.mail.Folder.OPEN_MODE_RO;
import static com.fsck.k9.mail.Folder.OPEN_MODE_RW;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


public class SelectOrExamineResponseTest {

    private static final Set<Flag> TEST_PERMANENT_FLAGS = new HashSet<>(0);

    private ImapFolder folder;

    @Before
    public void setup() throws Exception {
        folder = mock(ImapFolder.class);
        when(folder.getPermanentFlags()).thenReturn(TEST_PERMANENT_FLAGS);
    }

    @Test
    public void newInstance_withSelectResponse_shouldParseAllFieldsCorrectly() throws Exception {
        List<ImapResponse> imapResponses = createMockImapResponses("1 OK [READ-WRITE] mailbox selected");
        when(folder.doesConnectionSupportQresync()).thenReturn(true);

        SelectOrExamineResponse result = SelectOrExamineResponse.newInstance(imapResponses, folder);

        assertNotNull(result);
        assertEquals(result.getUidValidity(), SelectOrExamineResponseDataFixture.TEST_CURRENT_UID_VALIDITY);
        assertEquals(result.getHighestModSeq(), SelectOrExamineResponseDataFixture.TEST_CURRENT_HIGHEST_MOD_SEQ);
        assertEquals(TEST_PERMANENT_FLAGS.size(), 4);
        assertEquals(result.canCreateKeywords(), true);
        QresyncParamResponse qresyncParamResponse = result.getQresyncParamResponse();
        assertNotNull(qresyncParamResponse);
        assertEquals(qresyncParamResponse.getExpungedUids().size(),
                SelectOrExamineResponseDataFixture.TEST_EXPUNGED_UIDS.size());
        assertEquals(qresyncParamResponse.getModifiedMessages().size(),
                SelectOrExamineResponseDataFixture.TEST_MODIFIED_MESSAGE_DATA.size());
        assertEquals(true, result.hasOpenMode());
        assertEquals(OPEN_MODE_RW, result.getOpenMode());
    }

    @Test
    public void newInstance_withSelectResponse_shouldReturnOpenModeReadWrite() throws Exception {
        List<ImapResponse> imapResponses = createMockImapResponses("x OK [READ-WRITE] Select completed.");

        SelectOrExamineResponse result = SelectOrExamineResponse.newInstance(imapResponses, folder);

        assertNotNull(result);
        assertEquals(true, result.hasOpenMode());
        assertEquals(OPEN_MODE_RW, result.getOpenMode());
    }

    @Test
    public void newInstance_withExamineResponse_shouldReturnOpenModeReadOnly() throws Exception {
        List<ImapResponse> imapResponses = createMockImapResponses("x OK [READ-ONLY] Examine completed.");

        SelectOrExamineResponse result = SelectOrExamineResponse.newInstance(imapResponses, folder);

        assertNotNull(result);
        assertEquals(true, result.hasOpenMode());
        assertEquals(OPEN_MODE_RO, result.getOpenMode());
    }

    @Test
    public void newInstance_withoutResponseCode_shouldReturnHasOpenModeFalse() throws Exception {
        List<ImapResponse> imapResponses = createMockImapResponses("x OK Select completed.");

        SelectOrExamineResponse result = SelectOrExamineResponse.newInstance(imapResponses, folder);

        assertNotNull(result);
        assertEquals(false, result.hasOpenMode());
    }

    @Test
    public void getOpenMode_withoutResponseCode_shouldThrow() throws Exception {
        List<ImapResponse> imapResponses = createMockImapResponses("x OK Select completed.");

        SelectOrExamineResponse result = SelectOrExamineResponse.newInstance(imapResponses, folder);

        assertNotNull(result);
        try {
            result.getOpenMode();
            fail("Expected exception");
        } catch (IllegalStateException ignored) {
        }
    }

    @Test
    public void newInstance_withInvalidResponseText_shouldReturnHasOpenModeFalse() throws Exception {
        List<ImapResponse> imapResponses = createMockImapResponses("x OK [()] Examine completed.");

        SelectOrExamineResponse result = SelectOrExamineResponse.newInstance(imapResponses, folder);

        assertNotNull(result);
        assertEquals(false, result.hasOpenMode());
    }

    @Test
    public void newInstance_withUnknownResponseText_shouldReturnHasOpenModeFalse() throws Exception {
        List<ImapResponse> imapResponses = createMockImapResponses("x OK [FUNKY] Examine completed.");

        SelectOrExamineResponse result = SelectOrExamineResponse.newInstance(imapResponses, folder);

        assertNotNull(result);
        assertEquals(false, result.hasOpenMode());
    }

    @Test
    public void newInstance_withUntaggedResponse_shouldReturnNull() throws Exception {
        List<ImapResponse> imapResponses = createMockImapResponses("* OK [READ-WRITE] Select completed.");

        SelectOrExamineResponse result = SelectOrExamineResponse.newInstance(imapResponses, folder);

        assertNull(result);
    }

    @Test
    public void newInstance_withoutOkResponse_shouldReturnNull() throws Exception {
        List<ImapResponse> imapResponses = createMockImapResponses("x BYE");

        SelectOrExamineResponse result = SelectOrExamineResponse.newInstance(imapResponses, folder);

        assertNull(result);
    }

    private List<ImapResponse> createMockImapResponses(String lastLine) throws IOException {
        SelectOrExamineResponseDataFixture dataFixture = SelectOrExamineResponseDataFixture.getDefaultInstance();
        return dataFixture.createWithTaggedResponse(lastLine);
    }
}
