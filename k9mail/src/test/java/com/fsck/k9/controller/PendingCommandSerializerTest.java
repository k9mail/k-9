package com.fsck.k9.controller;


import java.util.HashMap;

import com.fsck.k9.controller.MessagingControllerCommands.PendingAppend;
import com.fsck.k9.controller.MessagingControllerCommands.PendingCommand;
import com.fsck.k9.controller.MessagingControllerCommands.PendingEmptyTrash;
import com.fsck.k9.controller.MessagingControllerCommands.PendingMoveOrCopy;
import org.junit.Test;

import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;



public class PendingCommandSerializerTest {
    static final int DATABASE_ID = 123;
    static final String UID = "uid";
    static final String SOURCE_FOLDER = "source_folder";
    static final String DEST_FOLDER = "dest_folder";
    static final HashMap<String, String> UID_MAP = new HashMap<>();
    public static final boolean IS_COPY = true;

    static {
        UID_MAP.put("uid_1", "uid_other_1");
        UID_MAP.put("uid_2", "uid_other_2");
    }


    PendingCommandSerializer pendingCommandSerializer = PendingCommandSerializer.getInstance();


    @Test
    public void testSerializeDeserialize__withoutArguments() {
        PendingCommand pendingCommand = PendingEmptyTrash.create();

        String serializedCommand = pendingCommandSerializer.serialize(pendingCommand);
        PendingEmptyTrash unserializedCommand = (PendingEmptyTrash) pendingCommandSerializer.unserialize(
                DATABASE_ID, pendingCommand.getCommandName(), serializedCommand);

        assertEquals(DATABASE_ID, unserializedCommand.databaseId);
    }

    @Test
    public void testSerializeDeserialize__withArguments() {
        PendingCommand pendingCommand = PendingAppend.create(SOURCE_FOLDER, singletonList(UID));

        String serializedCommand = pendingCommandSerializer.serialize(pendingCommand);
        PendingAppend unserializedCommand = (PendingAppend) pendingCommandSerializer.unserialize(
                DATABASE_ID, pendingCommand.getCommandName(), serializedCommand);

        assertEquals(DATABASE_ID, unserializedCommand.databaseId);
        assertEquals(SOURCE_FOLDER, unserializedCommand.folder);
        assertEquals(singletonList(UID), unserializedCommand.uids);
    }

    @Test
    public void testSerializeDeserialize__withComplexArguments() {
        PendingCommand pendingCommand = PendingMoveOrCopy.create(
                SOURCE_FOLDER, DEST_FOLDER, IS_COPY, UID_MAP);

        String serializedCommand = pendingCommandSerializer.serialize(pendingCommand);
        PendingMoveOrCopy unserializedCommand = (PendingMoveOrCopy) pendingCommandSerializer.unserialize(
                DATABASE_ID, pendingCommand.getCommandName(), serializedCommand);

        assertEquals(DATABASE_ID, unserializedCommand.databaseId);
        assertEquals(SOURCE_FOLDER, unserializedCommand.srcFolder);
        assertEquals(DEST_FOLDER, unserializedCommand.destFolder);
        assertEquals(UID_MAP, unserializedCommand.newUidMap);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDeserialize__withUnknownCommandName__shouldFail() {
        PendingCommand pendingCommand = PendingEmptyTrash.create();

        String serializedCommand = pendingCommandSerializer.serialize(pendingCommand);
        pendingCommandSerializer.unserialize(DATABASE_ID,  "BAD_COMMAND_NAME", serializedCommand);
    }

}
