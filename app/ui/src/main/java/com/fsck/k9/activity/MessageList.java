package com.fsck.k9.activity;


import java.util.Collection;
import java.util.List;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.IntentSender.SendIntentException;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;

import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentManager.OnBackStackChangedListener;
import androidx.fragment.app.FragmentTransaction;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.ActionBarDrawerToggle;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.fsck.k9.Account;
import com.fsck.k9.Account.SortType;
import com.fsck.k9.DI;
import com.fsck.k9.K9;
import com.fsck.k9.K9.SplitViewMode;
import com.fsck.k9.Preferences;
import com.fsck.k9.activity.compose.MessageActions;
import com.fsck.k9.controller.MessageReference;
import com.fsck.k9.fragment.MessageListFragment;
import com.fsck.k9.fragment.MessageListFragment.MessageListFragmentListener;
import com.fsck.k9.helper.Contacts;
import com.fsck.k9.helper.ParcelableUtil;
import com.fsck.k9.mailstore.SearchStatusManager;
import com.fsck.k9.mailstore.StorageManager;
import com.fsck.k9.notification.NotificationChannelManager;
import com.fsck.k9.search.LocalSearch;
import com.fsck.k9.search.SearchAccount;
import com.fsck.k9.search.SearchSpecification;
import com.fsck.k9.search.SearchSpecification.Attribute;
import com.fsck.k9.search.SearchSpecification.SearchCondition;
import com.fsck.k9.search.SearchSpecification.SearchField;
import com.fsck.k9.ui.BuildConfig;
import com.fsck.k9.ui.K9Drawer;
import com.fsck.k9.ui.R;
import com.fsck.k9.ui.Theme;
import com.fsck.k9.ui.managefolders.ManageFoldersActivity;
import com.fsck.k9.ui.messagelist.DefaultFolderProvider;
import com.fsck.k9.ui.messageview.MessageViewFragment;
import com.fsck.k9.ui.messageview.MessageViewFragment.MessageViewFragmentListener;
import com.fsck.k9.ui.messageview.PlaceholderFragment;
import com.fsck.k9.ui.onboarding.OnboardingActivity;
import com.fsck.k9.view.ViewSwitcher;
import com.fsck.k9.view.ViewSwitcher.OnSwitchCompleteListener;
import com.mikepenz.materialdrawer.Drawer.OnDrawerListener;
import timber.log.Timber;


/**
 * MessageList is the primary user interface for the program. This Activity
 * shows a list of messages.
 * From this Activity the user can perform all standard message operations.
 */
