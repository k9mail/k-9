package com.fsck.k9.fragment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Future;

import android.app.Activity;
import android.app.DialogFragment;
import android.app.Fragment;
import android.app.LoaderManager;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.Loader;
import android.database.Cursor;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.widget.SwipeRefreshLayout;
import android.text.TextUtils;
import timber.log.Timber;
import android.view.ActionMode;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.fsck.k9.Account;
import com.fsck.k9.Account.SortType;
import com.fsck.k9.BuildConfig;
import com.fsck.k9.K9;
import com.fsck.k9.Preferences;
import com.fsck.k9.R;
import com.fsck.k9.activity.ActivityListener;
import com.fsck.k9.activity.ChooseFolder;
import com.fsck.k9.activity.FolderInfoHolder;
import com.fsck.k9.activity.MessageReference;
import com.fsck.k9.activity.misc.ContactPictureLoader;
import com.fsck.k9.cache.EmailProviderCache;
import com.fsck.k9.controller.MessagingController;
import com.fsck.k9.fragment.ConfirmationDialogFragment.ConfirmationDialogFragmentListener;
import com.fsck.k9.fragment.MessageListFragmentComparators.ArrivalComparator;
import com.fsck.k9.fragment.MessageListFragmentComparators.AttachmentComparator;
import com.fsck.k9.fragment.MessageListFragmentComparators.ComparatorChain;
import com.fsck.k9.fragment.MessageListFragmentComparators.DateComparator;
import com.fsck.k9.fragment.MessageListFragmentComparators.FlaggedComparator;
import com.fsck.k9.fragment.MessageListFragmentComparators.ReverseComparator;
import com.fsck.k9.fragment.MessageListFragmentComparators.ReverseIdComparator;
import com.fsck.k9.fragment.MessageListFragmentComparators.SenderComparator;
import com.fsck.k9.fragment.MessageListFragmentComparators.SubjectComparator;
import com.fsck.k9.fragment.MessageListFragmentComparators.UnreadComparator;
import com.fsck.k9.helper.ContactPicture;
import com.fsck.k9.helper.MergeCursorWithUniqueId;
import com.fsck.k9.helper.MessageHelper;
import com.fsck.k9.helper.Utility;
import com.fsck.k9.mail.Flag;
import com.fsck.k9.mail.Folder;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mailstore.LocalFolder;
import com.fsck.k9.preferences.StorageEditor;
import com.fsck.k9.provider.EmailProvider;
import com.fsck.k9.provider.EmailProvider.MessageColumns;
import com.fsck.k9.provider.EmailProvider.SpecialColumns;
import com.fsck.k9.search.ConditionsTreeNode;
import com.fsck.k9.search.LocalSearch;
import com.fsck.k9.search.SearchSpecification;
import com.fsck.k9.search.SearchSpecification.SearchCondition;
import com.fsck.k9.search.SearchSpecification.SearchField;
import com.fsck.k9.search.SqlQueryBuilder;

import static com.fsck.k9.fragment.MLFProjectionInfo.ACCOUNT_UUID_COLUMN;
import static com.fsck.k9.fragment.MLFProjectionInfo.FLAGGED_COLUMN;
import static com.fsck.k9.fragment.MLFProjectionInfo.FOLDER_NAME_COLUMN;
import static com.fsck.k9.fragment.MLFProjectionInfo.ID_COLUMN;
import static com.fsck.k9.fragment.MLFProjectionInfo.PROJECTION;
import static com.fsck.k9.fragment.MLFProjectionInfo.READ_COLUMN;
import static com.fsck.k9.fragment.MLFProjectionInfo.SUBJECT_COLUMN;
import static com.fsck.k9.fragment.MLFProjectionInfo.THREADED_PROJECTION;
import static com.fsck.k9.fragment.MLFProjectionInfo.THREAD_COUNT_COLUMN;
import static com.fsck.k9.fragment.MLFProjectionInfo.THREAD_ROOT_COLUMN;
import static com.fsck.k9.fragment.MLFProjectionInfo.UID_COLUMN;


