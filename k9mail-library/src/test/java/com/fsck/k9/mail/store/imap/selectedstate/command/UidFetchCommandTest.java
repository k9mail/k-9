package com.fsck.k9.mail.store.imap.selectedstate.command;


import java.util.Collections;
import java.util.Map;

import com.fsck.k9.mail.FetchProfile;
import com.fsck.k9.mail.FetchProfile.Item;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.Part;
import com.fsck.k9.mail.store.imap.ImapMessage;
import org.junit.Test;

import static com.fsck.k9.mail.FetchProfile.Item.BODY_SANE;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


public class UidFetchCommandTest {

    @Test
    public void createCommandString_withFlagsFetchProfile_shouldCreateRespectiveString() {
        UidFetchCommand command = createUidFetchCommand(4096, 1L, Item.FLAGS);

        String commandString = command.createCommandString();

        assertEquals(commandString, "UID FETCH 1 (UID FLAGS)");
    }

    @Test
    public void createCommandString_withEnvelopeFetchProfile_shouldCreateRespectiveString() {
        UidFetchCommand command = createUidFetchCommand(4096, 1L, Item.ENVELOPE);

        String commandString = command.createCommandString();

        assertEquals(commandString, "UID FETCH 1 (UID INTERNALDATE RFC822.SIZE BODY.PEEK" +
                "[HEADER.FIELDS (date subject from content-type to cc reply-to message-id " +
                "references in-reply-to X-K9mail-Identity)])");
    }

    @Test
    public void createCommandString_withStructureFetchProfile_shouldCreateRespectiveString() {
        UidFetchCommand command = createUidFetchCommand(4096, 1L, Item.STRUCTURE);

        String commandString = command.createCommandString();

        assertEquals(commandString, "UID FETCH 1 (UID BODYSTRUCTURE)");
    }

    @Test
    public void createCommandString_withBodySaneFetchProfile_shouldCreateRespectiveString() {
        UidFetchCommand command = createUidFetchCommand(4096, 1L, BODY_SANE);

        String commandString = command.createCommandString();

        assertEquals(commandString, "UID FETCH 1 (UID BODY.PEEK[]<0.4096>)");
    }

    @Test
    public void createCommandString_withBodySaneFetchProfileAndNoMaximumDownloadSize_shouldCreateRespectiveString() {
        UidFetchCommand command = createUidFetchCommand(0, 1L, BODY_SANE);

        String commandString = command.createCommandString();

        assertEquals(commandString, "UID FETCH 1 (UID BODY.PEEK[])");
    }

    @Test
    public void createCommandString_withBodyFetchProfileAndNoMaximumDownloadSize_shouldCreateRespectiveString() {
        UidFetchCommand command = createUidFetchCommand(0, 1L, Item.BODY);

        String commandString = command.createCommandString();

        assertEquals(commandString, "UID FETCH 1 (UID BODY.PEEK[])");
    }

    @Test
    public void createCommandString_withTextSection_shouldCreateRespectiveString() {
        UidFetchCommand command = createUidFetchCommand(4096, 1L, createPart("TEXT"));

        String commandString = command.createCommandString();

        assertEquals(commandString, "UID FETCH 1 (UID BODY.PEEK[TEXT]<0.4096>)");
    }

    @Test
    public void createCommandString_withNonTextSection_shouldCreateRespectiveString() {
        UidFetchCommand command = createUidFetchCommand(4096, 1L, createPart("1.1"));

        String commandString = command.createCommandString();

        assertEquals(commandString, "UID FETCH 1 (UID BODY.PEEK[1.1])");
    }

    private ImapMessage createImapMessage(String uid) {
        ImapMessage message = mock(ImapMessage.class);
        when(message.getUid()).thenReturn(uid);

        return message;
    }

    private UidFetchCommand createUidFetchCommand(int maximumAutoDownloadMessageSize, Long uid, Item... items) {
        FetchProfile fetchProfile = new FetchProfile();
        Collections.addAll(fetchProfile, items);
        Map<String, Message> messageMap = Collections.singletonMap(String.valueOf(uid),
                (Message) createImapMessage(String.valueOf(uid)));
        return UidFetchCommand.createWithMessageParams(Collections.singleton(uid),
                maximumAutoDownloadMessageSize, messageMap, fetchProfile, null);
    }

    private Part createPart(String serverExtra) {
        Part part = mock(Part.class);
        when(part.getServerExtra()).thenReturn(serverExtra);
        return part;
    }

    private UidFetchCommand createUidFetchCommand(int maximumAutoDownloadMessageSize, Long uid, Part part) {
        return UidFetchCommand.createWithPartParams(Collections.singleton(uid),
                maximumAutoDownloadMessageSize, part, null);
    }
}
