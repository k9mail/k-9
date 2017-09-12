package com.fsck.k9.mail.store.imap;


import java.util.List;

import org.junit.Test;

import static com.fsck.k9.mail.Folder.OPEN_MODE_RW;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;


public class QresyncParamResponseTest {

    @Test
    public void fromSelectOrExamineResponse_shouldParseResponseCorrectly() throws Exception {
        SelectOrExamineResponseDataFixture dataFixture = SelectOrExamineResponseDataFixture.getDefaultInstance();
        List<ImapResponse> selectOrExamineResponse = dataFixture.createForQresyncParam(OPEN_MODE_RW, false);

        QresyncParamResponse response = QresyncParamResponse.fromSelectOrExamineResponse(selectOrExamineResponse,
                mock(ImapFolder.class));

        assertNotNull(response);
        assertEquals(response.getExpungedUids(), SelectOrExamineResponseDataFixture.TEST_EXPUNGED_UIDS);
        ImapMessage modifiedMessage = response.getModifiedMessages().get(0);
        assertEquals(modifiedMessage.getUid(), SelectOrExamineResponseDataFixture.TEST_MODIFIED_MESSAGE_UID);
        assertEquals(modifiedMessage.getFlags(), SelectOrExamineResponseDataFixture.TEST_MODIFIED_MESSAGE_FLAGS);
    }
}
