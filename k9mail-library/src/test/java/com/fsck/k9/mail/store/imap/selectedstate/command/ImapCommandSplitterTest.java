package com.fsck.k9.mail.store.imap.selectedstate.command;


import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.fsck.k9.mail.store.imap.selectedstate.command.FolderSelectedStateCommand.ContiguousIdGroup;
import org.junit.Test;

import static com.fsck.k9.mail.store.imap.ImapResponseHelper.createNonContiguousIdSet;
import static junit.framework.Assert.assertEquals;


public class ImapCommandSplitterTest {

    @Test
    public void splitCommand_withManyNonContiguousIds_shouldSplitCommand() throws Exception {
        Set<Long> ids = createNonContiguousIdSet(10000, 10500, 2);
        TestCommand command = TestCommand.createWithIdSet(ids);

        List<String> commands = ImapCommandSplitter.splitCommand(command, 980);

        assertEquals(commands.size(), 2);
        verifyTestCommandString(commands.get(0), createNonContiguousIdSet(10000, 10322, 2), null,
                null);
        verifyTestCommandString(commands.get(1), createNonContiguousIdSet(10324, 10500, 2), null,
                null);
    }

    @Test
    public void splitCommand_withContiguousAndNonContiguousIds_shouldGroupIdsAndSplitCommand()
            throws Exception {
        Set<Long> firstIdSet = createNonContiguousIdSet(10000, 10300, 2);
        Set<Long> secondIdSet = createNonContiguousIdSet(10301, 10399, 1);
        Set<Long> thirdIdSet = createNonContiguousIdSet(10400, 10500, 2);
        Set<Long> idSet = new HashSet<>(firstIdSet.size() + secondIdSet.size() + thirdIdSet.size());
        idSet.addAll(firstIdSet);
        idSet.addAll(secondIdSet);
        idSet.addAll(thirdIdSet);
        TestCommand command = TestCommand.createWithIdSet(idSet);
        ImapCommandSplitter.optimizeGroupings(command);

        List<String> commands = ImapCommandSplitter.splitCommand(command, 980);

        assertEquals(commands.size(), 2);
        Set<Long> firstCommandIds = createNonContiguousIdSet(10000, 10298, 2);
        firstCommandIds.addAll(createNonContiguousIdSet(10402, 10424, 2));
        verifyTestCommandString(commands.get(0), firstCommandIds, null, null);
        Set<Long> secondCommandIds = createNonContiguousIdSet(10426, 10500, 2);
        verifyTestCommandString(commands.get(1), secondCommandIds, 10300L, 10400L);
    }

    @Test
    public void optimizeGroupings_withContiguousIds_shouldGroupIdsCorrectly() {
        TestCommand command = TestCommand.createWithIdSet(createNonContiguousIdSet(1, 100, 1));

        ImapCommandSplitter.optimizeGroupings(command);

        verifyIdGroup(command, 1, 100);
    }

    @Test
    public void optimizeGroupings_withContiguousAndNonContiguousIds_shouldGroupIdsCorrectly() {
        Set<Long> idSet = createNonContiguousIdSet(1, 100, 1);
        Set<Long> finalIdSet = createNonContiguousIdSet(300, 400, 2);
        idSet.addAll(finalIdSet);
        TestCommand command = TestCommand.createWithIdSetAndGroup(idSet, 101L, 115L);

        ImapCommandSplitter.optimizeGroupings(command);

        assertEquals(command.getIdSet(), finalIdSet);
        verifyIdGroup(command, 1, 115);
    }

    private void verifyIdGroup(TestCommand command, long start, long end) {
        List<ContiguousIdGroup> idGroups = command.getIdGroups();
        assertEquals(idGroups.size(), 1);

        ContiguousIdGroup idGroup = idGroups.get(0);
        assertEquals(idGroup.getStart(), Long.valueOf(start));
        assertEquals(idGroup.getEnd(), Long.valueOf(end));
    }

    private void verifyTestCommandString(String commandString, Set<Long> ids, Long start, Long end) {
        TestCommand tempCommand = TestCommand.createWithIdSetAndGroup(ids, start, end);
        assertEquals(commandString, tempCommand.createCommandString());
    }
}
