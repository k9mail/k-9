package com.fsck.k9.mail.store.imap;


import org.junit.Test;

import static com.fsck.k9.mail.Folder.OPEN_MODE_RO;
import static com.fsck.k9.mail.Folder.OPEN_MODE_RW;
import static org.junit.Assert.assertEquals;


public class SelectOrExamineCommandTest {

    @Test
    public void createCommandString_withReadWriteMode_shouldReturnRespectiveString() throws Exception {
        SelectOrExamineCommand command = SelectOrExamineCommand.create(OPEN_MODE_RW, "\"Folder\"");

        String commandString = command.createCommandString();

        assertEquals(commandString, "SELECT \"Folder\"");
    }

    @Test
    public void createCommandString_withReadOnlyMode_shouldReturnRespectiveString() throws Exception {
        SelectOrExamineCommand command = SelectOrExamineCommand.create(OPEN_MODE_RO, "\"Folder\"");

        String commandString = command.createCommandString();

        assertEquals(commandString, "EXAMINE \"Folder\"");
    }

    @Test
    public void createCommandString_withOpenUsingCondstoreParam_shouldReturnRespectiveString() throws Exception {
        SelectOrExamineCommand command = SelectOrExamineCommand.createWithCondstoreParameter(OPEN_MODE_RW, "\"Folder\"");

        String commandString = command.createCommandString();

        assertEquals(commandString, "SELECT \"Folder\" (CONDSTORE)");
    }

    @Test
    public void createCommandString_withOpenUsingQresyncParam_shouldReturnRespectiveString() throws Exception {
        SelectOrExamineCommand command = SelectOrExamineCommand.createWithQresyncParameter(OPEN_MODE_RW, "\"Folder\"",
                123456L, 123456789L);

        String commandString = command.createCommandString();

        assertEquals(commandString, "SELECT \"Folder\" (QRESYNC (123456 123456789))");
    }
}