public class MessageListFragment extends Fragment implements OnItemClickListener,
        ConfirmationDialogFragmentListener, LoaderCallbacks<Cursor> {

    public static MessageListFragment newInstance(
            LocalSearch search, boolean isThreadDisplay, boolean threadedList) {
        MessageListFragment fragment = new MessageListFragment();
        Bundle args = new Bundle();
        args.putParcelable(ARG_SEARCH, search);
        args.putBoolean(ARG_IS_THREAD_DISPLAY, isThreadDisplay);
        args.putBoolean(ARG_THREADED_LIST, threadedList);
        fragment.setArguments(args);
        return fragment;
    }

    private static final int ACTIVITY_CHOOSE_FOLDER_MOVE = 1;
    private static final int ACTIVITY_CHOOSE_FOLDER_COPY = 2;

    private static final String ARG_SEARCH = "searchObject";
    private static final String ARG_THREADED_LIST = "showingThreadedList";
    private static final String ARG_IS_THREAD_DISPLAY = "isThreadedDisplay";

    private static final String STATE_SELECTED_MESSAGES = "selectedMessages";
    private static final String STATE_ACTIVE_MESSAGE = "activeMessage";
    private static final String STATE_REMOTE_SEARCH_PERFORMED = "remoteSearchPerformed";
    private static final String STATE_MESSAGE_LIST = "listState";

    /**
     * Maps a {@link SortType} to a {@link Comparator} implementation.
     */
    private static final Map<SortType, Comparator<Cursor>> SORT_COMPARATORS;

    static {
        // fill the mapping at class time loading

        final Map<SortType, Comparator<Cursor>> map =
                new EnumMap<>(SortType.class);
        map.put(SortType.SORT_ATTACHMENT, new AttachmentComparator());
        map.put(SortType.SORT_DATE, new DateComparator());
        map.put(SortType.SORT_ARRIVAL, new ArrivalComparator());
        map.put(SortType.SORT_FLAGGED, new FlaggedComparator());
        map.put(SortType.SORT_SUBJECT, new SubjectComparator());
        map.put(SortType.SORT_SENDER, new SenderComparator());
        map.put(SortType.SORT_UNREAD, new UnreadComparator());

        // make it immutable to prevent accidental alteration (content is immutable already)
        SORT_COMPARATORS = Collections.unmodifiableMap(map);
    }

    ListView listView;
    private SwipeRefreshLayout swipeRefreshLayout;
    Parcelable savedListState;

    int previewLines = 0;


    private MessageListAdapter adapter;
    private View footerView;
    private FolderInfoHolder currentFolder;
    private LayoutInflater layoutInflater;
    private MessagingController messagingController;

    private Account account;
    private String[] accountUuids;
    private int unreadMessageCount = 0;

    private Cursor[] cursors;
    private boolean[] cursorValid;
    int uniqueIdColumn;

    /**
     * Stores the name of the folder that we want to open as soon as possible after load.
     */
    private String folderName;

    private boolean remoteSearchPerformed = false;
    private Future<?> remoteSearchFuture = null;
    private List<Message> extraSearchResults;

    private String title;
    private LocalSearch search = null;
    private boolean singleAccountMode;
    private boolean singleFolderMode;
    private boolean allAccounts;

    private final MessageListHandler handler = new MessageListHandler(this);

    private SortType sortType = SortType.SORT_DATE;
    private boolean sortAscending = true;
    private boolean sortDateAscending = false;
    boolean senderAboveSubject = false;
    boolean checkboxes = true;
    boolean stars = true;

    private int selectedCount = 0;
    Set<Long> selected = new HashSet<>();
    private ActionMode actionMode;
    private Boolean hasConnectivity;
    /**
     * Relevant messages for the current context when we have to remember the chosen messages
     * between user interactions (e.g. selecting a folder for move operation).
     */
    private List<MessageReference> activeMessages;
    /* package visibility for faster inner class access */
    MessageHelper messageHelper;
    private final ActionModeCallback actionModeCallback = new ActionModeCallback();
    MessageListFragmentListener fragmentListener;
    boolean showingThreadedList;
    private boolean isThreadDisplay;
    private Context context;
    private final ActivityListener activityListener = new MessageListActivityListener();
    private Preferences preferences;
    private boolean loaderJustInitialized;
    MessageReference activeMessage;
    /**
     * {@code true} after {@link #onCreate(Bundle)} was executed. Used in {@link #updateTitle()} to
     * make sure we don't access member variables before initialization is complete.
     */
    private boolean initialized = false;
    ContactPictureLoader contactsPictureLoader;
    private LocalBroadcastManager localBroadcastManager;
    private BroadcastReceiver cacheBroadcastReceiver;
    private IntentFilter cacheIntentFilter;
    /**
     * Stores the unique ID of the message the context menu was opened for.
     *
     * We have to save this because the message list might change between the time the menu was
     * opened and when the user clicks on a menu item. When this happens the 'adapter position' that
     * is accessible via the {@code ContextMenu} object might correspond to another list item and we
     * would end up using/modifying the wrong message.
     *
     * The value of this field is {@code 0} when no context menu is currently open.
     */
    private long contextMenuUniqueId = 0;




    /**
     * @return The comparator to use to display messages in an ordered
     *         fashion. Never {@code null}.
     */
    private Comparator<Cursor> getComparator() {
        final List<Comparator<Cursor>> chain =
                new ArrayList<>(3 /* we add 3 comparators at most */);

        // Add the specified comparator
        final Comparator<Cursor> comparator = SORT_COMPARATORS.get(sortType);
        if (sortAscending) {
            chain.add(comparator);
        } else {
            chain.add(new ReverseComparator<>(comparator));
        }

        // Add the date comparator if not already specified
        if (sortType != SortType.SORT_DATE && sortType != SortType.SORT_ARRIVAL) {
            final Comparator<Cursor> dateComparator = SORT_COMPARATORS.get(SortType.SORT_DATE);
            if (sortDateAscending) {
                chain.add(dateComparator);
            } else {
                chain.add(new ReverseComparator<>(dateComparator));
            }
        }

        // Add the id comparator
        chain.add(new ReverseIdComparator());

        // Build the comparator chain
        return new ComparatorChain<>(chain);
    }

    void folderLoading(String folder, boolean loading) {
        if (currentFolder != null && currentFolder.name.equals(folder)) {
            currentFolder.loading = loading;
        }
        updateMoreMessagesOfCurrentFolder();
        updateFooterView();
    }

    public void updateTitle() {
        if (!initialized) {
            return;
        }

        setWindowTitle();
        if (!search.isManualSearch()) {
            setWindowProgress();
        }
    }

    private void setWindowProgress() {
        int level = Window.PROGRESS_END;

        if (currentFolder != null && currentFolder.loading && activityListener.getFolderTotal() > 0) {
            int divisor = activityListener.getFolderTotal();
            if (divisor != 0) {
                level = (Window.PROGRESS_END / divisor) * (activityListener.getFolderCompleted()) ;
                if (level > Window.PROGRESS_END) {
                    level = Window.PROGRESS_END;
                }
            }
        }

        fragmentListener.setMessageListProgress(level);
    }

    private void setWindowTitle() {
        // regular folder content display
        if (!isManualSearch() && singleFolderMode) {
            Activity activity = getActivity();
            String displayName = FolderInfoHolder.getDisplayName(activity, account,
                    folderName);

            fragmentListener.setMessageListTitle(displayName);

            String operation = activityListener.getOperation(activity);
            if (operation.length() < 1) {
                fragmentListener.setMessageListSubTitle(account.getEmail());
            } else {
                fragmentListener.setMessageListSubTitle(operation);
            }
        } else {
            // query result display.  This may be for a search folder as opposed to a user-initiated search.
            if (title != null) {
                // This was a search folder; the search folder has overridden our title.
                fragmentListener.setMessageListTitle(title);
            } else {
                // This is a search result; set it to the default search result line.
                fragmentListener.setMessageListTitle(getString(R.string.search_results));
            }

            fragmentListener.setMessageListSubTitle(null);
        }

        // set unread count
        if (unreadMessageCount <= 0) {
            fragmentListener.setUnreadCount(0);
        } else {
            if (!singleFolderMode && title == null) {
                // The unread message count is easily confused
                // with total number of messages in the search result, so let's hide it.
                fragmentListener.setUnreadCount(0);
            } else {
                fragmentListener.setUnreadCount(unreadMessageCount);
            }
        }
    }

    void progress(final boolean progress) {
        fragmentListener.enableActionBarProgress(progress);
        if (swipeRefreshLayout != null && !progress) {
            swipeRefreshLayout.setRefreshing(false);
        }
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (view == footerView) {
            if (currentFolder != null && !search.isManualSearch() && currentFolder.moreMessages) {

                messagingController.loadMoreMessages(account, folderName, null);

            } else if (currentFolder != null && isRemoteSearch() &&
                    extraSearchResults != null && extraSearchResults.size() > 0) {

                int numResults = extraSearchResults.size();
                int limit = account.getRemoteSearchNumResults();

                List<Message> toProcess = extraSearchResults;

                if (limit > 0 && numResults > limit) {
                    toProcess = toProcess.subList(0, limit);
                    extraSearchResults = extraSearchResults.subList(limit,
                            extraSearchResults.size());
                } else {
                    extraSearchResults = null;
                    updateFooter(null);
                }

                messagingController.loadSearchResults(account, currentFolder.name, toProcess, activityListener);
            }

            return;
        }

        Cursor cursor = (Cursor) parent.getItemAtPosition(position);
        if (cursor == null) {
            return;
        }

        if (selectedCount > 0) {
            toggleMessageSelect(position);
        } else {
            if (showingThreadedList && cursor.getInt(THREAD_COUNT_COLUMN) > 1) {
                Account account = getAccountFromCursor(cursor);
                String folderName = cursor.getString(FOLDER_NAME_COLUMN);

                // If threading is enabled and this item represents a thread, display the thread contents.
                long rootId = cursor.getLong(THREAD_ROOT_COLUMN);
                fragmentListener.showThread(account, folderName, rootId);
            } else {
                // This item represents a message; just display the message.
                openMessageAtPosition(listViewToAdapterPosition(position));
            }
        }
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        context = activity.getApplicationContext();

        try {
            fragmentListener = (MessageListFragmentListener) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.getClass() +
                    " must implement MessageListFragmentListener");
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Context appContext = getActivity().getApplicationContext();

        preferences = Preferences.getPreferences(appContext);
        messagingController = MessagingController.getInstance(getActivity().getApplication());

        previewLines = K9.messageListPreviewLines();
        checkboxes = K9.messageListCheckboxes();
        stars = K9.messageListStars();

        if (K9.showContactPicture()) {
            contactsPictureLoader = ContactPicture.getContactPictureLoader(getActivity());
        }

        restoreInstanceState(savedInstanceState);
        decodeArguments();

        createCacheBroadcastReceiver(appContext);

        initialized = true;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {

        layoutInflater = inflater;

        View view = inflater.inflate(R.layout.message_list_fragment, container, false);

        initializePullToRefresh(view);

        initializeLayout();
        listView.setVerticalFadingEdgeEnabled(false);

        return view;
    }

    @Override
    public void onDestroyView() {
        savedListState = listView.onSaveInstanceState();
        super.onDestroyView();
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        messageHelper = MessageHelper.getInstance(getActivity());

        initializeMessageList();

        // This needs to be done before initializing the cursor loader below
        initializeSortSettings();

        loaderJustInitialized = true;
        LoaderManager loaderManager = getLoaderManager();
        int len = accountUuids.length;
        cursors = new Cursor[len];
        cursorValid = new boolean[len];
        for (int i = 0; i < len; i++) {
            loaderManager.initLoader(i, null, this);
            cursorValid[i] = false;
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        saveSelectedMessages(outState);
        saveListState(outState);

        outState.putBoolean(STATE_REMOTE_SEARCH_PERFORMED, remoteSearchPerformed);
        if (activeMessage != null) {
            outState.putString(STATE_ACTIVE_MESSAGE, activeMessage.toIdentityString());
        }
    }

    /**
     * Restore the state of a previous {@link MessageListFragment} instance.
     *
     * @see #onSaveInstanceState(Bundle)
     */
    private void restoreInstanceState(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            return;
        }

        restoreSelectedMessages(savedInstanceState);

        remoteSearchPerformed = savedInstanceState.getBoolean(STATE_REMOTE_SEARCH_PERFORMED);
        savedListState = savedInstanceState.getParcelable(STATE_MESSAGE_LIST);
        String messageReferenceString = savedInstanceState.getString(STATE_ACTIVE_MESSAGE);
        activeMessage = MessageReference.parse(messageReferenceString);
    }

    /**
     * Write the unique IDs of selected messages to a {@link Bundle}.
     */
    private void saveSelectedMessages(Bundle outState) {
        long[] selected = new long[this.selected.size()];
        int i = 0;
        for (Long id : this.selected) {
            selected[i++] = id;
        }
        outState.putLongArray(STATE_SELECTED_MESSAGES, selected);
    }

    /**
     * Restore selected messages from a {@link Bundle}.
     */
    private void restoreSelectedMessages(Bundle savedInstanceState) {
        long[] selected = savedInstanceState.getLongArray(STATE_SELECTED_MESSAGES);
        if (selected != null) {
            for (long id : selected) {
                this.selected.add(id);
            }
        }
    }

    private void saveListState(Bundle outState) {
        if (savedListState != null) {
            // The previously saved state was never restored, so just use that.
            outState.putParcelable(STATE_MESSAGE_LIST, savedListState);
        } else if (listView != null) {
            outState.putParcelable(STATE_MESSAGE_LIST, listView.onSaveInstanceState());
        }
    }

    private void initializeSortSettings() {
        if (singleAccountMode) {
            sortType = account.getSortType();
            sortAscending = account.isSortAscending(sortType);
            sortDateAscending = account.isSortAscending(SortType.SORT_DATE);
        } else {
            sortType = K9.getSortType();
            sortAscending = K9.isSortAscending(sortType);
            sortDateAscending = K9.isSortAscending(SortType.SORT_DATE);
        }
    }

    private void decodeArguments() {
        Bundle args = getArguments();

        showingThreadedList = args.getBoolean(ARG_THREADED_LIST, false);
        isThreadDisplay = args.getBoolean(ARG_IS_THREAD_DISPLAY, false);
        search = args.getParcelable(ARG_SEARCH);
        title = search.getName();

        String[] accountUuids = search.getAccountUuids();

        singleAccountMode = false;
        if (accountUuids.length == 1 && !search.searchAllAccounts()) {
            singleAccountMode = true;
            account = preferences.getAccount(accountUuids[0]);
        }

        singleFolderMode = false;
        if (singleAccountMode && (search.getFolderNames().size() == 1)) {
            singleFolderMode = true;
            folderName = search.getFolderNames().get(0);
            currentFolder = getFolderInfoHolder(folderName, account);
        }

        allAccounts = false;
        if (singleAccountMode) {
            this.accountUuids = new String[] { account.getUuid() };
        } else {
            if (accountUuids.length == 1 &&
                    accountUuids[0].equals(SearchSpecification.ALL_ACCOUNTS)) {
                allAccounts = true;

                List<Account> accounts = preferences.getAccounts();

                this.accountUuids = new String[accounts.size()];
                for (int i = 0, len = accounts.size(); i < len; i++) {
                    this.accountUuids[i] = accounts.get(i).getUuid();
                }

                if (this.accountUuids.length == 1) {
                    singleAccountMode = true;
                    account = accounts.get(0);
                }
            } else {
                this.accountUuids = accountUuids;
            }
        }
    }

    private void initializeMessageList() {
        adapter = new MessageListAdapter(this);

        if (folderName != null) {
            currentFolder = getFolderInfoHolder(folderName, account);
        }

        if (singleFolderMode) {
            listView.addFooterView(getFooterView(listView));
            updateFooterView();
        }

        listView.setAdapter(adapter);
    }

    private void createCacheBroadcastReceiver(Context appContext) {
        localBroadcastManager = LocalBroadcastManager.getInstance(appContext);

        cacheBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                adapter.notifyDataSetChanged();
            }
        };

        cacheIntentFilter = new IntentFilter(EmailProviderCache.ACTION_CACHE_UPDATED);
    }

    private FolderInfoHolder getFolderInfoHolder(String folderName, Account account) {
        try {
            LocalFolder localFolder = MlfUtils.getOpenFolder(folderName, account);
            return new FolderInfoHolder(context, localFolder, account);
        } catch (MessagingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        localBroadcastManager.unregisterReceiver(cacheBroadcastReceiver);
        activityListener.onPause(getActivity());
        messagingController.removeListener(activityListener);
    }

    /**
     * On resume we refresh messages for the folder that is currently open.
     * This guarantees that things like unread message count and read status
     * are updated.
     */
    @Override
    public void onResume() {
        super.onResume();

        senderAboveSubject = K9.messageListSenderAboveSubject();

        if (!loaderJustInitialized) {
            restartLoader();
        } else {
            loaderJustInitialized = false;
        }

        // Check if we have connectivity.  Cache the value.
        if (hasConnectivity == null) {
            hasConnectivity = Utility.hasConnectivity(getActivity().getApplication());
        }

        localBroadcastManager.registerReceiver(cacheBroadcastReceiver, cacheIntentFilter);
        activityListener.onResume(getActivity());
        messagingController.addListener(activityListener);

        //Cancel pending new mail notifications when we open an account
        List<Account> accountsWithNotification;

        Account account = this.account;
        if (account != null) {
            accountsWithNotification = Collections.singletonList(account);
        } else {
            accountsWithNotification = preferences.getAccounts();
        }

        for (Account accountWithNotification : accountsWithNotification) {
            messagingController.cancelNotificationsForAccount(accountWithNotification);
        }

        if (this.account != null && folderName != null && !search.isManualSearch()) {
            messagingController.getFolderUnreadMessageCount(this.account, folderName, activityListener);
        }

        updateTitle();
    }

    private void restartLoader() {
        if (cursorValid == null) {
            return;
        }

        // Refresh the message list
        LoaderManager loaderManager = getLoaderManager();
        for (int i = 0; i < accountUuids.length; i++) {
            loaderManager.restartLoader(i, null, this);
            cursorValid[i] = false;
        }
    }

    private void initializePullToRefresh(View layout) {
        swipeRefreshLayout = (SwipeRefreshLayout) layout.findViewById(R.id.swiperefresh);
        listView = (ListView) layout.findViewById(R.id.message_list);

        if (isRemoteSearchAllowed()) {
            swipeRefreshLayout.setOnRefreshListener(
                    new SwipeRefreshLayout.OnRefreshListener() {
                        @Override
                        public void onRefresh() {
                            onRemoteSearchRequested();
                        }
                    }
            );
        } else if (isCheckMailSupported()) {
            swipeRefreshLayout.setOnRefreshListener(
                    new SwipeRefreshLayout.OnRefreshListener() {
                        @Override
                        public void onRefresh() {
                            checkMail();
                        }
                    }
            );
        }

        // Disable pull-to-refresh until the message list has been loaded
        swipeRefreshLayout.setEnabled(false);
    }

    private void initializeLayout() {
        listView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        listView.setLongClickable(true);
        listView.setFastScrollEnabled(true);
        listView.setScrollingCacheEnabled(false);
        listView.setOnItemClickListener(this);

        registerForContextMenu(listView);
    }

    public void onCompose() {
        if (!singleAccountMode) {
            /*
             * If we have a query string, we don't have an account to let
             * compose start the default action.
             */
            fragmentListener.onCompose(null);
        } else {
            fragmentListener.onCompose(account);
        }
    }

    private void onReply(MessageReference messageReference) {
        fragmentListener.onReply(messageReference);
    }

    private void onReplyAll(MessageReference messageReference) {
        fragmentListener.onReplyAll(messageReference);
    }

    private void onForward(MessageReference messageReference) {
        fragmentListener.onForward(messageReference);
    }

    private void onResendMessage(MessageReference messageReference) {
        fragmentListener.onResendMessage(messageReference);
    }

    public void changeSort(SortType sortType) {
        Boolean sortAscending = (this.sortType == sortType) ? !this.sortAscending : null;
        changeSort(sortType, sortAscending);
    }

    /**
     * User has requested a remote search.  Setup the bundle and start the intent.
     */
    private void onRemoteSearchRequested() {
        String searchAccount;
        String searchFolder;

        searchAccount = account.getUuid();
        searchFolder = currentFolder.name;

        String queryString = search.getRemoteSearchArguments();

        remoteSearchPerformed = true;
        remoteSearchFuture = messagingController.searchRemoteMessages(searchAccount, searchFolder,
                queryString, null, null, activityListener);

        swipeRefreshLayout.setEnabled(false);

        fragmentListener.remoteSearchStarted();
    }

    /**
     * Change the sort type and sort order used for the message list.
     *
     * @param sortType
     *         Specifies which field to use for sorting the message list.
     * @param sortAscending
     *         Specifies the sort order. If this argument is {@code null} the default search order
     *         for the sort type is used.
     */
    // FIXME: Don't save the changes in the UI thread
    private void changeSort(SortType sortType, Boolean sortAscending) {
        this.sortType = sortType;

        Account account = this.account;

        if (account != null) {
            account.setSortType(this.sortType);

            if (sortAscending == null) {
                this.sortAscending = account.isSortAscending(this.sortType);
            } else {
                this.sortAscending = sortAscending;
            }
            account.setSortAscending(this.sortType, this.sortAscending);
            sortDateAscending = account.isSortAscending(SortType.SORT_DATE);

            account.save(preferences);
        } else {
            K9.setSortType(this.sortType);

            if (sortAscending == null) {
                this.sortAscending = K9.isSortAscending(this.sortType);
            } else {
                this.sortAscending = sortAscending;
            }
            K9.setSortAscending(this.sortType, this.sortAscending);
            sortDateAscending = K9.isSortAscending(SortType.SORT_DATE);

            StorageEditor editor = preferences.getStorage().edit();
            K9.save(editor);
            editor.commit();
        }

        reSort();
    }

    private void reSort() {
        int toastString = sortType.getToast(sortAscending);

        Toast toast = Toast.makeText(getActivity(), toastString, Toast.LENGTH_SHORT);
        toast.show();

        LoaderManager loaderManager = getLoaderManager();
        for (int i = 0, len = accountUuids.length; i < len; i++) {
            loaderManager.restartLoader(i, null, this);
        }
    }

    public void onCycleSort() {
        SortType[] sorts = SortType.values();
        int curIndex = 0;

        for (int i = 0; i < sorts.length; i++) {
            if (sorts[i] == sortType) {
                curIndex = i;
                break;
            }
        }

        curIndex++;

        if (curIndex == sorts.length) {
            curIndex = 0;
        }

        changeSort(sorts[curIndex]);
    }

    private void onDelete(MessageReference message) {
        onDelete(Collections.singletonList(message));
    }

    private void onDelete(List<MessageReference> messages) {
        if (K9.confirmDelete()) {
            // remember the message selection for #onCreateDialog(int)
            activeMessages = messages;
            showDialog(R.id.dialog_confirm_delete);
        } else {
            onDeleteConfirmed(messages);
        }
    }

    private void onDeleteConfirmed(List<MessageReference> messages) {
        if (showingThreadedList) {
            messagingController.deleteThreads(messages);
        } else {
            messagingController.deleteMessages(messages, null);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK) {
            return;
        }

        switch (requestCode) {
            case ACTIVITY_CHOOSE_FOLDER_MOVE:
            case ACTIVITY_CHOOSE_FOLDER_COPY: {
                if (data == null) {
                    return;
                }

                final String destFolderName = data.getStringExtra(ChooseFolder.EXTRA_NEW_FOLDER);
                final List<MessageReference> messages = activeMessages;

                if (destFolderName != null) {

                    activeMessages = null; // don't need it any more

                    if (messages.size() > 0) {
                        MlfUtils.setLastSelectedFolderName(preferences, messages, destFolderName);
                    }

                    switch (requestCode) {
                        case ACTIVITY_CHOOSE_FOLDER_MOVE:
                            move(messages, destFolderName);
                            break;

                        case ACTIVITY_CHOOSE_FOLDER_COPY:
                            copy(messages, destFolderName);
                            break;
                        }
                }
                break;
            }
        }
    }

    public void onExpunge() {
        if (currentFolder != null) {
            onExpunge(account, currentFolder.name);
        }
    }

    private void onExpunge(final Account account, String folderName) {
        messagingController.expunge(account, folderName);
    }

    private void showDialog(int dialogId) {
        DialogFragment fragment;
        switch (dialogId) {
            case R.id.dialog_confirm_spam: {
                String title = getString(R.string.dialog_confirm_spam_title);

                int selectionSize = activeMessages.size();
                String message = getResources().getQuantityString(
                        R.plurals.dialog_confirm_spam_message, selectionSize, selectionSize);

                String confirmText = getString(R.string.dialog_confirm_spam_confirm_button);
                String cancelText = getString(R.string.dialog_confirm_spam_cancel_button);

                fragment = ConfirmationDialogFragment.newInstance(dialogId, title, message,
                        confirmText, cancelText);
                break;
            }
            case R.id.dialog_confirm_delete: {
                String title = getString(R.string.dialog_confirm_delete_title);

                int selectionSize = activeMessages.size();
                String message = getResources().getQuantityString(
                        R.plurals.dialog_confirm_delete_messages, selectionSize,
                        selectionSize);

                String confirmText = getString(R.string.dialog_confirm_delete_confirm_button);
                String cancelText = getString(R.string.dialog_confirm_delete_cancel_button);

                fragment = ConfirmationDialogFragment.newInstance(dialogId, title, message,
                        confirmText, cancelText);
                break;
            }
            case R.id.dialog_confirm_mark_all_as_read: {
                String title = getString(R.string.dialog_confirm_mark_all_as_read_title);
                String message = getString(R.string.dialog_confirm_mark_all_as_read_message);

                String confirmText = getString(R.string.dialog_confirm_mark_all_as_read_confirm_button);
                String cancelText = getString(R.string.dialog_confirm_mark_all_as_read_cancel_button);

                fragment = ConfirmationDialogFragment.newInstance(dialogId, title, message, confirmText, cancelText);
                break;
            }
            default: {
                throw new RuntimeException("Called showDialog(int) with unknown dialog id.");
            }
        }

        fragment.setTargetFragment(this, dialogId);
        fragment.show(getFragmentManager(), getDialogTag(dialogId));
    }

    private String getDialogTag(int dialogId) {
        return "dialog-" + dialogId;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        switch (itemId) {
        case R.id.set_sort_date: {
            changeSort(SortType.SORT_DATE);
            return true;
        }
        case R.id.set_sort_arrival: {
            changeSort(SortType.SORT_ARRIVAL);
            return true;
        }
        case R.id.set_sort_subject: {
            changeSort(SortType.SORT_SUBJECT);
            return true;
        }
        case R.id.set_sort_sender: {
            changeSort(SortType.SORT_SENDER);
            return true;
        }
        case R.id.set_sort_flag: {
            changeSort(SortType.SORT_FLAGGED);
            return true;
        }
        case R.id.set_sort_unread: {
            changeSort(SortType.SORT_UNREAD);
            return true;
        }
        case R.id.set_sort_attach: {
            changeSort(SortType.SORT_ATTACHMENT);
            return true;
        }
        case R.id.select_all: {
            selectAll();
            return true;
        }
        }

        if (!singleAccountMode) {
            // None of the options after this point are "safe" for search results
            //TODO: This is not true for "unread" and "starred" searches in regular folders
            return false;
        }

        switch (itemId) {
        case R.id.send_messages: {
            onSendPendingMessages();
            return true;
        }
        case R.id.expunge: {
            if (currentFolder != null) {
                onExpunge(account, currentFolder.name);
            }
            return true;
        }
        default: {
            return super.onOptionsItemSelected(item);
        }
        }
    }

    public void onSendPendingMessages() {
        messagingController.sendPendingMessages(account, null);
    }

    @Override
    public boolean onContextItemSelected(android.view.MenuItem item) {
        if (contextMenuUniqueId == 0) {
            return false;
        }

        int adapterPosition = getPositionForUniqueId(contextMenuUniqueId);
        if (adapterPosition == AdapterView.INVALID_POSITION) {
            return false;
        }

        switch (item.getItemId()) {
            case R.id.deselect:
            case R.id.select: {
                toggleMessageSelectWithAdapterPosition(adapterPosition);
                break;
            }
            case R.id.reply: {
                onReply(getMessageAtPosition(adapterPosition));
                break;
            }
            case R.id.reply_all: {
                onReplyAll(getMessageAtPosition(adapterPosition));
                break;
            }
            case R.id.forward: {
                onForward(getMessageAtPosition(adapterPosition));
                break;
            }
            case R.id.send_again: {
                onResendMessage(getMessageAtPosition(adapterPosition));
                selectedCount = 0;
                break;
            }
            case R.id.same_sender: {
                Cursor cursor = (Cursor) adapter.getItem(adapterPosition);
                String senderAddress = MlfUtils.getSenderAddressFromCursor(cursor);
                if (senderAddress != null) {
                    fragmentListener.showMoreFromSameSender(senderAddress);
                }
                break;
            }
            case R.id.delete: {
                MessageReference message = getMessageAtPosition(adapterPosition);
                onDelete(message);
                break;
            }
            case R.id.mark_as_read: {
                setFlag(adapterPosition, Flag.SEEN, true);
                break;
            }
            case R.id.mark_as_unread: {
                setFlag(adapterPosition, Flag.SEEN, false);
                break;
            }
            case R.id.flag: {
                setFlag(adapterPosition, Flag.FLAGGED, true);
                break;
            }
            case R.id.unflag: {
                setFlag(adapterPosition, Flag.FLAGGED, false);
                break;
            }

            // only if the account supports this
            case R.id.archive: {
                onArchive(getMessageAtPosition(adapterPosition));
                break;
            }
            case R.id.spam: {
                onSpam(getMessageAtPosition(adapterPosition));
                break;
            }
            case R.id.move: {
                onMove(getMessageAtPosition(adapterPosition));
                break;
            }
            case R.id.copy: {
                onCopy(getMessageAtPosition(adapterPosition));
                break;
            }

            // debug options
            case R.id.debug_delete_locally: {
                onDebugClearLocally(getMessageAtPosition(adapterPosition));
                break;
            }
        }

        contextMenuUniqueId = 0;
        return true;
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        Cursor cursor = (Cursor) listView.getItemAtPosition(info.position);

        if (cursor == null) {
            return;
        }

        getActivity().getMenuInflater().inflate(R.menu.message_list_item_context, menu);
        menu.findItem(R.id.debug_delete_locally).setVisible(BuildConfig.DEBUG);

        contextMenuUniqueId = cursor.getLong(uniqueIdColumn);
        Account account = getAccountFromCursor(cursor);

        String subject = cursor.getString(SUBJECT_COLUMN);
        boolean read = (cursor.getInt(READ_COLUMN) == 1);
        boolean flagged = (cursor.getInt(FLAGGED_COLUMN) == 1);

        menu.setHeaderTitle(subject);

        if (selected.contains(contextMenuUniqueId)) {
            menu.findItem(R.id.select).setVisible(false);
        } else {
            menu.findItem(R.id.deselect).setVisible(false);
        }

        if (read) {
            menu.findItem(R.id.mark_as_read).setVisible(false);
        } else {
            menu.findItem(R.id.mark_as_unread).setVisible(false);
        }

        if (flagged) {
            menu.findItem(R.id.flag).setVisible(false);
        } else {
            menu.findItem(R.id.unflag).setVisible(false);
        }

        if (!messagingController.isCopyCapable(account)) {
            menu.findItem(R.id.copy).setVisible(false);
        }

        if (!messagingController.isMoveCapable(account)) {
            menu.findItem(R.id.move).setVisible(false);
            menu.findItem(R.id.archive).setVisible(false);
            menu.findItem(R.id.spam).setVisible(false);
        }

        if (!account.hasArchiveFolder()) {
            menu.findItem(R.id.archive).setVisible(false);
        }

        if (!account.hasSpamFolder()) {
            menu.findItem(R.id.spam).setVisible(false);
        }

    }

    public void onSwipeRightToLeft(final MotionEvent e1, final MotionEvent e2) {
        // Handle right-to-left as an un-select
        handleSwipe(e1, false);
    }

    public void onSwipeLeftToRight(final MotionEvent e1, final MotionEvent e2) {
        // Handle left-to-right as a select.
        handleSwipe(e1, true);
    }

    /**
     * Handle a select or unselect swipe event.
     *
     * @param downMotion
     *         Event that started the swipe
     * @param selected
     *         {@code true} if this was an attempt to select (i.e. left to right).
     */
    private void handleSwipe(final MotionEvent downMotion, final boolean selected) {
        int x = (int) downMotion.getRawX();
        int y = (int) downMotion.getRawY();

        Rect headerRect = new Rect();
        listView.getGlobalVisibleRect(headerRect);

        // Only handle swipes in the visible area of the message list
        if (headerRect.contains(x, y)) {
            int[] listPosition = new int[2];
            listView.getLocationOnScreen(listPosition);

            int listX = x - listPosition[0];
            int listY = y - listPosition[1];

            int listViewPosition = listView.pointToPosition(listX, listY);

            toggleMessageSelect(listViewPosition);
        }
    }

    private int listViewToAdapterPosition(int position) {
        if (position >= 0 && position < adapter.getCount()) {
            return position;
        }

        return AdapterView.INVALID_POSITION;
    }

    private int adapterToListViewPosition(int position) {
        if (position >= 0 && position < adapter.getCount()) {
            return position;
        }

        return AdapterView.INVALID_POSITION;
    }

    class MessageListActivityListener extends ActivityListener {
        @Override
        public void remoteSearchFailed(String folder, final String err) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    Activity activity = getActivity();
                    if (activity != null) {
                        Toast.makeText(activity, R.string.remote_search_error,
                                Toast.LENGTH_LONG).show();
                    }
                }
            });
        }

        @Override
        public void remoteSearchStarted(String folder) {
            handler.progress(true);
            handler.updateFooter(context.getString(R.string.remote_search_sending_query));
        }

        @Override
        public void enableProgressIndicator(boolean enable) {
            handler.progress(enable);
        }

        @Override
        public void remoteSearchFinished(String folder, int numResults, int maxResults, List<Message> extraResults) {
            handler.progress(false);
            handler.remoteSearchFinished();
            extraSearchResults = extraResults;
            if (extraResults != null && extraResults.size() > 0) {
                handler.updateFooter(String.format(context.getString(R.string.load_more_messages_fmt), maxResults));
            } else {
                handler.updateFooter(null);
            }
            fragmentListener.setMessageListProgress(Window.PROGRESS_END);

        }

        @Override
        public void remoteSearchServerQueryComplete(String folderName, int numResults, int maxResults) {
            handler.progress(true);
            if (maxResults != 0 && numResults > maxResults) {
                handler.updateFooter(context.getString(R.string.remote_search_downloading_limited,
                        maxResults, numResults));
            } else {
                handler.updateFooter(context.getString(R.string.remote_search_downloading, numResults));
            }
            fragmentListener.setMessageListProgress(Window.PROGRESS_START);
        }

        @Override
        public void informUserOfStatus() {
            handler.refreshTitle();
        }

        @Override
        public void synchronizeMailboxStarted(Account account, String folder) {
            if (updateForMe(account, folder)) {
                handler.progress(true);
                handler.folderLoading(folder, true);
            }
            super.synchronizeMailboxStarted(account, folder);
        }

        @Override
        public void synchronizeMailboxFinished(Account account, String folder,
        int totalMessagesInMailbox, int numNewMessages) {

            if (updateForMe(account, folder)) {
                handler.progress(false);
                handler.folderLoading(folder, false);
            }
            super.synchronizeMailboxFinished(account, folder, totalMessagesInMailbox, numNewMessages);
        }

        @Override
        public void synchronizeMailboxFailed(Account account, String folder, String message) {

            if (updateForMe(account, folder)) {
                handler.progress(false);
                handler.folderLoading(folder, false);
            }
            super.synchronizeMailboxFailed(account, folder, message);
        }

        @Override
        public void folderStatusChanged(Account account, String folder, int unreadMessageCount) {
            if (isSingleAccountMode() && isSingleFolderMode() && MessageListFragment.this.account.equals(account) &&
                    folderName.equals(folder)) {
                MessageListFragment.this.unreadMessageCount = unreadMessageCount;
            }
            super.folderStatusChanged(account, folder, unreadMessageCount);
        }

        private boolean updateForMe(Account account, String folder) {
            if (account == null || folder == null) {
                return false;
            }

            if (!Utility.arrayContains(accountUuids, account.getUuid())) {
                return false;
            }

            List<String> folderNames = search.getFolderNames();
            return (folderNames.isEmpty() || folderNames.contains(folder));
        }
    }


    private View getFooterView(ViewGroup parent) {
        if (footerView == null) {
            footerView = layoutInflater.inflate(R.layout.message_list_item_footer, parent, false);
            FooterViewHolder holder = new FooterViewHolder();
            holder.main = (TextView) footerView.findViewById(R.id.main_text);
            footerView.setTag(holder);
        }

        return footerView;
    }

    private void updateFooterView() {
        if (!search.isManualSearch() && currentFolder != null && account != null) {
            if (currentFolder.loading) {
                updateFooter(context.getString(R.string.status_loading_more));
            } else if (!currentFolder.moreMessages) {
                updateFooter(null);
            } else {
                String message;
                if (!currentFolder.lastCheckFailed) {
                    if (account.getDisplayCount() == 0) {
                        message = context.getString(R.string.message_list_load_more_messages_action);
                    } else {
                        message = String.format(context.getString(R.string.load_more_messages_fmt),
                                account.getDisplayCount());
                    }
                } else {
                    message = context.getString(R.string.status_loading_more_failed);
                }
                updateFooter(message);
            }
        } else {
            updateFooter(null);
        }
    }

    public void updateFooter(final String text) {
        if (footerView == null) {
            return;
        }

        FooterViewHolder holder = (FooterViewHolder) footerView.getTag();

        if (text != null) {
            holder.main.setText(text);
            holder.main.setVisibility(View.VISIBLE);
        } else {
            holder.main.setVisibility(View.GONE);
        }
    }

    static class FooterViewHolder {
        public TextView main;
    }

    /**
     * Set selection state for all messages.
     *
     * @param selected
     *         If {@code true} all messages get selected. Otherwise, all messages get deselected and
     *         action mode is finished.
     */
    private void setSelectionState(boolean selected) {
        if (selected) {
            if (adapter.getCount() == 0) {
                // Nothing to do if there are no messages
                return;
            }

            selectedCount = 0;
            for (int i = 0, end = adapter.getCount(); i < end; i++) {
                Cursor cursor = (Cursor) adapter.getItem(i);
                long uniqueId = cursor.getLong(uniqueIdColumn);
                this.selected.add(uniqueId);

                if (showingThreadedList) {
                    int threadCount = cursor.getInt(THREAD_COUNT_COLUMN);
                    selectedCount += (threadCount > 1) ? threadCount : 1;
                } else {
                    selectedCount++;
                }
            }

            if (actionMode == null) {
                startAndPrepareActionMode();
            }
            computeBatchDirection();
            updateActionModeTitle();
            computeSelectAllVisibility();
        } else {
            this.selected.clear();
            selectedCount = 0;
            if (actionMode != null) {
                actionMode.finish();
                actionMode = null;
            }
        }

        adapter.notifyDataSetChanged();
    }

    private void toggleMessageSelect(int listViewPosition) {
        int adapterPosition = listViewToAdapterPosition(listViewPosition);
        if (adapterPosition == AdapterView.INVALID_POSITION) {
            return;
        }

        toggleMessageSelectWithAdapterPosition(adapterPosition);
    }

    void toggleMessageFlagWithAdapterPosition(int adapterPosition) {
        Cursor cursor = (Cursor) adapter.getItem(adapterPosition);
        boolean flagged = (cursor.getInt(FLAGGED_COLUMN) == 1);

        setFlag(adapterPosition,Flag.FLAGGED, !flagged);
    }

    void toggleMessageSelectWithAdapterPosition(int adapterPosition) {
        Cursor cursor = (Cursor) adapter.getItem(adapterPosition);
        long uniqueId = cursor.getLong(uniqueIdColumn);

        boolean selected = this.selected.contains(uniqueId);
        if (!selected) {
            this.selected.add(uniqueId);
        } else {
            this.selected.remove(uniqueId);
        }

        int selectedCountDelta = 1;
        if (showingThreadedList) {
            int threadCount = cursor.getInt(THREAD_COUNT_COLUMN);
            if (threadCount > 1) {
                selectedCountDelta = threadCount;
            }
        }

        if (actionMode != null) {
            if (selectedCount == selectedCountDelta && selected) {
                actionMode.finish();
                actionMode = null;
                return;
            }
        } else {
            startAndPrepareActionMode();
        }

        if (selected) {
            selectedCount -= selectedCountDelta;
        } else {
            selectedCount += selectedCountDelta;
        }

        computeBatchDirection();
        updateActionModeTitle();

        computeSelectAllVisibility();

        adapter.notifyDataSetChanged();
    }

    private void updateActionModeTitle() {
        actionMode.setTitle(String.format(getString(R.string.actionbar_selected), selectedCount));
    }

    private void computeSelectAllVisibility() {
        actionModeCallback.showSelectAll(selected.size() != adapter.getCount());
    }

    private void computeBatchDirection() {
        boolean isBatchFlag = false;
        boolean isBatchRead = false;

        for (int i = 0, end = adapter.getCount(); i < end; i++) {
            Cursor cursor = (Cursor) adapter.getItem(i);
            long uniqueId = cursor.getLong(uniqueIdColumn);

            if (selected.contains(uniqueId)) {
                boolean read = (cursor.getInt(READ_COLUMN) == 1);
                boolean flagged = (cursor.getInt(FLAGGED_COLUMN) == 1);

                if (!flagged) {
                    isBatchFlag = true;
                }
                if (!read) {
                    isBatchRead = true;
                }

                if (isBatchFlag && isBatchRead) {
                    break;
                }
            }
        }

        actionModeCallback.showMarkAsRead(isBatchRead);
        actionModeCallback.showFlag(isBatchFlag);
    }

    private void setFlag(int adapterPosition, final Flag flag, final boolean newState) {
        if (adapterPosition == AdapterView.INVALID_POSITION) {
            return;
        }

        Cursor cursor = (Cursor) adapter.getItem(adapterPosition);
        Account account = preferences.getAccount(cursor.getString(ACCOUNT_UUID_COLUMN));

        if (showingThreadedList && cursor.getInt(THREAD_COUNT_COLUMN) > 1) {
            long threadRootId = cursor.getLong(THREAD_ROOT_COLUMN);
            messagingController.setFlagForThreads(account,
                    Collections.singletonList(threadRootId), flag, newState);
        } else {
            long id = cursor.getLong(ID_COLUMN);
            messagingController.setFlag(account, Collections.singletonList(id), flag,
                    newState);
        }

        computeBatchDirection();
    }

    private void setFlagForSelected(final Flag flag, final boolean newState) {
        if (selected.isEmpty()) {
            return;
        }

        Map<Account, List<Long>> messageMap = new HashMap<>();
        Map<Account, List<Long>> threadMap = new HashMap<>();
        Set<Account> accounts = new HashSet<>();

        for (int position = 0, end = adapter.getCount(); position < end; position++) {
            Cursor cursor = (Cursor) adapter.getItem(position);
            long uniqueId = cursor.getLong(uniqueIdColumn);

            if (selected.contains(uniqueId)) {
                String uuid = cursor.getString(ACCOUNT_UUID_COLUMN);
                Account account = preferences.getAccount(uuid);
                accounts.add(account);

                if (showingThreadedList && cursor.getInt(THREAD_COUNT_COLUMN) > 1) {
                    List<Long> threadRootIdList = threadMap.get(account);
                    if (threadRootIdList == null) {
                        threadRootIdList = new ArrayList<>();
                        threadMap.put(account, threadRootIdList);
                    }

                    threadRootIdList.add(cursor.getLong(THREAD_ROOT_COLUMN));
                } else {
                    List<Long> messageIdList = messageMap.get(account);
                    if (messageIdList == null) {
                        messageIdList = new ArrayList<>();
                        messageMap.put(account, messageIdList);
                    }

                    messageIdList.add(cursor.getLong(ID_COLUMN));
                }
            }
        }

        for (Account account : accounts) {
            List<Long> messageIds = messageMap.get(account);
            List<Long> threadRootIds = threadMap.get(account);

            if (messageIds != null) {
                messagingController.setFlag(account, messageIds, flag, newState);
            }

            if (threadRootIds != null) {
                messagingController.setFlagForThreads(account, threadRootIds, flag, newState);
            }
        }

        computeBatchDirection();
    }

    private void onMove(MessageReference message) {
        onMove(Collections.singletonList(message));
    }

    /**
     * Display the message move activity.
     *
     * @param messages
     *         Never {@code null}.
     */
    private void onMove(List<MessageReference> messages) {
        if (!checkCopyOrMovePossible(messages, FolderOperation.MOVE)) {
            return;
        }

        String folderName;
        if (isThreadDisplay) {
            folderName = messages.get(0).getFolderName();
        } else if (singleFolderMode) {
            folderName = currentFolder.folder.getName();
        } else {
            folderName = null;
        }


        displayFolderChoice(ACTIVITY_CHOOSE_FOLDER_MOVE, folderName,
                messages.get(0).getAccountUuid(), null,
                messages);
    }

    private void onCopy(MessageReference message) {
        onCopy(Collections.singletonList(message));
    }

    /**
     * Display the message copy activity.
     *
     * @param messages
     *         Never {@code null}.
     */
    private void onCopy(List<MessageReference> messages) {
        if (!checkCopyOrMovePossible(messages, FolderOperation.COPY)) {
            return;
        }

        String folderName;
        if (isThreadDisplay) {
            folderName = messages.get(0).getFolderName();
        } else if (singleFolderMode) {
            folderName = currentFolder.folder.getName();
        } else {
            folderName = null;
        }

        displayFolderChoice(ACTIVITY_CHOOSE_FOLDER_COPY, folderName,
                messages.get(0).getAccountUuid(),
                null,
                messages);
    }

    private void onDebugClearLocally(MessageReference message) {
        messagingController.debugClearMessagesLocally(Collections.singletonList(message));
    }

    /**
     * Helper method to manage the invocation of {@link #startActivityForResult(Intent, int)} for a
     * folder operation ({@link ChooseFolder} activity), while saving a list of associated messages.
     *
     * @param requestCode
     *         If {@code >= 0}, this code will be returned in {@code onActivityResult()} when the
     *         activity exits.
     *
     * @see #startActivityForResult(Intent, int)
     */
    private void displayFolderChoice(int requestCode, String sourceFolderName,
            String accountUuid, String lastSelectedFolderName,
            List<MessageReference> messages) {
        Intent intent = new Intent(getActivity(), ChooseFolder.class);
        intent.putExtra(ChooseFolder.EXTRA_ACCOUNT, accountUuid);
        intent.putExtra(ChooseFolder.EXTRA_SEL_FOLDER, lastSelectedFolderName);

        if (sourceFolderName == null) {
            intent.putExtra(ChooseFolder.EXTRA_SHOW_CURRENT, "yes");
        } else {
            intent.putExtra(ChooseFolder.EXTRA_CUR_FOLDER, sourceFolderName);
        }

        // remember the selected messages for #onActivityResult
        activeMessages = messages;
        startActivityForResult(intent, requestCode);
    }

    private void onArchive(MessageReference message) {
        onArchive(Collections.singletonList(message));
    }

    private void onArchive(final List<MessageReference> messages) {
        Map<Account, List<MessageReference>> messagesByAccount = groupMessagesByAccount(messages);

        for (Entry<Account, List<MessageReference>> entry : messagesByAccount.entrySet()) {
            Account account = entry.getKey();
            String archiveFolder = account.getArchiveFolderName();

            if (!K9.FOLDER_NONE.equals(archiveFolder)) {
                move(entry.getValue(), archiveFolder);
            }
        }
    }

    private Map<Account, List<MessageReference>> groupMessagesByAccount(final List<MessageReference> messages) {
        Map<Account, List<MessageReference>> messagesByAccount = new HashMap<>();
        for (MessageReference message : messages) {
            Account account = preferences.getAccount(message.getAccountUuid());

            List<MessageReference> msgList = messagesByAccount.get(account);
            if (msgList == null) {
                msgList = new ArrayList<>();
                messagesByAccount.put(account, msgList);
            }

            msgList.add(message);
        }
        return messagesByAccount;
    }

    private void onSpam(MessageReference message) {
        onSpam(Collections.singletonList(message));
    }

    /**
     * Move messages to the spam folder.
     *
     * @param messages
     *         The messages to move to the spam folder. Never {@code null}.
     */
    private void onSpam(List<MessageReference> messages) {
        if (K9.confirmSpam()) {
            // remember the message selection for #onCreateDialog(int)
            activeMessages = messages;
            showDialog(R.id.dialog_confirm_spam);
        } else {
            onSpamConfirmed(messages);
        }
    }

    private void onSpamConfirmed(List<MessageReference> messages) {
        Map<Account, List<MessageReference>> messagesByAccount = groupMessagesByAccount(messages);

        for (Entry<Account, List<MessageReference>> entry : messagesByAccount.entrySet()) {
            Account account = entry.getKey();
            String spamFolder = account.getSpamFolderName();

            if (!K9.FOLDER_NONE.equals(spamFolder)) {
                move(entry.getValue(), spamFolder);
            }
        }
    }

    private enum FolderOperation {
        COPY, MOVE
    }

    /**
     * Display a Toast message if any message isn't synchronized
     *
     * @param messages
     *         The messages to copy or move. Never {@code null}.
     * @param operation
     *         The type of operation to perform. Never {@code null}.
     *
     * @return {@code true}, if operation is possible.
     */
    private boolean checkCopyOrMovePossible(final List<MessageReference> messages,
            final FolderOperation operation) {

        if (messages.isEmpty()) {
            return false;
        }

        boolean first = true;
        for (MessageReference message : messages) {
            if (first) {
                first = false;
                Account account = preferences.getAccount(message.getAccountUuid());
                if ((operation == FolderOperation.MOVE && !messagingController.isMoveCapable(account)) ||
                        (operation == FolderOperation.COPY && !messagingController.isCopyCapable(account))) {
                    return false;
                }
            }
            // message check
            if ((operation == FolderOperation.MOVE && !messagingController.isMoveCapable(message)) ||
                    (operation == FolderOperation.COPY && !messagingController.isCopyCapable(message))) {
                final Toast toast = Toast.makeText(getActivity(), R.string.move_copy_cannot_copy_unsynced_message,
                                                   Toast.LENGTH_LONG);
                toast.show();
                return false;
            }
        }
        return true;
    }

    /**
     * Copy the specified messages to the specified folder.
     *
     * @param messages
     *         List of messages to copy. Never {@code null}.
     * @param destination
     *         The name of the destination folder. Never {@code null}.
     */
    private void copy(List<MessageReference> messages, final String destination) {
        copyOrMove(messages, destination, FolderOperation.COPY);
    }

    /**
     * Move the specified messages to the specified folder.
     *
     * @param messages
     *         The list of messages to move. Never {@code null}.
     * @param destination
     *         The name of the destination folder. Never {@code null}.
     */
    private void move(List<MessageReference> messages, final String destination) {
        copyOrMove(messages, destination, FolderOperation.MOVE);
    }

    /**
     * The underlying implementation for {@link #copy(List, String)} and
     * {@link #move(List, String)}. This method was added mainly because those 2
     * methods share common behavior.
     *
     * @param messages
     *         The list of messages to copy or move. Never {@code null}.
     * @param destination
     *         The name of the destination folder. Never {@code null} or {@link K9#FOLDER_NONE}.
     * @param operation
     *         Specifies what operation to perform. Never {@code null}.
     */
    private void copyOrMove(List<MessageReference> messages, final String destination,
            final FolderOperation operation) {

        Map<String, List<MessageReference>> folderMap = new HashMap<>();

        for (MessageReference message : messages) {
            if ((operation == FolderOperation.MOVE && !messagingController.isMoveCapable(message)) ||
                    (operation == FolderOperation.COPY && !messagingController.isCopyCapable(message))) {

                Toast.makeText(getActivity(), R.string.move_copy_cannot_copy_unsynced_message,
                        Toast.LENGTH_LONG).show();

                // XXX return meaningful error value?

                // message isn't synchronized
                return;
            }

            String folderName = message.getFolderName();
            if (folderName.equals(destination)) {
                // Skip messages already in the destination folder
                continue;
            }

            List<MessageReference> outMessages = folderMap.get(folderName);
            if (outMessages == null) {
                outMessages = new ArrayList<>();
                folderMap.put(folderName, outMessages);
            }

            outMessages.add(message);
        }

        for (Map.Entry<String, List<MessageReference>> entry : folderMap.entrySet()) {
            String folderName = entry.getKey();
            List<MessageReference> outMessages = entry.getValue();
            Account account = preferences.getAccount(outMessages.get(0).getAccountUuid());

            if (operation == FolderOperation.MOVE) {
                if (showingThreadedList) {
                    messagingController.moveMessagesInThread(account, folderName, outMessages, destination);
                } else {
                    messagingController.moveMessages(account, folderName, outMessages, destination);
                }
            } else {
                if (showingThreadedList) {
                    messagingController.copyMessagesInThread(account, folderName, outMessages, destination);
                } else {
                    messagingController.copyMessages(account, folderName, outMessages, destination);
                }
            }
        }
    }


    class ActionModeCallback implements ActionMode.Callback {
        private MenuItem mSelectAll;
        private MenuItem mMarkAsRead;
        private MenuItem mMarkAsUnread;
        private MenuItem mFlag;
        private MenuItem mUnflag;

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            mSelectAll = menu.findItem(R.id.select_all);
            mMarkAsRead = menu.findItem(R.id.mark_as_read);
            mMarkAsUnread = menu.findItem(R.id.mark_as_unread);
            mFlag = menu.findItem(R.id.flag);
            mUnflag = menu.findItem(R.id.unflag);

            // we don't support cross account actions atm
            if (!singleAccountMode) {
                // show all
                menu.findItem(R.id.move).setVisible(true);
                menu.findItem(R.id.archive).setVisible(true);
                menu.findItem(R.id.spam).setVisible(true);
                menu.findItem(R.id.copy).setVisible(true);

                Set<String> accountUuids = getAccountUuidsForSelected();

                for (String accountUuid : accountUuids) {
                    Account account = preferences.getAccount(accountUuid);
                    if (account != null) {
                        setContextCapabilities(account, menu);
                    }
                }

            }
            return true;
        }

        /**
         * Get the set of account UUIDs for the selected messages.
         */
        private Set<String> getAccountUuidsForSelected() {
            int maxAccounts = accountUuids.length;
            Set<String> accountUuids = new HashSet<>(maxAccounts);

            for (int position = 0, end = adapter.getCount(); position < end; position++) {
                Cursor cursor = (Cursor) adapter.getItem(position);
                long uniqueId = cursor.getLong(uniqueIdColumn);

                if (selected.contains(uniqueId)) {
                    String accountUuid = cursor.getString(ACCOUNT_UUID_COLUMN);
                    accountUuids.add(accountUuid);

                    if (accountUuids.size() == MessageListFragment.this.accountUuids.length) {
                        break;
                    }
                }
            }

            return accountUuids;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            actionMode = null;
            mSelectAll = null;
            mMarkAsRead = null;
            mMarkAsUnread = null;
            mFlag = null;
            mUnflag = null;
            setSelectionState(false);
        }

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            MenuInflater inflater = mode.getMenuInflater();
            inflater.inflate(R.menu.message_list_context, menu);

            // check capabilities
            setContextCapabilities(account, menu);

            return true;
        }

        /**
         * Disables menu options not supported by the account type or current "search view".
         *
         * @param account
         *         The account to query for its capabilities.
         * @param menu
         *         The menu to adapt.
         */
        private void setContextCapabilities(Account account, Menu menu) {
            if (!singleAccountMode) {
                // We don't support cross-account copy/move operations right now
                menu.findItem(R.id.move).setVisible(false);
                menu.findItem(R.id.copy).setVisible(false);

                //TODO: we could support the archive and spam operations if all selected messages
                // belong to non-POP3 accounts
                menu.findItem(R.id.archive).setVisible(false);
                menu.findItem(R.id.spam).setVisible(false);

            } else {
                // hide unsupported
                if (!messagingController.isCopyCapable(account)) {
                    menu.findItem(R.id.copy).setVisible(false);
                }

                if (!messagingController.isMoveCapable(account)) {
                    menu.findItem(R.id.move).setVisible(false);
                    menu.findItem(R.id.archive).setVisible(false);
                    menu.findItem(R.id.spam).setVisible(false);
                }

                if (!account.hasArchiveFolder()) {
                    menu.findItem(R.id.archive).setVisible(false);
                }

                if (!account.hasSpamFolder()) {
                    menu.findItem(R.id.spam).setVisible(false);
                }
            }
        }

        public void showSelectAll(boolean show) {
            if (actionMode != null) {
                mSelectAll.setVisible(show);
            }
        }

        public void showMarkAsRead(boolean show) {
            if (actionMode != null) {
                mMarkAsRead.setVisible(show);
                mMarkAsUnread.setVisible(!show);
            }
        }

        public void showFlag(boolean show) {
            if (actionMode != null) {
                mFlag.setVisible(show);
                mUnflag.setVisible(!show);
            }
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            /*
             * In the following we assume that we can't move or copy
             * mails to the same folder. Also that spam isn't available if we are
             * in the spam folder,same for archive.
             *
             * This is the case currently so safe assumption.
             */
            switch (item.getItemId()) {
            case R.id.delete: {
                List<MessageReference> messages = getCheckedMessages();
                onDelete(messages);
                selectedCount = 0;
                break;
            }
            case R.id.mark_as_read: {
                setFlagForSelected(Flag.SEEN, true);
                break;
            }
            case R.id.mark_as_unread: {
                setFlagForSelected(Flag.SEEN, false);
                break;
            }
            case R.id.flag: {
                setFlagForSelected(Flag.FLAGGED, true);
                break;
            }
            case R.id.unflag: {
                setFlagForSelected(Flag.FLAGGED, false);
                break;
            }
            case R.id.select_all: {
                selectAll();
                break;
            }

            // only if the account supports this
            case R.id.archive: {
                onArchive(getCheckedMessages());
                selectedCount = 0;
                break;
            }
            case R.id.spam: {
                onSpam(getCheckedMessages());
                selectedCount = 0;
                break;
            }
            case R.id.move: {
                onMove(getCheckedMessages());
                selectedCount = 0;
                break;
            }
            case R.id.copy: {
                onCopy(getCheckedMessages());
                selectedCount = 0;
                break;
            }
            }
            if (selectedCount == 0) {
                actionMode.finish();
            }

            return true;
        }
    }

    @Override
    public void doPositiveClick(int dialogId) {
        switch (dialogId) {
            case R.id.dialog_confirm_spam: {
                onSpamConfirmed(activeMessages);
                // No further need for this reference
                activeMessages = null;
                break;
            }
            case R.id.dialog_confirm_delete: {
                onDeleteConfirmed(activeMessages);
                activeMessage = null;
                break;
            }
            case R.id.dialog_confirm_mark_all_as_read: {
                markAllAsRead();
                break;
            }
        }
    }

    @Override
    public void doNegativeClick(int dialogId) {
        switch (dialogId) {
            case R.id.dialog_confirm_spam:
            case R.id.dialog_confirm_delete: {
                // No further need for this reference
                activeMessages = null;
                break;
            }
        }
    }

    @Override
    public void dialogCancelled(int dialogId) {
        doNegativeClick(dialogId);
    }

    public void checkMail() {
        if (isSingleAccountMode() && isSingleFolderMode()) {
            messagingController.synchronizeMailbox(account, folderName, activityListener);
            messagingController.sendPendingMessages(account, activityListener);
        } else if (allAccounts) {
            messagingController.checkMail(context, null, true, true, activityListener);
        } else {
            for (String accountUuid : accountUuids) {
                Account account = preferences.getAccount(accountUuid);
                messagingController.checkMail(context, account, true, true, activityListener);
            }
        }
    }

    /**
     * We need to do some special clean up when leaving a remote search result screen. If no
     * remote search is in progress, this method does nothing special.
     */
    @Override
    public void onStop() {
        // If we represent a remote search, then kill that before going back.
        if (isRemoteSearch() && remoteSearchFuture != null) {
            try {
                Timber.i("Remote search in progress, attempting to abort...");
                // Canceling the future stops any message fetches in progress.
                final boolean cancelSuccess = remoteSearchFuture.cancel(true);   // mayInterruptIfRunning = true
                if (!cancelSuccess) {
                    Timber.e("Could not cancel remote search future.");
                }
                // Closing the folder will kill off the connection if we're mid-search.
                final Account searchAccount = account;
                final Folder remoteFolder = currentFolder.folder;
                remoteFolder.close();
                // Send a remoteSearchFinished() message for good measure.
                activityListener
                        .remoteSearchFinished(currentFolder.name, 0, searchAccount.getRemoteSearchNumResults(), null);
            } catch (Exception e) {
                // Since the user is going back, log and squash any exceptions.
                Timber.e(e, "Could not abort remote search before going back");
            }
        }
        super.onStop();
    }

    public void selectAll() {
        setSelectionState(true);
    }

    public void onMoveUp() {
        int currentPosition = listView.getSelectedItemPosition();
        if (currentPosition == AdapterView.INVALID_POSITION || listView.isInTouchMode()) {
            currentPosition = listView.getFirstVisiblePosition();
        }
        if (currentPosition > 0) {
            listView.setSelection(currentPosition - 1);
        }
    }

    public void onMoveDown() {
        int currentPosition = listView.getSelectedItemPosition();
        if (currentPosition == AdapterView.INVALID_POSITION || listView.isInTouchMode()) {
            currentPosition = listView.getFirstVisiblePosition();
        }

        if (currentPosition < listView.getCount()) {
            listView.setSelection(currentPosition + 1);
        }
    }

    public boolean openPrevious(MessageReference messageReference) {
        int position = getPosition(messageReference);
        if (position <= 0) {
            return false;
        }

        openMessageAtPosition(position - 1);
        return true;
    }

    public boolean openNext(MessageReference messageReference) {
        int position = getPosition(messageReference);
        if (position < 0 || position == adapter.getCount() - 1) {
            return false;
        }

        openMessageAtPosition(position + 1);
        return true;
    }

    public boolean isFirst(MessageReference messageReference) {
        return adapter.isEmpty() || messageReference.equals(getReferenceForPosition(0));
    }

    public boolean isLast(MessageReference messageReference) {
        return adapter.isEmpty() || messageReference.equals(getReferenceForPosition(adapter.getCount() - 1));
    }

    private MessageReference getReferenceForPosition(int position) {
        Cursor cursor = (Cursor) adapter.getItem(position);

        String accountUuid = cursor.getString(ACCOUNT_UUID_COLUMN);
        String folderName = cursor.getString(FOLDER_NAME_COLUMN);
        String messageUid = cursor.getString(UID_COLUMN);
        return new MessageReference(accountUuid, folderName, messageUid, null);
    }

    private void openMessageAtPosition(int position) {
        // Scroll message into view if necessary
        int listViewPosition = adapterToListViewPosition(position);
        if (listViewPosition != AdapterView.INVALID_POSITION &&
                (listViewPosition < listView.getFirstVisiblePosition() ||
                listViewPosition > listView.getLastVisiblePosition())) {
            listView.setSelection(listViewPosition);
        }

        MessageReference ref = getReferenceForPosition(position);

        // For some reason the listView.setSelection() above won't do anything when we call
        // onOpenMessage() (and consequently adapter.notifyDataSetChanged()) right away. So we
        // defer the call using MessageListHandler.
        handler.openMessage(ref);
    }

    private int getPosition(MessageReference messageReference) {
        for (int i = 0, len = adapter.getCount(); i < len; i++) {
            Cursor cursor = (Cursor) adapter.getItem(i);

            String accountUuid = cursor.getString(ACCOUNT_UUID_COLUMN);
            String folderName = cursor.getString(FOLDER_NAME_COLUMN);
            String uid = cursor.getString(UID_COLUMN);

            if (accountUuid.equals(messageReference.getAccountUuid()) &&
                    folderName.equals(messageReference.getFolderName()) &&
                    uid.equals(messageReference.getUid())) {
                return i;
            }
        }

        return -1;
    }

    public interface MessageListFragmentListener {
        void enableActionBarProgress(boolean enable);
        void setMessageListProgress(int level);
        void showThread(Account account, String folderName, long rootId);
        void showMoreFromSameSender(String senderAddress);
        void onResendMessage(MessageReference message);
        void onForward(MessageReference message);
        void onReply(MessageReference message);
        void onReplyAll(MessageReference message);
        void openMessage(MessageReference messageReference);
        void setMessageListTitle(String title);
        void setMessageListSubTitle(String subTitle);
        void setUnreadCount(int unread);
        void onCompose(Account account);
        boolean startSearch(Account account, String folderName);
        void remoteSearchStarted();
        void goBack();
        void updateMenu();
    }

    public void onReverseSort() {
        changeSort(sortType);
    }

    private MessageReference getSelectedMessage() {
        int listViewPosition = listView.getSelectedItemPosition();
        int adapterPosition = listViewToAdapterPosition(listViewPosition);

        return getMessageAtPosition(adapterPosition);
    }

    private int getAdapterPositionForSelectedMessage() {
        int listViewPosition = listView.getSelectedItemPosition();
        return listViewToAdapterPosition(listViewPosition);
    }

    private int getPositionForUniqueId(long uniqueId) {
        for (int position = 0, end = adapter.getCount(); position < end; position++) {
            Cursor cursor = (Cursor) adapter.getItem(position);
            if (cursor.getLong(uniqueIdColumn) == uniqueId) {
                return position;
            }
        }

        return AdapterView.INVALID_POSITION;
    }

    private MessageReference getMessageAtPosition(int adapterPosition) {
        if (adapterPosition == AdapterView.INVALID_POSITION) {
            return null;
        }

        Cursor cursor = (Cursor) adapter.getItem(adapterPosition);

        String accountUuid = cursor.getString(ACCOUNT_UUID_COLUMN);
        String folderName = cursor.getString(FOLDER_NAME_COLUMN);
        String messageUid = cursor.getString(UID_COLUMN);

        return new MessageReference(accountUuid, folderName, messageUid, null);
    }

    private List<MessageReference> getCheckedMessages() {
        List<MessageReference> messages = new ArrayList<>(selected.size());
        for (int position = 0, end = adapter.getCount(); position < end; position++) {
            Cursor cursor = (Cursor) adapter.getItem(position);
            long uniqueId = cursor.getLong(uniqueIdColumn);

            if (selected.contains(uniqueId)) {
                MessageReference message = getMessageAtPosition(position);
                if (message != null) {
                    messages.add(message);
                }
            }
        }

        return messages;
    }

    public void onDelete() {
        MessageReference message = getSelectedMessage();
        if (message != null) {
            onDelete(Collections.singletonList(message));
        }
    }

    public void toggleMessageSelect() {
        toggleMessageSelect(listView.getSelectedItemPosition());
    }

    public void onToggleFlagged() {
        onToggleFlag(Flag.FLAGGED, FLAGGED_COLUMN);
    }

    public void onToggleRead() {
        onToggleFlag(Flag.SEEN, READ_COLUMN);
    }

    private void onToggleFlag(Flag flag, int flagColumn) {
        int adapterPosition = getAdapterPositionForSelectedMessage();
        if (adapterPosition == ListView.INVALID_POSITION) {
            return;
        }

        Cursor cursor = (Cursor) adapter.getItem(adapterPosition);
        boolean flagState = (cursor.getInt(flagColumn) == 1);
        setFlag(adapterPosition, flag, !flagState);
    }

    public void onMove() {
        MessageReference message = getSelectedMessage();
        if (message != null) {
            onMove(message);
        }
    }

    public void onArchive() {
        MessageReference message = getSelectedMessage();
        if (message != null) {
            onArchive(message);
        }
    }

    public void onCopy() {
        MessageReference message = getSelectedMessage();
        if (message != null) {
            onCopy(message);
        }
    }

    public boolean isOutbox() {
        return (folderName != null && folderName.equals(account.getOutboxFolderName()));
    }

    private boolean isErrorFolder() {
        return K9.ERROR_FOLDER_NAME.equals(folderName);
    }

    public boolean isRemoteFolder() {
        if (search.isManualSearch() || isOutbox() || isErrorFolder()) {
            return false;
        }

        if (!messagingController.isMoveCapable(account)) {
            // For POP3 accounts only the Inbox is a remote folder.
            return (folderName != null && folderName.equals(account.getInboxFolderName()));
        }

        return true;
    }

    public boolean isManualSearch() {
        return search.isManualSearch();
    }

    public boolean isAccountExpungeCapable() {
        try {
            return (account != null && account.getRemoteStore().isExpungeCapable());
        } catch (Exception e) {
            return false;
        }
    }

    public void onRemoteSearch() {
        // Remote search is useless without the network.
        if (hasConnectivity) {
            onRemoteSearchRequested();
        } else {
            Toast.makeText(getActivity(), getText(R.string.remote_search_unavailable_no_network),
                    Toast.LENGTH_SHORT).show();
        }
    }

    public boolean isRemoteSearch() {
        return remoteSearchPerformed;
    }

    public boolean isRemoteSearchAllowed() {
        if (!search.isManualSearch() || remoteSearchPerformed || !singleFolderMode) {
            return false;
        }

        boolean allowRemoteSearch = false;
        final Account searchAccount = account;
        if (searchAccount != null) {
            allowRemoteSearch = searchAccount.allowRemoteSearch();
        }

        return allowRemoteSearch;
    }

    public boolean onSearchRequested() {
        String folderName = (currentFolder != null) ? currentFolder.name : null;
        return fragmentListener.startSearch(account, folderName);
   }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        String accountUuid = accountUuids[id];
        Account account = preferences.getAccount(accountUuid);

        String threadId = getThreadId(search);

        Uri uri;
        String[] projection;
        boolean needConditions;
        if (threadId != null) {
            uri = Uri.withAppendedPath(EmailProvider.CONTENT_URI, "account/" + accountUuid + "/thread/" + threadId);
            projection = PROJECTION;
            needConditions = false;
        } else if (showingThreadedList) {
            uri = Uri.withAppendedPath(EmailProvider.CONTENT_URI, "account/" + accountUuid + "/messages/threaded");
            projection = THREADED_PROJECTION;
            needConditions = true;
        } else {
            uri = Uri.withAppendedPath(EmailProvider.CONTENT_URI, "account/" + accountUuid + "/messages");
            projection = PROJECTION;
            needConditions = true;
        }

        StringBuilder query = new StringBuilder();
        List<String> queryArgs = new ArrayList<>();
        if (needConditions) {
            boolean selectActive = activeMessage != null && activeMessage.getAccountUuid().equals(accountUuid);

            if (selectActive) {
                query.append("(" + MessageColumns.UID + " = ? AND " + SpecialColumns.FOLDER_NAME + " = ?) OR (");
                queryArgs.add(activeMessage.getUid());
                queryArgs.add(activeMessage.getFolderName());
            }

            SqlQueryBuilder.buildWhereClause(account, search.getConditions(), query, queryArgs);

            if (selectActive) {
                query.append(')');
            }
        }

        String selection = query.toString();
        String[] selectionArgs = queryArgs.toArray(new String[0]);

        String sortOrder = buildSortOrder();

        return new CursorLoader(getActivity(), uri, projection, selection, selectionArgs,
                sortOrder);
    }

    private String getThreadId(LocalSearch search) {
        for (ConditionsTreeNode node : search.getLeafSet()) {
            SearchCondition condition = node.mCondition;
            if (condition.field == SearchField.THREAD_ID) {
                return condition.value;
            }
        }

        return null;
    }

    private String buildSortOrder() {
        String sortColumn;
        switch (sortType) {
            case SORT_ARRIVAL: {
                sortColumn = MessageColumns.INTERNAL_DATE;
                break;
            }
            case SORT_ATTACHMENT: {
                sortColumn = "(" + MessageColumns.ATTACHMENT_COUNT + " < 1)";
                break;
            }
            case SORT_FLAGGED: {
                sortColumn = "(" + MessageColumns.FLAGGED + " != 1)";
                break;
            }
            case SORT_SENDER: {
                //FIXME
                sortColumn = MessageColumns.SENDER_LIST;
                break;
            }
            case SORT_SUBJECT: {
                sortColumn = MessageColumns.SUBJECT + " COLLATE NOCASE";
                break;
            }
            case SORT_UNREAD: {
                sortColumn = MessageColumns.READ;
                break;
            }
            case SORT_DATE:
            default: {
                sortColumn = MessageColumns.DATE;
            }
        }

        String sortDirection = (sortAscending) ? " ASC" : " DESC";
        String secondarySort;
        if (sortType == SortType.SORT_DATE || sortType == SortType.SORT_ARRIVAL) {
            secondarySort = "";
        } else {
            secondarySort = MessageColumns.DATE + ((sortDateAscending) ? " ASC, " : " DESC, ");
        }

        return sortColumn + sortDirection + ", " + secondarySort + MessageColumns.ID + " DESC";
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        if (isThreadDisplay && data.getCount() == 0) {
            handler.goBack();
            return;
        }

        swipeRefreshLayout.setRefreshing(false);
        swipeRefreshLayout.setEnabled(isPullToRefreshAllowed());

        final int loaderId = loader.getId();
        cursors[loaderId] = data;
        cursorValid[loaderId] = true;

        Cursor cursor;
        if (cursors.length > 1) {
            cursor = new MergeCursorWithUniqueId(cursors, getComparator());
            uniqueIdColumn = cursor.getColumnIndex("_id");
        } else {
            cursor = data;
            uniqueIdColumn = ID_COLUMN;
        }

        if (isThreadDisplay) {
            if (cursor.moveToFirst()) {
                title = cursor.getString(SUBJECT_COLUMN);
                if (!TextUtils.isEmpty(title)) {
                    title = Utility.stripSubject(title);
                }
                if (TextUtils.isEmpty(title)) {
                    title = getString(R.string.general_no_subject);
                }
                updateTitle();
            } else {
                //TODO: empty thread view -> return to full message list
            }
        }

        cleanupSelected(cursor);
        updateContextMenu(cursor);

        adapter.swapCursor(cursor);

        resetActionMode();
        computeBatchDirection();

        if (isLoadFinished()) {
            if (savedListState != null) {
                handler.restoreListPosition();
            }

            fragmentListener.updateMenu();
        }
    }

    private void updateMoreMessagesOfCurrentFolder() {
        if (folderName != null) {
            try {
                LocalFolder folder = MlfUtils.getOpenFolder(folderName, account);
                currentFolder.setMoreMessagesFromFolder(folder);
            } catch (MessagingException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public boolean isLoadFinished() {
        if (cursorValid == null) {
            return false;
        }

        for (boolean cursorValid : this.cursorValid) {
            if (!cursorValid) {
                return false;
            }
        }

        return true;
    }

    /**
     * Close the context menu when the message it was opened for is no longer in the message list.
     */
    private void updateContextMenu(Cursor cursor) {
        if (contextMenuUniqueId == 0) {
            return;
        }

        for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
            long uniqueId = cursor.getLong(uniqueIdColumn);
            if (uniqueId == contextMenuUniqueId) {
                return;
            }
        }

        contextMenuUniqueId = 0;
        Activity activity = getActivity();
        if (activity != null) {
            activity.closeContextMenu();
        }
    }

    private void cleanupSelected(Cursor cursor) {
        if (selected.isEmpty()) {
            return;
        }

        Set<Long> selected = new HashSet<>();
        for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
            long uniqueId = cursor.getLong(uniqueIdColumn);
            if (this.selected.contains(uniqueId)) {
                selected.add(uniqueId);
            }
        }

        this.selected = selected;
    }

    /**
     * Starts or finishes the action mode when necessary.
     */
    private void resetActionMode() {
        if (selected.isEmpty()) {
            if (actionMode != null) {
                actionMode.finish();
            }
            return;
        }

        if (actionMode == null) {
            startAndPrepareActionMode();
        }

        recalculateSelectionCount();
        updateActionModeTitle();
    }

    private void startAndPrepareActionMode() {
        actionMode = getActivity().startActionMode(actionModeCallback);
        actionMode.invalidate();
    }

    /**
     * Recalculates the selection count.
     *
     * <p>
     * For non-threaded lists this is simply the number of visibly selected messages. If threaded
     * view is enabled this method counts the number of messages in the selected threads.
     * </p>
     */
    private void recalculateSelectionCount() {
        if (!showingThreadedList) {
            selectedCount = selected.size();
            return;
        }

        selectedCount = 0;
        for (int i = 0, end = adapter.getCount(); i < end; i++) {
            Cursor cursor = (Cursor) adapter.getItem(i);
            long uniqueId = cursor.getLong(uniqueIdColumn);

            if (selected.contains(uniqueId)) {
                int threadCount = cursor.getInt(THREAD_COUNT_COLUMN);
                selectedCount += (threadCount > 1) ? threadCount : 1;
            }
        }
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        selected.clear();
        adapter.swapCursor(null);
    }

    Account getAccountFromCursor(Cursor cursor) {
        String accountUuid = cursor.getString(ACCOUNT_UUID_COLUMN);
        return preferences.getAccount(accountUuid);
    }

    void remoteSearchFinished() {
        remoteSearchFuture = null;
    }

    /**
     * Mark a message as 'active'.
     *
     * <p>
     * The active message is the one currently displayed in the message view portion of the split
     * view.
     * </p>
     *
     * @param messageReference
     *         {@code null} to not mark any message as being 'active'.
     */
    public void setActiveMessage(MessageReference messageReference) {
        activeMessage = messageReference;

        // Reload message list with modified query that always includes the active message
        if (isAdded()) {
            restartLoader();
        }

        // Redraw list immediately
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    public boolean isSingleAccountMode() {
        return singleAccountMode;
    }

    public boolean isSingleFolderMode() {
        return singleFolderMode;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public boolean isMarkAllAsReadSupported() {
        return (isSingleAccountMode() && isSingleFolderMode());
    }

    public void confirmMarkAllAsRead() {
        if (K9.confirmMarkAllRead()) {
            showDialog(R.id.dialog_confirm_mark_all_as_read);
        } else {
            markAllAsRead();
        }
    }

    private void markAllAsRead() {
        if (isMarkAllAsReadSupported()) {
            messagingController.markAllMessagesRead(account, folderName);
        }
    }

    public boolean isCheckMailSupported() {
        return (allAccounts || !isSingleAccountMode() || !isSingleFolderMode() ||
                isRemoteFolder());
    }

    private boolean isCheckMailAllowed() {
        return (!isManualSearch() && isCheckMailSupported());
    }

    private boolean isPullToRefreshAllowed() {
        return (isRemoteSearchAllowed() || isCheckMailAllowed());
    }

    LayoutInflater getK9LayoutInflater() {
        return layoutInflater;
    }
}