public class MessageList extends K9Activity implements MessageListFragmentListener,
        MessageViewFragmentListener, OnBackStackChangedListener, OnSwitchCompleteListener {

    private static final String EXTRA_SEARCH = "search_bytes";
    private static final String EXTRA_NO_THREADING = "no_threading";

    private static final String ACTION_SHORTCUT = "shortcut";
    private static final String EXTRA_SPECIAL_FOLDER = "special_folder";

    private static final String EXTRA_MESSAGE_REFERENCE = "message_reference";

    // used for remote search
    public static final String EXTRA_SEARCH_ACCOUNT = "com.fsck.k9.search_account";
    private static final String EXTRA_SEARCH_FOLDER = "com.fsck.k9.search_folder";

    private static final String STATE_DISPLAY_MODE = "displayMode";
    private static final String STATE_MESSAGE_LIST_WAS_DISPLAYED = "messageListWasDisplayed";
    private static final String STATE_FIRST_BACK_STACK_ID = "firstBackstackId";

    private static final String FRAGMENT_TAG_MESSAGE_VIEW = "MessageViewFragment";
    private static final String FRAGMENT_TAG_PLACEHOLDER = "MessageViewPlaceholder";

    // Used for navigating to next/previous message
    private static final int PREVIOUS = 1;
    private static final int NEXT = 2;

    public static final int REQUEST_MASK_PENDING_INTENT = 1 << 15;

    public static void actionDisplaySearch(Context context, SearchSpecification search,
            boolean noThreading, boolean newTask) {
        actionDisplaySearch(context, search, noThreading, newTask, true);
    }

    public static void actionDisplaySearch(Context context, SearchSpecification search,
            boolean noThreading, boolean newTask, boolean clearTop) {
        context.startActivity(
                intentDisplaySearch(context, search, noThreading, newTask, clearTop));
    }

    public static Intent intentDisplaySearch(Context context, SearchSpecification search,
            boolean noThreading, boolean newTask, boolean clearTop) {
        Intent intent = new Intent(context, MessageList.class);
        intent.putExtra(EXTRA_SEARCH, ParcelableUtil.marshall(search));
        intent.putExtra(EXTRA_NO_THREADING, noThreading);

        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (clearTop) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        }
        if (newTask) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }

        return intent;
    }

    public static Intent shortcutIntent(Context context, String specialFolder) {
        Intent intent = new Intent(context, MessageList.class);
        intent.setAction(ACTION_SHORTCUT);
        intent.putExtra(EXTRA_SPECIAL_FOLDER, specialFolder);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        return intent;
    }

    public static Intent shortcutIntentForAccount(Context context, Account account) {
        DefaultFolderProvider defaultFolderProvider = DI.get(DefaultFolderProvider.class);
        String folderServerId = defaultFolderProvider.getDefaultFolder(account);

        LocalSearch search = new LocalSearch();
        search.addAccountUuid(account.getUuid());
        search.addAllowedFolder(folderServerId);
        return MessageList.intentDisplaySearch(context, search, false, true, true);
    }

    public static Intent actionDisplayMessageIntent(Context context,
            MessageReference messageReference) {
        Intent intent = new Intent(context, MessageList.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(EXTRA_MESSAGE_REFERENCE, messageReference.toIdentityString());
        return intent;
    }

    public static void launch(Context context) {
        Intent intent = new Intent(context, MessageList.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP |
                Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
    }


    private enum DisplayMode {
        MESSAGE_LIST,
        MESSAGE_VIEW,
        SPLIT_VIEW
    }


    protected final SearchStatusManager searchStatusManager = DI.get(SearchStatusManager.class);
    private StorageManager.StorageListener mStorageListener = new StorageListenerImplementation();
    private final Preferences preferences = DI.get(Preferences.class);
    private final NotificationChannelManager channelUtils = DI.get(NotificationChannelManager.class);
    private final DefaultFolderProvider defaultFolderProvider = DI.get(DefaultFolderProvider.class);

    private ActionBar actionBar;
    private ActionBarDrawerToggle drawerToggle;
    private K9Drawer drawer;
    private FragmentTransaction openFolderTransaction;
    private Menu menu;

    private ProgressBar progressBar;
    private PlaceholderFragment messageViewPlaceHolder;

    private MessageListFragment messageListFragment;
    private MessageViewFragment messageViewFragment;
    private int firstBackStackId = -1;

    private Account account;
    private LocalSearch search;
    private boolean singleFolderMode;

    private int lastDirection = (K9.isMessageViewShowNext()) ? NEXT : PREVIOUS;

    /**
     * {@code true} if the message list should be displayed as flat list (i.e. no threading)
     * regardless whether or not message threading was enabled in the settings. This is used for
     * filtered views, e.g. when only displaying the unread messages in a folder.
     */
    private boolean noThreading;

    private DisplayMode displayMode;
    private MessageReference messageReference;

    /**
     * {@code true} when the message list was displayed once. This is used in
     * {@link #onBackPressed()} to decide whether to go from the message view to the message list or
     * finish the activity.
     */
    private boolean messageListWasDisplayed = false;
    private ViewSwitcher viewSwitcher;


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        List<Account> accounts = preferences.getAccounts();
        if (accounts.isEmpty()) {
            OnboardingActivity.launch(this);
            finish();
            return;
        }

        if (UpgradeDatabases.actionUpgradeDatabases(this, getIntent())) {
            finish();
            return;
        }

        if (useSplitView()) {
            setLayout(R.layout.split_message_list);
        } else {
            setLayout(R.layout.message_list);
            viewSwitcher = findViewById(R.id.container);
            viewSwitcher.setFirstInAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_in_left));
            viewSwitcher.setFirstOutAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_out_right));
            viewSwitcher.setSecondInAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_in_right));
            viewSwitcher.setSecondOutAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_out_left));
            viewSwitcher.setOnSwitchCompleteListener(this);
        }

        initializeActionBar();
        initializeDrawer(savedInstanceState);

        if (!decodeExtras(getIntent())) {
            return;
        }

        if (isDrawerEnabled()) {
            drawer.updateUserAccountsAndFolders(account);
        }

        findFragments();
        initializeDisplayMode(savedInstanceState);
        initializeLayout();
        initializeFragments();
        displayViews();
        channelUtils.updateChannels();

        if (savedInstanceState == null) {
            checkAndRequestPermissions();
        }
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        if (isFinishing()) {
            return;
        }

        setIntent(intent);

        if (firstBackStackId >= 0) {
            getSupportFragmentManager().popBackStackImmediate(firstBackStackId,
                    FragmentManager.POP_BACK_STACK_INCLUSIVE);
            firstBackStackId = -1;
        }
        removeMessageListFragment();
        removeMessageViewFragment();

        messageReference = null;
        search = null;

        if (!decodeExtras(intent)) {
            return;
        }

        if (isDrawerEnabled()) {
            drawer.updateUserAccountsAndFolders(account);
        }

        initializeDisplayMode(null);
        initializeFragments();
        displayViews();
    }

    /**
     * Get references to existing fragments if the activity was restarted.
     */
    private void findFragments() {
        FragmentManager fragmentManager = getSupportFragmentManager();
        messageListFragment = (MessageListFragment) fragmentManager.findFragmentById(R.id.message_list_container);
        messageViewFragment = (MessageViewFragment) fragmentManager.findFragmentByTag(FRAGMENT_TAG_MESSAGE_VIEW);

        if (messageListFragment != null) {
            initializeFromLocalSearch(messageListFragment.getLocalSearch());
        }
    }

    /**
     * Create fragment instances if necessary.
     *
     * @see #findFragments()
     */
    private void initializeFragments() {
        FragmentManager fragmentManager = getSupportFragmentManager();
        fragmentManager.addOnBackStackChangedListener(this);

        boolean hasMessageListFragment = (messageListFragment != null);

        if (!hasMessageListFragment) {
            FragmentTransaction ft = fragmentManager.beginTransaction();
            messageListFragment = MessageListFragment.newInstance(search, false,
                    (K9.isThreadedViewEnabled() && !noThreading));
            ft.add(R.id.message_list_container, messageListFragment);
            ft.commit();
        }

        // Check if the fragment wasn't restarted and has a MessageReference in the arguments. If
        // so, open the referenced message.
        if (!hasMessageListFragment && messageViewFragment == null &&
                messageReference != null) {
            openMessage(messageReference);
        }
    }

    /**
     * Set the initial display mode (message list, message view, or split view).
     *
     * <p><strong>Note:</strong>
     * This method has to be called after {@link #findFragments()} because the result depends on
     * the availability of a {@link MessageViewFragment} instance.
     * </p>
     *
     * @param savedInstanceState
     *         The saved instance state that was passed to the activity as argument to
     *         {@link #onCreate(Bundle)}. May be {@code null}.
     */
    private void initializeDisplayMode(Bundle savedInstanceState) {
        if (useSplitView()) {
            displayMode = DisplayMode.SPLIT_VIEW;
            return;
        }

        if (savedInstanceState != null) {
            DisplayMode savedDisplayMode =
                    (DisplayMode) savedInstanceState.getSerializable(STATE_DISPLAY_MODE);
            if (savedDisplayMode != DisplayMode.SPLIT_VIEW) {
                displayMode = savedDisplayMode;
                return;
            }
        }

        if (messageViewFragment != null || messageReference != null) {
            displayMode = DisplayMode.MESSAGE_VIEW;
        } else {
            displayMode = DisplayMode.MESSAGE_LIST;
        }
    }

    private boolean useSplitView() {
        SplitViewMode splitViewMode = K9.getSplitViewMode();
        int orientation = getResources().getConfiguration().orientation;

        return (splitViewMode == SplitViewMode.ALWAYS ||
                (splitViewMode == SplitViewMode.WHEN_IN_LANDSCAPE &&
                        orientation == Configuration.ORIENTATION_LANDSCAPE));
    }

    private void initializeLayout() {
        progressBar = findViewById(R.id.message_list_progress);
        messageViewPlaceHolder = new PlaceholderFragment();
    }

    private void displayViews() {
        switch (displayMode) {
            case MESSAGE_LIST: {
                showMessageList();
                break;
            }
            case MESSAGE_VIEW: {
                showMessageView();
                break;
            }
            case SPLIT_VIEW: {
                messageListWasDisplayed = true;
                if (messageViewFragment == null) {
                    showMessageViewPlaceHolder();
                } else {
                    MessageReference activeMessage = messageViewFragment.getMessageReference();
                    if (activeMessage != null) {
                        messageListFragment.setActiveMessage(activeMessage);
                    }
                }
                break;
            }
        }
    }

    private boolean decodeExtras(Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            Uri uri = intent.getData();
            List<String> segmentList = uri.getPathSegments();

            String accountId = segmentList.get(0);
            Collection<Account> accounts = preferences.getAvailableAccounts();
            for (Account account : accounts) {
                if (String.valueOf(account.getAccountNumber()).equals(accountId)) {
                    String folderServerId = segmentList.get(1);
                    String messageUid = segmentList.get(2);
                    messageReference = new MessageReference(account.getUuid(), folderServerId, messageUid, null);
                    break;
                }
            }
        } else if (ACTION_SHORTCUT.equals(action)) {
            // Handle shortcut intents
            String specialFolder = intent.getStringExtra(EXTRA_SPECIAL_FOLDER);
            if (SearchAccount.UNIFIED_INBOX.equals(specialFolder)) {
                search = SearchAccount.createUnifiedInboxAccount().getRelatedSearch();
            }
        } else if (intent.getStringExtra(SearchManager.QUERY) != null) {
            // check if this intent comes from the system search ( remote )
            if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
                //Query was received from Search Dialog
                String query = intent.getStringExtra(SearchManager.QUERY).trim();

                search = new LocalSearch(getString(R.string.search_results));
                search.setManualSearch(true);
                noThreading = true;

                search.or(new SearchCondition(SearchField.SENDER, Attribute.CONTAINS, query));
                search.or(new SearchCondition(SearchField.SUBJECT, Attribute.CONTAINS, query));
                search.or(new SearchCondition(SearchField.MESSAGE_CONTENTS, Attribute.CONTAINS, query));

                Bundle appData = intent.getBundleExtra(SearchManager.APP_DATA);
                if (appData != null) {
                    String searchAccountUuid = appData.getString(EXTRA_SEARCH_ACCOUNT);
                    if (searchAccountUuid != null) {
                        search.addAccountUuid(searchAccountUuid);
                        // searches started from a folder list activity will provide an account, but no folder
                        if (appData.getString(EXTRA_SEARCH_FOLDER) != null) {
                            search.addAllowedFolder(appData.getString(EXTRA_SEARCH_FOLDER));
                        }
                    } else if (BuildConfig.DEBUG) {
                        throw new AssertionError("Invalid app data in search intent");
                    }
                }
            }
        } else {
            // regular LocalSearch object was passed
            search = intent.hasExtra(EXTRA_SEARCH) ?
                    ParcelableUtil.unmarshall(intent.getByteArrayExtra(EXTRA_SEARCH), LocalSearch.CREATOR) : null;
            noThreading = intent.getBooleanExtra(EXTRA_NO_THREADING, false);
        }

        if (messageReference == null) {
            String messageReferenceString = intent.getStringExtra(EXTRA_MESSAGE_REFERENCE);
            messageReference = MessageReference.parse(messageReferenceString);
        }

        if (messageReference != null) {
            search = new LocalSearch();
            search.addAccountUuid(messageReference.getAccountUuid());
            String folderServerId = messageReference.getFolderServerId();
            search.addAllowedFolder(folderServerId);
        }

        if (search == null) {
            String accountUuid = intent.getStringExtra("account");
            if (accountUuid != null) {
                // We've most likely been started by an old unread widget or accounts shortcut
                String folderServerId = intent.getStringExtra("folder");
                if (folderServerId == null) {
                    account = preferences.getAccount(accountUuid);
                    folderServerId = defaultFolderProvider.getDefaultFolder(account);
                }

                search = new LocalSearch(folderServerId);
                search.addAccountUuid(accountUuid);
                search.addAllowedFolder(folderServerId);
            } else {
                if (K9.isHideSpecialAccounts()) {
                    account = preferences.getDefaultAccount();
                    search = new LocalSearch();
                    search.addAccountUuid(account.getUuid());
                    String folderServerId = defaultFolderProvider.getDefaultFolder(account);
                    search.addAllowedFolder(folderServerId);
                } else {
                    account = null;
                    search = SearchAccount.createUnifiedInboxAccount().getRelatedSearch();
                }
            }
        }

        initializeFromLocalSearch(search);

        if (account != null && !account.isAvailable(this)) {
            onAccountUnavailable();
            return false;
        }

        return true;
    }


    private void checkAndRequestPermissions() {
        if (!hasPermission(Permission.READ_CONTACTS)) {
            requestPermissionOrShowRationale(Permission.READ_CONTACTS);
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        StorageManager.getInstance(getApplication()).removeListener(mStorageListener);
    }

    @Override
    public void onResume() {
        super.onResume();

        if (!(this instanceof Search)) {
            //necessary b/c no guarantee Search.onStop will be called before MessageList.onResume
            //when returning from search results
            searchStatusManager.setActive(false);
        }

        if (account != null && !account.isAvailable(this)) {
            onAccountUnavailable();
            return;
        }
        StorageManager.getInstance(getApplication()).addListener(mStorageListener);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Contacts.clearCache();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putSerializable(STATE_DISPLAY_MODE, displayMode);
        outState.putBoolean(STATE_MESSAGE_LIST_WAS_DISPLAYED, messageListWasDisplayed);
        outState.putInt(STATE_FIRST_BACK_STACK_ID, firstBackStackId);
    }

    @Override
    public void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);

        messageListWasDisplayed = savedInstanceState.getBoolean(STATE_MESSAGE_LIST_WAS_DISPLAYED);
        firstBackStackId = savedInstanceState.getInt(STATE_FIRST_BACK_STACK_ID);
    }

    private void initializeActionBar() {
        actionBar = getSupportActionBar();
        actionBar.setDisplayHomeAsUpEnabled(true);
    }

    private void initializeDrawer(Bundle savedInstanceState) {
        if (!isDrawerEnabled()) {
            return;
        }

        drawer = new K9Drawer(this, savedInstanceState);

        DrawerLayout drawerLayout = drawer.getLayout();
        drawerToggle = new ActionBarDrawerToggle(
                this, drawerLayout, null,
                R.string.navigation_drawer_open, R.string.navigation_drawer_close
        );
        drawerLayout.addDrawerListener(drawerToggle);
        drawerToggle.syncState();
    }

    public OnDrawerListener createOnDrawerListener() {
        return new OnDrawerListener() {
            @Override
            public void onDrawerClosed(View drawerView) {
                if (openFolderTransaction != null) {
                    openFolderTransaction.commit();
                    openFolderTransaction = null;
                }
            }

            @Override
            public void onDrawerOpened(View drawerView) {
                // Do nothing
            }

            @Override
            public void onDrawerSlide(View drawerView, float slideOffset) {
                // Do nothing
            }
        };
    }

    public void openFolder(String folderName) {
        LocalSearch search = new LocalSearch(folderName);
        search.addAccountUuid(account.getUuid());
        search.addAllowedFolder(folderName);

        performSearch(search);
    }

    public void openFolderImmediately(String folderName) {
        openFolder(folderName);
        openFolderTransaction.commit();
        openFolderTransaction = null;
    }

    public void openUnifiedInbox() {
        account = null;
        drawer.selectUnifiedInbox();
        actionDisplaySearch(this, SearchAccount.createUnifiedInboxAccount().getRelatedSearch(), false, false);
    }

    public void launchManageFoldersScreen() {
        if (account == null) {
            Timber.e("Tried to open \"Manage folders\", but no account selected!");
            return;
        }

        ManageFoldersActivity.launch(this, account);
    }

    public void openRealAccount(Account account) {
        String folderServerId = defaultFolderProvider.getDefaultFolder(account);

        LocalSearch search = new LocalSearch();
        search.addAllowedFolder(folderServerId);
        search.addAccountUuid(account.getUuid());
        actionDisplaySearch(this, search, false, false);
    }

    private void performSearch(LocalSearch search) {
        initializeFromLocalSearch(search);

        FragmentManager fragmentManager = getSupportFragmentManager();

        if (BuildConfig.DEBUG && fragmentManager.getBackStackEntryCount() > 0) {
            throw new IllegalStateException("Don't call performSearch() while there are fragments on the back stack");
        }

        openFolderTransaction = fragmentManager.beginTransaction();
        messageListFragment = MessageListFragment.newInstance(search, false, K9.isThreadedViewEnabled());
        openFolderTransaction.replace(R.id.message_list_container, messageListFragment);
    }

    protected boolean isDrawerEnabled() {
        return true;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        boolean ret = false;
        if (KeyEvent.ACTION_DOWN == event.getAction()) {
            ret = onCustomKeyDown(event.getKeyCode(), event);
        }
        if (!ret) {
            ret = super.dispatchKeyEvent(event);
        }
        return ret;
    }

    @Override
    public void onBackPressed() {
        if (isDrawerEnabled() && drawer.isOpen()) {
            drawer.close();
        } else if (displayMode == DisplayMode.MESSAGE_VIEW && messageListWasDisplayed) {
            showMessageList();
        } else {
            if (isDrawerEnabled() && account != null && getSupportFragmentManager().getBackStackEntryCount() == 0) {
                String defaultFolder = defaultFolderProvider.getDefaultFolder(account);
                String currentFolder = singleFolderMode ? search.getFolderServerIds().get(0) : null;
                if (!defaultFolder.equals(currentFolder)) {
                    openFolderImmediately(defaultFolder);
                } else {
                    super.onBackPressed();
                }
            } else {
                super.onBackPressed();
            }
        }
    }

    /**
     * Handle hotkeys
     *
     * <p>
     * This method is called by {@link #dispatchKeyEvent(KeyEvent)} before any view had the chance
     * to consume this key event.
     * </p>
     *
     * @param keyCode
     *         The value in {@code event.getKeyCode()}.
     * @param event
     *         Description of the key event.
     *
     * @return {@code true} if this event was consumed.
     */
    public boolean onCustomKeyDown(final int keyCode, final KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP: {
                if (messageViewFragment != null && displayMode != DisplayMode.MESSAGE_LIST &&
                        K9.isUseVolumeKeysForNavigation()) {
                    showPreviousMessage();
                    return true;
                } else if (displayMode != DisplayMode.MESSAGE_VIEW && K9.isUseVolumeKeysForListNavigation()) {
                    messageListFragment.onMoveUp();
                    return true;
                }

                break;
            }
            case KeyEvent.KEYCODE_VOLUME_DOWN: {
                if (messageViewFragment != null && displayMode != DisplayMode.MESSAGE_LIST &&
                        K9.isUseVolumeKeysForNavigation()) {
                    showNextMessage();
                    return true;
                } else if (displayMode != DisplayMode.MESSAGE_VIEW && K9.isUseVolumeKeysForListNavigation()) {
                    messageListFragment.onMoveDown();
                    return true;
                }

                break;
            }
            case KeyEvent.KEYCODE_C: {
                messageListFragment.onCompose();
                return true;
            }
            case KeyEvent.KEYCODE_O: {
                messageListFragment.onCycleSort();
                return true;
            }
            case KeyEvent.KEYCODE_I: {
                messageListFragment.onReverseSort();
                return true;
            }
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_D: {
                if (displayMode == DisplayMode.MESSAGE_LIST) {
                    messageListFragment.onDelete();
                } else if (messageViewFragment != null) {
                    messageViewFragment.onDelete();
                }
                return true;
            }
            case KeyEvent.KEYCODE_S: {
                messageListFragment.toggleMessageSelect();
                return true;
            }
            case KeyEvent.KEYCODE_G: {
                if (displayMode == DisplayMode.MESSAGE_LIST) {
                    messageListFragment.onToggleFlagged();
                } else if (messageViewFragment != null) {
                    messageViewFragment.onToggleFlagged();
                }
                return true;
            }
            case KeyEvent.KEYCODE_M: {
                if (displayMode == DisplayMode.MESSAGE_LIST) {
                    messageListFragment.onMove();
                } else if (messageViewFragment != null) {
                    messageViewFragment.onMove();
                }
                return true;
            }
            case KeyEvent.KEYCODE_V: {
                if (displayMode == DisplayMode.MESSAGE_LIST) {
                    messageListFragment.onArchive();
                } else if (messageViewFragment != null) {
                    messageViewFragment.onArchive();
                }
                return true;
            }
            case KeyEvent.KEYCODE_Y: {
                if (displayMode == DisplayMode.MESSAGE_LIST) {
                    messageListFragment.onCopy();
                } else if (messageViewFragment != null) {
                    messageViewFragment.onCopy();
                }
                return true;
            }
            case KeyEvent.KEYCODE_Z: {
                if (displayMode == DisplayMode.MESSAGE_LIST) {
                    messageListFragment.onToggleRead();
                } else if (messageViewFragment != null) {
                    messageViewFragment.onToggleRead();
                }
                return true;
            }
            case KeyEvent.KEYCODE_F: {
                if (messageViewFragment != null) {
                    messageViewFragment.onForward();
                }
                return true;
            }
            case KeyEvent.KEYCODE_A: {
                if (messageViewFragment != null) {
                    messageViewFragment.onReplyAll();
                }
                return true;
            }
            case KeyEvent.KEYCODE_R: {
                if (messageViewFragment != null) {
                    messageViewFragment.onReply();
                }
                return true;
            }
            case KeyEvent.KEYCODE_J:
            case KeyEvent.KEYCODE_P: {
                if (messageViewFragment != null) {
                    showPreviousMessage();
                }
                return true;
            }
            case KeyEvent.KEYCODE_N:
            case KeyEvent.KEYCODE_K: {
                if (messageViewFragment != null) {
                    showNextMessage();
                }
                return true;
            }
            /* FIXME
            case KeyEvent.KEYCODE_Z: {
                messageViewFragment.zoom(event);
                return true;
            }*/
            case KeyEvent.KEYCODE_H: {
                Toast toast;
                if (displayMode == DisplayMode.MESSAGE_LIST) {
                    toast = Toast.makeText(this, R.string.message_list_help_key, Toast.LENGTH_LONG);
                } else {
                    toast = Toast.makeText(this, R.string.message_view_help_key, Toast.LENGTH_LONG);
                }
                toast.show();
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_LEFT: {
                if (messageViewFragment != null && displayMode == DisplayMode.MESSAGE_VIEW) {
                    return showPreviousMessage();
                }
                return false;
            }
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                if (messageViewFragment != null && displayMode == DisplayMode.MESSAGE_VIEW) {
                    return showNextMessage();
                }
                return false;
            }

        }

        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // Swallow these events too to avoid the audible notification of a volume change
        if (K9.isUseVolumeKeysForListNavigation()) {
            if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP) || (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
                Timber.v("Swallowed key up.");
                return true;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onSearchRequested() {
        return messageListFragment.onSearchRequested();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            if (displayMode != DisplayMode.MESSAGE_VIEW && !isAdditionalMessageListDisplayed()) {
                if (isDrawerEnabled()) {
                    if (drawer.isOpen()) {
                        drawer.close();
                    } else {
                        drawer.open();
                    }
                } else {
                    finish();
                }
            } else {
                goBack();
            }
            return true;
        } else if (id == R.id.compose) {
            messageListFragment.onCompose();
            return true;
        } else if (id == R.id.toggle_message_view_theme) {
            onToggleTheme();
            return true;
        } else if (id == R.id.set_sort_date) {     // MessageList
            messageListFragment.changeSort(SortType.SORT_DATE);
            return true;
        } else if (id == R.id.set_sort_arrival) {
            messageListFragment.changeSort(SortType.SORT_ARRIVAL);
            return true;
        } else if (id == R.id.set_sort_subject) {
            messageListFragment.changeSort(SortType.SORT_SUBJECT);
            return true;
        } else if (id == R.id.set_sort_sender) {
            messageListFragment.changeSort(SortType.SORT_SENDER);
            return true;
        } else if (id == R.id.set_sort_flag) {
            messageListFragment.changeSort(SortType.SORT_FLAGGED);
            return true;
        } else if (id == R.id.set_sort_unread) {
            messageListFragment.changeSort(SortType.SORT_UNREAD);
            return true;
        } else if (id == R.id.set_sort_attach) {
            messageListFragment.changeSort(SortType.SORT_ATTACHMENT);
            return true;
        } else if (id == R.id.select_all) {
            messageListFragment.selectAll();
            return true;
        } else if (id == R.id.search) {
            messageListFragment.onSearchRequested();
            return true;
        } else if (id == R.id.search_remote) {
            messageListFragment.onRemoteSearch();
            return true;
        } else if (id == R.id.mark_all_as_read) {
            messageListFragment.confirmMarkAllAsRead();
            return true;
        } else if (id == R.id.next_message) {   // MessageView
            showNextMessage();
            return true;
        } else if (id == R.id.previous_message) {
            showPreviousMessage();
            return true;
        } else if (id == R.id.delete) {
            messageViewFragment.onDelete();
            return true;
        } else if (id == R.id.reply) {
            messageViewFragment.onReply();
            return true;
        } else if (id == R.id.reply_all) {
            messageViewFragment.onReplyAll();
            return true;
        } else if (id == R.id.forward) {
            messageViewFragment.onForward();
            return true;
        } else if (id == R.id.forward_as_attachment) {
            messageViewFragment.onForwardAsAttachment();
            return true;
        } else if (id == R.id.edit_as_new_message) {
            messageViewFragment.onEditAsNewMessage();
            return true;
        } else if (id == R.id.share) {
            messageViewFragment.onSendAlternate();
            return true;
        } else if (id == R.id.toggle_unread) {
            messageViewFragment.onToggleRead();
            return true;
        } else if (id == R.id.archive || id == R.id.refile_archive) {
            messageViewFragment.onArchive();
            return true;
        } else if (id == R.id.spam || id == R.id.refile_spam) {
            messageViewFragment.onSpam();
            return true;
        } else if (id == R.id.move || id == R.id.refile_move) {
            messageViewFragment.onMove();
            return true;
        } else if (id == R.id.copy || id == R.id.refile_copy) {
            messageViewFragment.onCopy();
            return true;
        } else if (id == R.id.show_headers || id == R.id.hide_headers) {
            messageViewFragment.onToggleAllHeadersView();
            updateMenu();
            return true;
        } else if (id == R.id.set_format_plain) {
            messageViewFragment.setRenderPlainFormat(true);
            messageViewFragment.asyncReloadMessage(); /* call refresh */
            updateMenu();
            return true;
        } else if (id == R.id.set_format_html) {
            messageViewFragment.setRenderPlainFormat(false);
            messageViewFragment.asyncReloadMessage(); /* call refresh */
            updateMenu();
            return true;
        }

        if (!singleFolderMode) {
            // None of the options after this point are "safe" for search results
            //TODO: This is not true for "unread" and "starred" searches in regular folders
            return false;
        }

        if (id == R.id.send_messages) {
            messageListFragment.onSendPendingMessages();
            return true;
        } else if (id == R.id.expunge) {
            messageListFragment.onExpunge();
            return true;
        } else if (id == R.id.empty_trash) {
            messageListFragment.onEmptyTrash();
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.message_list_option, menu);
        this.menu = menu;
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        configureMenu(menu);
        return true;
    }

    /**
     * Hide menu items not appropriate for the current context.
     *
     * <p><strong>Note:</strong>
     * Please adjust the comments in {@code res/menu/message_list_option.xml} if you change the
     * visibility of a menu item in this method.
     * </p>
     *
     * @param menu
     *         The {@link Menu} instance that should be modified. May be {@code null}; in that case
     *         the method does nothing and immediately returns.
     */
    private void configureMenu(Menu menu) {
        if (menu == null) {
            return;
        }

        /*
         * Set visibility of menu items related to the message view
         */

        if (displayMode == DisplayMode.MESSAGE_LIST
                || messageViewFragment == null
                || !messageViewFragment.isInitialized()) {
            menu.findItem(R.id.next_message).setVisible(false);
            menu.findItem(R.id.previous_message).setVisible(false);
            menu.findItem(R.id.single_message_options).setVisible(false);
            menu.findItem(R.id.delete).setVisible(false);
            menu.findItem(R.id.compose).setVisible(false);
            menu.findItem(R.id.archive).setVisible(false);
            menu.findItem(R.id.move).setVisible(false);
            menu.findItem(R.id.copy).setVisible(false);
            menu.findItem(R.id.spam).setVisible(false);
            menu.findItem(R.id.refile).setVisible(false);
            menu.findItem(R.id.toggle_unread).setVisible(false);
            menu.findItem(R.id.toggle_message_view_theme).setVisible(false);
            menu.findItem(R.id.show_headers).setVisible(false);
            menu.findItem(R.id.hide_headers).setVisible(false);
            menu.findItem(R.id.set_format_html).setVisible(false);
            menu.findItem(R.id.set_format_plain).setVisible(false);
        } else {
            // hide prev/next buttons in split mode
            if (displayMode != DisplayMode.MESSAGE_VIEW) {
                menu.findItem(R.id.next_message).setVisible(false);
                menu.findItem(R.id.previous_message).setVisible(false);
            } else {
                MessageReference ref = messageViewFragment.getMessageReference();
                boolean initialized = (messageListFragment != null &&
                        messageListFragment.isLoadFinished());
                boolean canDoPrev = (initialized && !messageListFragment.isFirst(ref));
                boolean canDoNext = (initialized && !messageListFragment.isLast(ref));

                MenuItem prev = menu.findItem(R.id.previous_message);
                prev.setEnabled(canDoPrev);
                prev.getIcon().setAlpha(canDoPrev ? 255 : 127);

                MenuItem next = menu.findItem(R.id.next_message);
                next.setEnabled(canDoNext);
                next.getIcon().setAlpha(canDoNext ? 255 : 127);
            }

            MenuItem toggleTheme = menu.findItem(R.id.toggle_message_view_theme);
            if (K9.isFixedMessageViewTheme()) {
                toggleTheme.setVisible(false);
            } else {
                // Set title of menu item to switch to dark/light theme
                if (getThemeManager().getMessageViewTheme() == Theme.DARK) {
                    toggleTheme.setTitle(R.string.message_view_theme_action_light);
                } else {
                    toggleTheme.setTitle(R.string.message_view_theme_action_dark);
                }
                toggleTheme.setVisible(true);
            }

            // Set title of menu item to toggle the read state of the currently displayed message
            int[] drawableAttr;
            if (messageViewFragment.isMessageRead()) {
                menu.findItem(R.id.toggle_unread).setTitle(R.string.mark_as_unread_action);
                drawableAttr = new int[] { R.attr.iconActionMarkAsUnread };
            } else {
                menu.findItem(R.id.toggle_unread).setTitle(R.string.mark_as_read_action);
                drawableAttr = new int[] { R.attr.iconActionMarkAsRead };
            }
            TypedArray ta = obtainStyledAttributes(drawableAttr);
            menu.findItem(R.id.toggle_unread).setIcon(ta.getDrawable(0));
            ta.recycle();

            menu.findItem(R.id.delete).setVisible(K9.isMessageViewDeleteActionVisible());

            /*
             * Set visibility of copy, move, archive, spam in action bar and refile submenu
             */
            if (messageViewFragment.isCopyCapable()) {
                menu.findItem(R.id.copy).setVisible(K9.isMessageViewCopyActionVisible());
                menu.findItem(R.id.refile_copy).setVisible(true);
            } else {
                menu.findItem(R.id.copy).setVisible(false);
                menu.findItem(R.id.refile_copy).setVisible(false);
            }

            if (messageViewFragment.isMoveCapable()) {
                boolean canMessageBeArchived = messageViewFragment.canMessageBeArchived();
                boolean canMessageBeMovedToSpam = messageViewFragment.canMessageBeMovedToSpam();

                menu.findItem(R.id.move).setVisible(K9.isMessageViewMoveActionVisible());
                menu.findItem(R.id.archive).setVisible(canMessageBeArchived &&
                        K9.isMessageViewArchiveActionVisible());
                menu.findItem(R.id.spam).setVisible(canMessageBeMovedToSpam &&
                        K9.isMessageViewSpamActionVisible());

                menu.findItem(R.id.refile_move).setVisible(true);
                menu.findItem(R.id.refile_archive).setVisible(canMessageBeArchived);
                menu.findItem(R.id.refile_spam).setVisible(canMessageBeMovedToSpam);
            } else {
                menu.findItem(R.id.move).setVisible(false);
                menu.findItem(R.id.archive).setVisible(false);
                menu.findItem(R.id.spam).setVisible(false);

                menu.findItem(R.id.refile).setVisible(false);
            }

            if (messageViewFragment.allHeadersVisible()) {
                menu.findItem(R.id.show_headers).setVisible(false);
            } else {
                menu.findItem(R.id.hide_headers).setVisible(false);
            }

            if (messageViewFragment.getRenderPlainFormat()) {
                menu.findItem(R.id.set_format_plain).setVisible(false);
            } else {
                menu.findItem(R.id.set_format_html).setVisible(false);
            }
        }


        /*
         * Set visibility of menu items related to the message list
         */

        // Hide both search menu items by default and enable one when appropriate
        menu.findItem(R.id.search).setVisible(false);
        menu.findItem(R.id.search_remote).setVisible(false);

        if (displayMode == DisplayMode.MESSAGE_VIEW || messageListFragment == null ||
                !messageListFragment.isInitialized()) {
            menu.findItem(R.id.set_sort).setVisible(false);
            menu.findItem(R.id.select_all).setVisible(false);
            menu.findItem(R.id.send_messages).setVisible(false);
            menu.findItem(R.id.expunge).setVisible(false);
            menu.findItem(R.id.empty_trash).setVisible(false);
            menu.findItem(R.id.mark_all_as_read).setVisible(false);
        } else {
            menu.findItem(R.id.set_sort).setVisible(true);
            menu.findItem(R.id.select_all).setVisible(true);
            menu.findItem(R.id.compose).setVisible(true);
            menu.findItem(R.id.mark_all_as_read).setVisible(
                    messageListFragment.isMarkAllAsReadSupported());

            if (!messageListFragment.isSingleAccountMode()) {
                menu.findItem(R.id.expunge).setVisible(false);
                menu.findItem(R.id.send_messages).setVisible(false);
            } else {
                menu.findItem(R.id.send_messages).setVisible(messageListFragment.isOutbox());
                menu.findItem(R.id.expunge).setVisible(messageListFragment.isRemoteFolder() &&
                        messageListFragment.shouldShowExpungeAction());
            }
            menu.findItem(R.id.empty_trash).setVisible(messageListFragment.isShowingTrashFolder());

            // If this is an explicit local search, show the option to search on the server
            if (!messageListFragment.isRemoteSearch() &&
                    messageListFragment.isRemoteSearchAllowed()) {
                menu.findItem(R.id.search_remote).setVisible(true);
            } else if (!messageListFragment.isManualSearch()) {
                menu.findItem(R.id.search).setVisible(true);
            }
        }
    }

    protected void onAccountUnavailable() {
        //TODO: Find better way to handle this case.
        Timber.i("Account is unavailable right now: " + account);
        finish();
    }

    public void setActionBarTitle(String title) {
        actionBar.setTitle(title);
    }

    @Override
    public void setMessageListTitle(String title) {
        if (displayMode != DisplayMode.MESSAGE_VIEW) {
            setActionBarTitle(title);
        }
    }

    @Override
    public void setMessageListProgressEnabled(boolean enable) {
        progressBar.setVisibility(enable ? View.VISIBLE : View.INVISIBLE);
    }

    @Override
    public void setMessageListProgress(int progress) {
        progressBar.setProgress(progress);
    }

    @Override
    public void openMessage(MessageReference messageReference) {
        Account account = preferences.getAccount(messageReference.getAccountUuid());
        String folderServerId = messageReference.getFolderServerId();

        if (folderServerId.equals(account.getDraftsFolder())) {
            MessageActions.actionEditDraft(this, messageReference);
        } else {
            if (messageListFragment != null) {
                messageListFragment.setActiveMessage(messageReference);
            }

            MessageViewFragment fragment = MessageViewFragment.newInstance(messageReference);
            FragmentTransaction fragmentTransaction = getSupportFragmentManager().beginTransaction();
            fragmentTransaction.replace(R.id.message_view_container, fragment, FRAGMENT_TAG_MESSAGE_VIEW);
            fragmentTransaction.commit();
            messageViewFragment = fragment;

            if (displayMode != DisplayMode.SPLIT_VIEW) {
                showMessageView();
            }
        }
    }

    @Override
    public void onForward(MessageReference messageReference, Parcelable decryptionResultForReply) {
        MessageActions.actionForward(this, messageReference, decryptionResultForReply);
    }

    @Override
    public void onForwardAsAttachment(MessageReference messageReference, Parcelable decryptionResultForReply) {
        MessageActions.actionForwardAsAttachment(this, messageReference, decryptionResultForReply);
    }

    @Override
    public void onEditAsNewMessage(MessageReference messageReference) {
        MessageActions.actionEditDraft(this, messageReference);
    }

    @Override
    public void onReply(MessageReference messageReference, Parcelable decryptionResultForReply) {
        MessageActions.actionReply(this, messageReference, false, decryptionResultForReply);
    }

    @Override
    public void onReplyAll(MessageReference messageReference, Parcelable decryptionResultForReply) {
        MessageActions.actionReply(this, messageReference, true, decryptionResultForReply);
    }

    @Override
    public void onCompose(Account account) {
        MessageActions.actionCompose(this, account);
    }

    @Override
    public void onBackStackChanged() {
        findFragments();

        if (isDrawerEnabled() && !isAdditionalMessageListDisplayed()) {
            unlockDrawer();
        }

        if (displayMode == DisplayMode.SPLIT_VIEW) {
            showMessageViewPlaceHolder();
        }

        configureMenu(menu);
    }

    private final class StorageListenerImplementation implements StorageManager.StorageListener {
        @Override
        public void onUnmount(String providerId) {
            if (account != null && providerId.equals(account.getLocalStorageProviderId())) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        onAccountUnavailable();
                    }
                });
            }
        }

        @Override
        public void onMount(String providerId) {
            // no-op
        }
    }

    private void addMessageListFragment(MessageListFragment fragment, boolean addToBackStack) {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();

        ft.replace(R.id.message_list_container, fragment);
        if (addToBackStack)
            ft.addToBackStack(null);

        messageListFragment = fragment;

        if (isDrawerEnabled()) {
            lockDrawer();
        }

        int transactionId = ft.commit();
        if (transactionId >= 0 && firstBackStackId < 0) {
            firstBackStackId = transactionId;
        }
    }

    @Override
    public boolean startSearch(Account account, String folderServerId) {
        // If this search was started from a MessageList of a single folder, pass along that folder info
        // so that we can enable remote search.
        if (account != null && folderServerId != null) {
            final Bundle appData = new Bundle();
            appData.putString(EXTRA_SEARCH_ACCOUNT, account.getUuid());
            appData.putString(EXTRA_SEARCH_FOLDER, folderServerId);
            startSearch(null, false, appData, false);
        } else {
            // TODO Handle the case where we're searching from within a search result.
            startSearch(null, false, null, false);
        }

        return true;
    }

    @Override
    public void showThread(Account account, String folderServerId, long threadRootId) {
        showMessageViewPlaceHolder();

        LocalSearch tmpSearch = new LocalSearch();
        tmpSearch.addAccountUuid(account.getUuid());
        tmpSearch.and(SearchField.THREAD_ID, String.valueOf(threadRootId), Attribute.EQUALS);

        initializeFromLocalSearch(tmpSearch);

        MessageListFragment fragment = MessageListFragment.newInstance(tmpSearch, true, false);
        addMessageListFragment(fragment, true);
    }

    private void showMessageViewPlaceHolder() {
        removeMessageViewFragment();

        // Add placeholder fragment if necessary
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (fragmentManager.findFragmentByTag(FRAGMENT_TAG_PLACEHOLDER) == null) {
            FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
            fragmentTransaction.replace(R.id.message_view_container, messageViewPlaceHolder, FRAGMENT_TAG_PLACEHOLDER);
            fragmentTransaction.commit();
        }

        messageListFragment.setActiveMessage(null);
    }

    /**
     * Remove MessageViewFragment if necessary.
     */
    private void removeMessageViewFragment() {
        if (messageViewFragment != null) {
            FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
            ft.remove(messageViewFragment);
            messageViewFragment = null;
            ft.commit();

            showDefaultTitleView();
        }
    }

    private void removeMessageListFragment() {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        ft.remove(messageListFragment);
        messageListFragment = null;
        ft.commit();
    }

    @Override
    public void remoteSearchStarted() {
        // Remove action button for remote search
        configureMenu(menu);
    }

    @Override
    public void goBack() {
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (displayMode == DisplayMode.MESSAGE_VIEW) {
            showMessageList();
        } else if (fragmentManager.getBackStackEntryCount() > 0) {
            fragmentManager.popBackStack();
        } else {
            finish();
        }
    }

    @Override
    public void showNextMessageOrReturn() {
        if (K9.isMessageViewReturnToList() || !showLogicalNextMessage()) {
            if (displayMode == DisplayMode.SPLIT_VIEW) {
                showMessageViewPlaceHolder();
            } else {
                showMessageList();
            }
        }
    }

    /**
     * Shows the next message in the direction the user was displaying messages.
     *
     * @return {@code true}
     */
    private boolean showLogicalNextMessage() {
        boolean result = false;
        if (lastDirection == NEXT) {
            result = showNextMessage();
        } else if (lastDirection == PREVIOUS) {
            result = showPreviousMessage();
        }

        if (!result) {
            result = showNextMessage() || showPreviousMessage();
        }

        return result;
    }

    @Override
    public void setProgress(boolean enable) {
        setProgressBarIndeterminateVisibility(enable);
    }

    private boolean showNextMessage() {
        MessageReference ref = messageViewFragment.getMessageReference();
        if (ref != null) {
            if (messageListFragment.openNext(ref)) {
                lastDirection = NEXT;
                return true;
            }
        }
        return false;
    }

    private boolean showPreviousMessage() {
        MessageReference ref = messageViewFragment.getMessageReference();
        if (ref != null) {
            if (messageListFragment.openPrevious(ref)) {
                lastDirection = PREVIOUS;
                return true;
            }
        }
        return false;
    }

    private void showMessageList() {
        messageListWasDisplayed = true;
        displayMode = DisplayMode.MESSAGE_LIST;
        viewSwitcher.showFirstView();

        messageListFragment.setActiveMessage(null);

        if (isDrawerEnabled()) {
            if (isAdditionalMessageListDisplayed()) {
                lockDrawer();
            } else {
                unlockDrawer();
            }
        }

        showDefaultTitleView();
        configureMenu(menu);
    }

    private void showMessageView() {
        displayMode = DisplayMode.MESSAGE_VIEW;

        if (!messageListWasDisplayed) {
            viewSwitcher.setAnimateFirstView(false);
        }
        viewSwitcher.showSecondView();

        if (isDrawerEnabled()) {
            lockDrawer();
        }

        showMessageTitleView();
        configureMenu(menu);
    }

    @Override
    public void updateMenu() {
        invalidateOptionsMenu();
    }

    @Override
    public void disableDeleteAction() {
        menu.findItem(R.id.delete).setEnabled(false);
    }

    private void onToggleTheme() {
        getThemeManager().toggleMessageViewTheme();
        recreate();
    }

    private void showDefaultTitleView() {
        if (messageListFragment != null) {
            messageListFragment.updateTitle();
        }
    }

    private void showMessageTitleView() {
        setActionBarTitle("");
    }

    @Override
    public void onSwitchComplete(int displayedChild) {
        if (displayedChild == 0) {
            removeMessageViewFragment();
        }
    }

    @Override
    public void startIntentSenderForResult(IntentSender intent, int requestCode, Intent fillInIntent,
            int flagsMask, int flagsValues, int extraFlags) throws SendIntentException {
        requestCode |= REQUEST_MASK_PENDING_INTENT;
        super.startIntentSenderForResult(intent, requestCode, fillInIntent, flagsMask, flagsValues, extraFlags);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if ((requestCode & REQUEST_MASK_PENDING_INTENT) == REQUEST_MASK_PENDING_INTENT) {
            requestCode ^= REQUEST_MASK_PENDING_INTENT;
            if (messageViewFragment != null) {
                messageViewFragment.onPendingIntentResult(requestCode, resultCode, data);
            }
        }
    }

    private boolean isAdditionalMessageListDisplayed() {
        FragmentManager fragmentManager = getSupportFragmentManager();
        return fragmentManager.getBackStackEntryCount() > 0;
    }

    private void lockDrawer() {
        drawer.lock();
        drawerToggle.setDrawerIndicatorEnabled(false);
    }

    private void unlockDrawer() {
        drawer.unlock();
        drawerToggle.setDrawerIndicatorEnabled(true);
    }

    private void initializeFromLocalSearch(LocalSearch search) {
        this.search = search;
        singleFolderMode = false;

        if (search.searchAllAccounts()) {
            account = null;
        } else {
            String[] accountUuids = search.getAccountUuids();
            if (accountUuids.length == 1) {
                account = preferences.getAccount(accountUuids[0]);
                List<String> folderServerIds = search.getFolderServerIds();
                singleFolderMode = folderServerIds.size() == 1;
            } else {
                account = null;
            }
        }

        configureDrawer();
    }

    private void configureDrawer() {
        if (drawer == null)
            return;

        if (singleFolderMode) {
            drawer.selectFolder(search.getFolderServerIds().get(0));
        } else if (search.getId().equals(SearchAccount.UNIFIED_INBOX)) {
            drawer.selectUnifiedInbox();
        } else {
            drawer.deselect();
        }
    }
}
