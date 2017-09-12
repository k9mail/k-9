package com.fsck.k9.activity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.text.TextUtils.TruncateAt;
import android.text.format.DateUtils;
import timber.log.Timber;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toast;

import com.fsck.k9.Account;
import com.fsck.k9.Account.FolderMode;
import com.fsck.k9.AccountStats;
import com.fsck.k9.BaseAccount;
import com.fsck.k9.FontSizes;
import com.fsck.k9.K9;
import com.fsck.k9.Preferences;
import com.fsck.k9.R;
import com.fsck.k9.activity.compose.MessageActions;
import com.fsck.k9.activity.setup.AccountSettings;
import com.fsck.k9.activity.setup.FolderSettings;
import com.fsck.k9.activity.setup.Prefs;
import com.fsck.k9.controller.MessagingController;
import com.fsck.k9.controller.MessagingListener;
import com.fsck.k9.controller.SimpleMessagingListener;
import com.fsck.k9.helper.SizeFormatter;
import com.fsck.k9.mail.power.TracingPowerManager;
import com.fsck.k9.mail.power.TracingPowerManager.TracingWakeLock;
import com.fsck.k9.mail.Folder;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mailstore.LocalFolder;
import com.fsck.k9.search.LocalSearch;
import com.fsck.k9.search.SearchSpecification.Attribute;
import com.fsck.k9.search.SearchSpecification.SearchField;
import com.fsck.k9.service.MailService;

import de.cketti.library.changelog.ChangeLog;

/**
 * FolderList is the primary user interface for the program. This
 * Activity shows list of the Account's folders
 */

public class FolderList extends K9ListActivity {
    private static final String EXTRA_ACCOUNT = "account";

    private static final String EXTRA_FROM_SHORTCUT = "fromShortcut";

    private static final boolean REFRESH_REMOTE = true;

    private ListView mListView;

    private FolderListAdapter mAdapter;

    private LayoutInflater mInflater;

    private Account mAccount;

    private FolderListHandler mHandler = new FolderListHandler();

    private int mUnreadMessageCount;

    private FontSizes mFontSizes = K9.getFontSizes();
    private Context context;

    private MenuItem mRefreshMenuItem;
    private View mActionBarProgressView;
    private ActionBar mActionBar;

    private TextView mActionBarTitle;
    private TextView mActionBarSubTitle;
    private TextView mActionBarUnread;

    class FolderListHandler extends Handler {

        public void refreshTitle() {
            runOnUiThread(new Runnable() {
                public void run() {
                    mActionBarTitle.setText(getString(R.string.folders_title));

                    if (mUnreadMessageCount == 0) {
                        mActionBarUnread.setVisibility(View.GONE);
                    } else {
                        mActionBarUnread.setText(String.format("%d", mUnreadMessageCount));
                        mActionBarUnread.setVisibility(View.VISIBLE);
                    }

                    String operation = mAdapter.mListener.getOperation(FolderList.this);
                    if (operation.length() < 1) {
                        mActionBarSubTitle.setText(mAccount.getEmail());
                    } else {
                        mActionBarSubTitle.setText(operation);
                    }
                }
            });
        }


        public void newFolders(final List<FolderInfoHolder> newFolders) {
            runOnUiThread(new Runnable() {
                public void run() {
                    mAdapter.mFolders.clear();
                    mAdapter.mFolders.addAll(newFolders);
                    mAdapter.mFilteredFolders = mAdapter.mFolders;
                    mHandler.dataChanged();
                }
            });
        }

        public void workingAccount(final int res) {
            runOnUiThread(new Runnable() {
                public void run() {
                    String toastText = getString(res, mAccount.getDescription());
                    Toast toast = Toast.makeText(getApplication(), toastText, Toast.LENGTH_SHORT);
                    toast.show();
                }
            });
        }

        public void accountSizeChanged(final long oldSize, final long newSize) {
            runOnUiThread(new Runnable() {
                public void run() {
                    String toastText = getString(R.string.account_size_changed, mAccount.getDescription(), SizeFormatter.formatSize(getApplication(), oldSize), SizeFormatter.formatSize(getApplication(), newSize));

                    Toast toast = Toast.makeText(getApplication(), toastText, Toast.LENGTH_LONG);
                    toast.show();
                }
            });
        }

        public void folderLoading(final String folder, final boolean loading) {
            runOnUiThread(new Runnable() {
                public void run() {
                    FolderInfoHolder folderHolder = mAdapter.getFolder(folder);


                    if (folderHolder != null) {
                        folderHolder.loading = loading;
                    }

                }
            });
        }

        public void progress(final boolean progress) {
            // Make sure we don't try this before the menu is initialized
            // this could happen while the activity is initialized.
            if (mRefreshMenuItem == null) {
                return;
            }

            runOnUiThread(new Runnable() {
                public void run() {
                    if (progress) {
                        mRefreshMenuItem.setActionView(mActionBarProgressView);
                    } else {
                        mRefreshMenuItem.setActionView(null);
                    }
                }
            });

        }

        public void dataChanged() {
            runOnUiThread(new Runnable() {
                public void run() {
                    mAdapter.notifyDataSetChanged();
                }
            });
        }
    }

    /**
    * This class is responsible for reloading the list of local messages for a
    * given folder, notifying the adapter that the message have been loaded and
    * queueing up a remote update of the folder.
     */

    private void checkMail(FolderInfoHolder folder) {
        TracingPowerManager pm = TracingPowerManager.getPowerManager(this);
        final TracingWakeLock wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FolderList checkMail");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(K9.WAKE_LOCK_TIMEOUT);
        MessagingListener listener = new SimpleMessagingListener() {
            @Override
            public void synchronizeMailboxFinished(Account account, String folder, int totalMessagesInMailbox, int numNewMessages) {
                if (!account.equals(mAccount)) {
                    return;
                }
                wakeLock.release();
            }

            @Override
            public void synchronizeMailboxFailed(Account account, String folder,
            String message) {
                if (!account.equals(mAccount)) {
                    return;
                }
                wakeLock.release();
            }
        };
        MessagingController.getInstance(getApplication()).synchronizeMailbox(mAccount, folder.name, listener);
        sendMail(mAccount);
    }

    public static Intent actionHandleAccountIntent(Context context, Account account, boolean fromShortcut) {
        Intent intent = new Intent(context, FolderList.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(EXTRA_ACCOUNT, account.getUuid());

        if (fromShortcut) {
            intent.putExtra(EXTRA_FROM_SHORTCUT, true);
        }

        return intent;
    }

    public static void actionHandleAccount(Context context, Account account) {
        Intent intent = actionHandleAccountIntent(context, account, false);
        context.startActivity(intent);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (UpgradeDatabases.actionUpgradeDatabases(this, getIntent())) {
            finish();
            return;
        }

        mActionBarProgressView = getActionBarProgressView();
        mActionBar = getActionBar();
        initializeActionBar();
        setContentView(R.layout.folder_list);
        mListView = getListView();
        mListView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        mListView.setLongClickable(true);
        mListView.setFastScrollEnabled(true);
        mListView.setScrollingCacheEnabled(false);
        mListView.setOnItemClickListener(new OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onOpenFolder(((FolderInfoHolder)mAdapter.getItem(position)).name);
            }
        });
        registerForContextMenu(mListView);

        mListView.setSaveEnabled(true);

        mInflater = getLayoutInflater();

        context = this;

        onNewIntent(getIntent());
        if (isFinishing()) {
            /*
             * onNewIntent() may call finish(), but execution will still continue here.
             * We return now because we don't want to display the changelog which can
             * result in a leaked window error.
             */
            return;
        }

        ChangeLog cl = new ChangeLog(this);
        if (cl.isFirstRun()) {
            cl.getLogDialog().show();
        }
    }

    @SuppressLint("InflateParams")
    private View getActionBarProgressView() {
        return getLayoutInflater().inflate(R.layout.actionbar_indeterminate_progress_actionview, null);
    }

    private void initializeActionBar() {
        mActionBar.setDisplayShowCustomEnabled(true);
        mActionBar.setCustomView(R.layout.actionbar_custom);

        View customView = mActionBar.getCustomView();
        mActionBarTitle = (TextView) customView.findViewById(R.id.actionbar_title_first);
        mActionBarSubTitle = (TextView) customView.findViewById(R.id.actionbar_title_sub);
        mActionBarUnread = (TextView) customView.findViewById(R.id.actionbar_unread_count);

        mActionBar.setDisplayHomeAsUpEnabled(true);
    }

    @Override
    public void onNewIntent(Intent intent) {
        setIntent(intent); // onNewIntent doesn't autoset our "internal" intent

        mUnreadMessageCount = 0;
        String accountUuid = intent.getStringExtra(EXTRA_ACCOUNT);
        mAccount = Preferences.getPreferences(this).getAccount(accountUuid);

        if (mAccount == null) {
            /*
             * This can happen when a launcher shortcut is created for an
             * account, and then the account is deleted or data is wiped, and
             * then the shortcut is used.
             */
            finish();
            return;
        }

        if (intent.getBooleanExtra(EXTRA_FROM_SHORTCUT, false) &&
                   !K9.FOLDER_NONE.equals(mAccount.getAutoExpandFolderName())) {
            onOpenFolder(mAccount.getAutoExpandFolderName());
            finish();
        } else {
            initializeActivityView();
        }
    }

    private void initializeActivityView() {
        mAdapter = new FolderListAdapter();
        restorePreviousData();

        setListAdapter(mAdapter);
        getListView().setTextFilterEnabled(mAdapter.getFilter() != null); // should never be false but better safe then sorry
    }

    @SuppressWarnings("unchecked")
    private void restorePreviousData() {
        final Object previousData = getLastNonConfigurationInstance();

        if (previousData != null) {
            mAdapter.mFolders = (ArrayList<FolderInfoHolder>) previousData;
            mAdapter.mFilteredFolders = Collections.unmodifiableList(mAdapter.mFolders);
        }
    }


    @Override public Object onRetainNonConfigurationInstance() {
        return (mAdapter == null) ? null : mAdapter.mFolders;
    }

    @Override public void onPause() {
        super.onPause();
        MessagingController.getInstance(getApplication()).removeListener(mAdapter.mListener);
        mAdapter.mListener.onPause(this);
    }

    /**
    * On resume we refresh the folder list (in the background) and we refresh the
    * messages for any folder that is currently open. This guarantees that things
    * like unread message count and read status are updated.
     */
    @Override public void onResume() {
        super.onResume();

        if (!mAccount.isAvailable(this)) {
            Timber.i("account unavaliabale, not showing folder-list but account-list");
            Accounts.listAccounts(this);
            finish();
            return;
        }
        if (mAdapter == null)
            initializeActivityView();

        mHandler.refreshTitle();

        MessagingController.getInstance(getApplication()).addListener(mAdapter.mListener);
        //mAccount.refresh(Preferences.getPreferences(this));
        MessagingController.getInstance(getApplication()).getAccountStats(this, mAccount, mAdapter.mListener);

        onRefresh(!REFRESH_REMOTE);

        MessagingController.getInstance(getApplication()).cancelNotificationsForAccount(mAccount);
        mAdapter.mListener.onResume(this);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        //Shortcuts that work no matter what is selected
        switch (keyCode) {
        case KeyEvent.KEYCODE_Q: {
            onAccounts();
            return true;
        }

        case KeyEvent.KEYCODE_S: {
            onEditAccount();
            return true;
        }

        case KeyEvent.KEYCODE_H: {
            Toast toast = Toast.makeText(this, R.string.folder_list_help_key, Toast.LENGTH_LONG);
            toast.show();
            return true;
        }

        case KeyEvent.KEYCODE_1: {
            setDisplayMode(FolderMode.FIRST_CLASS);
            return true;
        }
        case KeyEvent.KEYCODE_2: {
            setDisplayMode(FolderMode.FIRST_AND_SECOND_CLASS);
            return true;
        }
        case KeyEvent.KEYCODE_3: {
            setDisplayMode(FolderMode.NOT_SECOND_CLASS);
            return true;
        }
        case KeyEvent.KEYCODE_4: {
            setDisplayMode(FolderMode.ALL);
            return true;
        }
        }//switch


        return super.onKeyDown(keyCode, event);
    }//onKeyDown

    private void setDisplayMode(FolderMode newMode) {
        mAccount.setFolderDisplayMode(newMode);
        mAccount.save(Preferences.getPreferences(this));
        if (mAccount.getFolderPushMode() != FolderMode.NONE) {
            MailService.actionRestartPushers(this, null);
        }
        mAdapter.getFilter().filter(null);
        onRefresh(false);
    }


    private void onRefresh(final boolean forceRemote) {

        MessagingController.getInstance(getApplication()).listFolders(mAccount, forceRemote, mAdapter.mListener);

    }

    private void onEditPrefs() {
        Prefs.actionPrefs(this);
    }
    private void onEditAccount() {
        AccountSettings.actionSettings(this, mAccount);
    }

    private void onAccounts() {
        Accounts.listAccounts(this);
        finish();
    }

    private void onEmptyTrash(final Account account) {
        mHandler.dataChanged();

        MessagingController.getInstance(getApplication()).emptyTrash(account, null);
    }

    private void onClearFolder(Account account, String folderName) {
        MessagingController.getInstance(getApplication()).clearFolder(account, folderName, mAdapter.mListener);
    }

    private void sendMail(Account account) {
        MessagingController.getInstance(getApplication()).sendPendingMessages(account, mAdapter.mListener);
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case android.R.id.home:
            onAccounts();

            return true;

        case R.id.search:
            onSearchRequested();

            return true;

        case R.id.compose:
            MessageActions.actionCompose(this, mAccount);

            return true;

        case R.id.check_mail:
            MessagingController.getInstance(getApplication()).checkMail(this, mAccount, true, true, mAdapter.mListener);

            return true;

        case R.id.send_messages:
            MessagingController.getInstance(getApplication()).sendPendingMessages(mAccount, null);

            return true;

        case R.id.list_folders:
            onRefresh(REFRESH_REMOTE);

            return true;

        case R.id.account_settings:
            onEditAccount();

            return true;

        case R.id.app_settings:
            onEditPrefs();

            return true;

        case R.id.empty_trash:
            onEmptyTrash(mAccount);

            return true;

        case R.id.compact:
            onCompact(mAccount);

            return true;

        case R.id.display_1st_class: {
            setDisplayMode(FolderMode.FIRST_CLASS);
            return true;
        }
        case R.id.display_1st_and_2nd_class: {
            setDisplayMode(FolderMode.FIRST_AND_SECOND_CLASS);
            return true;
        }
        case R.id.display_not_second_class: {
            setDisplayMode(FolderMode.NOT_SECOND_CLASS);
            return true;
        }
        case R.id.display_all: {
            setDisplayMode(FolderMode.ALL);
            return true;
        }
        default:
            return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public boolean onSearchRequested() {
         Bundle appData = new Bundle();
         appData.putString(MessageList.EXTRA_SEARCH_ACCOUNT, mAccount.getUuid());
         startSearch(null, false, appData, false);
         return true;
     }

    private void onOpenFolder(String folder) {
        LocalSearch search = new LocalSearch(folder);
        search.addAccountUuid(mAccount.getUuid());
        search.addAllowedFolder(folder);
        MessageList.actionDisplaySearch(this, search, false, false);
    }

    private void onCompact(Account account) {
        mHandler.workingAccount(R.string.compacting_account);
        MessagingController.getInstance(getApplication()).compact(account, null);
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.folder_list_option, menu);
        mRefreshMenuItem = menu.findItem(R.id.check_mail);
        configureFolderSearchView(menu);
        return true;
    }

    private void configureFolderSearchView(Menu menu) {
        final MenuItem folderMenuItem = menu.findItem(R.id.filter_folders);
        final SearchView folderSearchView = (SearchView) folderMenuItem.getActionView();
        folderSearchView.setQueryHint(getString(R.string.folder_list_filter_hint));
        folderSearchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {

            @Override
            public boolean onQueryTextSubmit(String query) {
                folderMenuItem.collapseActionView();
                mActionBarTitle.setText(getString(R.string.filter_folders_action));
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                mAdapter.getFilter().filter(newText);
                return true;
            }
        });

        folderSearchView.setOnCloseListener(new SearchView.OnCloseListener() {

            @Override
            public boolean onClose() {
                mActionBarTitle.setText(getString(R.string.folders_title));
                return false;
            }
        });
    }

    @Override public boolean onContextItemSelected(android.view.MenuItem item) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item .getMenuInfo();
        FolderInfoHolder folder = (FolderInfoHolder) mAdapter.getItem(info.position);

        switch (item.getItemId()) {
        case R.id.clear_local_folder:
            onClearFolder(mAccount, folder.name);
            break;
        case R.id.refresh_folder:
            checkMail(folder);
            break;
        case R.id.folder_settings:
            FolderSettings.actionSettings(this, mAccount, folder.name);
            break;
        }

        return super.onContextItemSelected(item);
    }

    @Override public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        getMenuInflater().inflate(R.menu.folder_context, menu);

        FolderInfoHolder folder = (FolderInfoHolder) mAdapter.getItem(info.position);

        menu.setHeaderTitle(folder.displayName);
    }

    class FolderListAdapter extends BaseAdapter implements Filterable {
        private List<FolderInfoHolder> mFolders = new ArrayList<FolderInfoHolder>();
        private List<FolderInfoHolder> mFilteredFolders = Collections.unmodifiableList(mFolders);
        private Filter mFilter = new FolderListFilter();

        public Object getItem(long position) {
            return getItem((int)position);
        }

        public Object getItem(int position) {
            return mFilteredFolders.get(position);
        }


        public long getItemId(int position) {
            return mFilteredFolders.get(position).folder.getName().hashCode() ;
        }

        public int getCount() {
            return mFilteredFolders.size();
        }

        @Override
        public boolean isEnabled(int item) {
            return true;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return true;
        }

        private ActivityListener mListener = new ActivityListener() {
            @Override
            public void informUserOfStatus() {
                mHandler.refreshTitle();
                mHandler.dataChanged();
            }
            @Override
            public void accountStatusChanged(BaseAccount account, AccountStats stats) {
                if (!account.equals(mAccount)) {
                    return;
                }
                if (stats == null) {
                    return;
                }
                mUnreadMessageCount = stats.unreadMessageCount;
                mHandler.refreshTitle();
            }

            @Override
            public void listFoldersStarted(Account account) {
                if (account.equals(mAccount)) {
                    mHandler.progress(true);
                }
                super.listFoldersStarted(account);

            }

            @Override
            public void listFoldersFailed(Account account, String message) {
                if (account.equals(mAccount)) {
                    mHandler.progress(false);
                    Toast.makeText(context, R.string.fetching_folders_failed, Toast.LENGTH_SHORT).show();
                }
                super.listFoldersFailed(account, message);
            }

            @Override
            public void listFoldersFinished(Account account) {
                if (account.equals(mAccount)) {

                    mHandler.progress(false);
                    MessagingController.getInstance(getApplication()).refreshListener(mAdapter.mListener);
                    mHandler.dataChanged();
                }
                super.listFoldersFinished(account);

            }

            @Override
            public void listFolders(Account account, List<LocalFolder> folders) {
                if (account.equals(mAccount)) {

                    List<FolderInfoHolder> newFolders = new LinkedList<FolderInfoHolder>();
                    List<FolderInfoHolder> topFolders = new LinkedList<FolderInfoHolder>();

                    Account.FolderMode aMode = account.getFolderDisplayMode();
                    for (LocalFolder folder : folders) {
                        Folder.FolderClass fMode = folder.getDisplayClass();

                        if ((aMode == FolderMode.FIRST_CLASS && fMode != Folder.FolderClass.FIRST_CLASS)
                                || (aMode == FolderMode.FIRST_AND_SECOND_CLASS &&
                                    fMode != Folder.FolderClass.FIRST_CLASS &&
                                    fMode != Folder.FolderClass.SECOND_CLASS)
                        || (aMode == FolderMode.NOT_SECOND_CLASS && fMode == Folder.FolderClass.SECOND_CLASS)) {
                            continue;
                        }

                        FolderInfoHolder holder = null;

                        int folderIndex = getFolderIndex(folder.getName());
                        if (folderIndex >= 0) {
                            holder = (FolderInfoHolder) getItem(folderIndex);
                        }

                        if (holder == null) {
                            holder = new FolderInfoHolder(context, folder, mAccount, -1);
                        } else {
                            holder.populate(context, folder, mAccount, -1);

                        }
                        if (folder.isInTopGroup()) {
                            topFolders.add(holder);
                        } else {
                            newFolders.add(holder);
                        }
                    }
                    Collections.sort(newFolders);
                    Collections.sort(topFolders);
                    topFolders.addAll(newFolders);
                    mHandler.newFolders(topFolders);
                }
                super.listFolders(account, folders);
            }

            @Override
            public void synchronizeMailboxStarted(Account account, String folder) {
                super.synchronizeMailboxStarted(account, folder);
                if (account.equals(mAccount)) {

                    mHandler.progress(true);
                    mHandler.folderLoading(folder, true);
                    mHandler.dataChanged();
                }

            }

            @Override
            public void synchronizeMailboxFinished(Account account, String folder, int totalMessagesInMailbox, int numNewMessages) {
                super.synchronizeMailboxFinished(account, folder, totalMessagesInMailbox, numNewMessages);
                if (account.equals(mAccount)) {
                    mHandler.progress(false);
                    mHandler.folderLoading(folder, false);

                    refreshFolder(account, folder);
                }

            }

            private void refreshFolder(Account account, String folderName) {
                // There has to be a cheaper way to get at the localFolder object than this
                LocalFolder localFolder = null;
                try {
                    if (account != null && folderName != null) {
                        if (!account.isAvailable(FolderList.this)) {
                            Timber.i("not refreshing folder of unavailable account");
                            return;
                        }
                        localFolder = account.getLocalStore().getFolder(folderName);
                        FolderInfoHolder folderHolder = getFolder(folderName);
                        if (folderHolder != null) {
                            folderHolder.populate(context, localFolder, mAccount, -1);
                            folderHolder.flaggedMessageCount = -1;

                            mHandler.dataChanged();
                        }
                    }
                } catch (Exception e) {
                    Timber.e(e, "Exception while populating folder");
                } finally {
                    if (localFolder != null) {
                        localFolder.close();
                    }
                }

            }

            @Override
            public void synchronizeMailboxFailed(Account account, String folder, String message) {
                super.synchronizeMailboxFailed(account, folder, message);
                if (!account.equals(mAccount)) {
                    return;
                }


                mHandler.progress(false);

                mHandler.folderLoading(folder, false);

                //   String mess = truncateStatus(message);

                //   mHandler.folderStatus(folder, mess);
                FolderInfoHolder holder = getFolder(folder);

                if (holder != null) {
                    holder.lastChecked = 0;
                }

                mHandler.dataChanged();

            }

            @Override
            public void setPushActive(Account account, String folderName, boolean enabled) {
                if (!account.equals(mAccount)) {
                    return;
                }
                FolderInfoHolder holder = getFolder(folderName);

                if (holder != null) {
                    holder.pushActive = enabled;

                    mHandler.dataChanged();
                }
            }


            @Override
            public void messageDeleted(Account account, String folder, Message message) {
                synchronizeMailboxRemovedMessage(account, folder, message);
            }

            @Override
            public void emptyTrashCompleted(Account account) {
                if (account.equals(mAccount)) {
                    refreshFolder(account, mAccount.getTrashFolderName());
                }
            }

            @Override
            public void folderStatusChanged(Account account, String folderName, int unreadMessageCount) {
                if (account.equals(mAccount)) {
                    refreshFolder(account, folderName);
                    informUserOfStatus();
                }
            }

            @Override
            public void sendPendingMessagesCompleted(Account account) {
                super.sendPendingMessagesCompleted(account);
                if (account.equals(mAccount)) {
                    refreshFolder(account, mAccount.getOutboxFolderName());
                }
            }

            @Override
            public void sendPendingMessagesStarted(Account account) {
                super.sendPendingMessagesStarted(account);

                if (account.equals(mAccount)) {
                    mHandler.dataChanged();
                }
            }

            @Override
            public void sendPendingMessagesFailed(Account account) {
                super.sendPendingMessagesFailed(account);
                if (account.equals(mAccount)) {
                    refreshFolder(account, mAccount.getOutboxFolderName());
                }
            }

            @Override
            public void accountSizeChanged(Account account, long oldSize, long newSize) {
                if (account.equals(mAccount)) {
                    mHandler.accountSizeChanged(oldSize, newSize);
                }
            }
        };


        public int getFolderIndex(String folder) {
            FolderInfoHolder searchHolder = new FolderInfoHolder();
            searchHolder.name = folder;
            return   mFilteredFolders.indexOf(searchHolder);
        }

        public FolderInfoHolder getFolder(String folder) {
            FolderInfoHolder holder = null;

            int index = getFolderIndex(folder);
            if (index >= 0) {
                holder = (FolderInfoHolder) getItem(index);
                if (holder != null) {
                    return holder;
                }
            }
            return null;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            if (position <= getCount()) {
                return  getItemView(position, convertView, parent);
            } else {
                Timber.e("getView with illegal position=%d called! count is only %d", position, getCount());
                return null;
            }
        }

        public View getItemView(int itemPosition, View convertView, ViewGroup parent) {
            FolderInfoHolder folder = (FolderInfoHolder) getItem(itemPosition);
            View view;
            if (convertView != null) {
                view = convertView;
            } else {
                view = mInflater.inflate(R.layout.folder_list_item, parent, false);
            }

            FolderViewHolder holder = (FolderViewHolder) view.getTag();

            if (holder == null) {
                holder = new FolderViewHolder();
                holder.folderName = (TextView) view.findViewById(R.id.folder_name);
                holder.newMessageCount = (TextView) view.findViewById(R.id.new_message_count);
                holder.flaggedMessageCount = (TextView) view.findViewById(R.id.flagged_message_count);
                holder.newMessageCountWrapper = (View) view.findViewById(R.id.new_message_count_wrapper);
                holder.flaggedMessageCountWrapper = (View) view.findViewById(R.id.flagged_message_count_wrapper);
                holder.newMessageCountIcon = (View) view.findViewById(R.id.new_message_count_icon);
                holder.flaggedMessageCountIcon = (View) view.findViewById(R.id.flagged_message_count_icon);

                holder.folderStatus = (TextView) view.findViewById(R.id.folder_status);
                holder.activeIcons = (RelativeLayout) view.findViewById(R.id.active_icons);
                holder.chip = view.findViewById(R.id.chip);
                holder.folderListItemLayout = (LinearLayout)view.findViewById(R.id.folder_list_item_layout);
                holder.rawFolderName = folder.name;

                view.setTag(holder);
            }

            if (folder == null) {
                return view;
            }

            final String folderStatus;

            if (folder.loading) {
                folderStatus = getString(R.string.status_loading);
            } else if (folder.status != null) {
                folderStatus = folder.status;
            } else if (folder.lastChecked != 0) {
                long now = System.currentTimeMillis();
                int flags = DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR;
                CharSequence formattedDate;

                if (Math.abs(now - folder.lastChecked) > DateUtils.WEEK_IN_MILLIS) {
                    formattedDate = getString(R.string.preposition_for_date,
                            DateUtils.formatDateTime(context, folder.lastChecked, flags));
                } else {
                    formattedDate = DateUtils.getRelativeTimeSpanString(folder.lastChecked,
                            now, DateUtils.MINUTE_IN_MILLIS, flags);
                }

                folderStatus = getString(folder.pushActive
                        ? R.string.last_refresh_time_format_with_push
                        : R.string.last_refresh_time_format,
                        formattedDate);
            } else {
                folderStatus = null;
            }

            holder.folderName.setText(folder.displayName);
            if (folderStatus != null) {
                holder.folderStatus.setText(folderStatus);
                holder.folderStatus.setVisibility(View.VISIBLE);
            } else {
                holder.folderStatus.setVisibility(View.GONE);
            }

            if(folder.unreadMessageCount == -1) {
               folder.unreadMessageCount = 0;
                try {
                    folder.unreadMessageCount  = folder.folder.getUnreadMessageCount();
                } catch (Exception e) {
                    Timber.e("Unable to get unreadMessageCount for %s:%s", mAccount.getDescription(), folder.name);
                }
            }
            if (folder.unreadMessageCount > 0) {
                holder.newMessageCount.setText(String.format("%d", folder.unreadMessageCount));
                holder.newMessageCountWrapper.setOnClickListener(
                        createUnreadSearch(mAccount, folder));
                holder.newMessageCountWrapper.setVisibility(View.VISIBLE);
                holder.newMessageCountIcon.setBackgroundDrawable(
                        mAccount.generateColorChip(false, false).drawable());
            } else {
                holder.newMessageCountWrapper.setVisibility(View.GONE);
            }

            if (folder.flaggedMessageCount == -1) {
                folder.flaggedMessageCount = 0;
                try {
                    folder.flaggedMessageCount = folder.folder.getFlaggedMessageCount();
                } catch (Exception e) {
                    Timber.e("Unable to get flaggedMessageCount for %s:%s", mAccount.getDescription(), folder.name);
                }
            }

            if (K9.messageListStars() && folder.flaggedMessageCount > 0) {
                holder.flaggedMessageCount.setText(String.format("%d", folder.flaggedMessageCount));
                holder.flaggedMessageCountWrapper.setOnClickListener(
                        createFlaggedSearch(mAccount, folder));
                holder.flaggedMessageCountWrapper.setVisibility(View.VISIBLE);
                holder.flaggedMessageCountIcon.setBackgroundDrawable(
                        mAccount.generateColorChip(false, true).drawable());
            } else {
                holder.flaggedMessageCountWrapper.setVisibility(View.GONE);
            }

            holder.activeIcons.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    Toast toast = Toast.makeText(getApplication(), getString(R.string.tap_hint), Toast.LENGTH_SHORT);
                    toast.show();
                }
            });

            holder.chip.setBackgroundColor(mAccount.getChipColor());


            mFontSizes.setViewTextSize(holder.folderName, mFontSizes.getFolderName());

            if (K9.wrapFolderNames()) {
                holder.folderName.setEllipsize(null);
                holder.folderName.setSingleLine(false);
            }
            else {
                holder.folderName.setEllipsize(TruncateAt.START);
                holder.folderName.setSingleLine(true);
            }
            mFontSizes.setViewTextSize(holder.folderStatus, mFontSizes.getFolderStatus());

            return view;
        }

        private OnClickListener createFlaggedSearch(Account account, FolderInfoHolder folder) {
            String searchTitle = getString(R.string.search_title,
                    getString(R.string.message_list_title, account.getDescription(),
                            folder.displayName),
                    getString(R.string.flagged_modifier));

            LocalSearch search = new LocalSearch(searchTitle);
            search.and(SearchField.FLAGGED, "1", Attribute.EQUALS);

            search.addAllowedFolder(folder.name);
            search.addAccountUuid(account.getUuid());

            return new FolderClickListener(search);
        }

        private OnClickListener createUnreadSearch(Account account, FolderInfoHolder folder) {
            String searchTitle = getString(R.string.search_title,
                    getString(R.string.message_list_title, account.getDescription(),
                            folder.displayName),
                    getString(R.string.unread_modifier));

            LocalSearch search = new LocalSearch(searchTitle);
            search.and(SearchField.READ, "1", Attribute.NOT_EQUALS);

            search.addAllowedFolder(folder.name);
            search.addAccountUuid(account.getUuid());

            return new FolderClickListener(search);
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        public boolean isItemSelectable(int position) {
            return true;
        }

        public void setFilter(final Filter filter) {
            this.mFilter = filter;
        }

        public Filter getFilter() {
            return mFilter;
        }

        /**
         * Filter to search for occurrences of the search-expression in any place of the
         * folder-name instead of doing just a prefix-search.
         *
         * @author Marcus@Wolschon.biz
         */
        public class FolderListFilter extends Filter {
            private CharSequence mSearchTerm;

            public CharSequence getSearchTerm() {
                return mSearchTerm;
            }

            /**
             * Do the actual search.
             * {@inheritDoc}
             *
             * @see #publishResults(CharSequence, FilterResults)
             */
            @Override
            protected FilterResults performFiltering(CharSequence searchTerm) {
                mSearchTerm = searchTerm;
                FilterResults results = new FilterResults();

                Locale locale = Locale.getDefault();
                if ((searchTerm == null) || (searchTerm.length() == 0)) {
                    List<FolderInfoHolder> list = new ArrayList<FolderInfoHolder>(mFolders);
                    results.values = list;
                    results.count = list.size();
                } else {
                    final String searchTermString = searchTerm.toString().toLowerCase(locale);
                    final String[] words = searchTermString.split(" ");
                    final int wordCount = words.length;

                    final List<FolderInfoHolder> newValues = new ArrayList<FolderInfoHolder>();

                    for (final FolderInfoHolder value : mFolders) {
                        if (value.displayName == null) {
                            continue;
                        }
                        final String valueText = value.displayName.toLowerCase(locale);

                        for (int k = 0; k < wordCount; k++) {
                            if (valueText.contains(words[k])) {
                                newValues.add(value);
                                break;
                            }
                        }
                    }

                    results.values = newValues;
                    results.count = newValues.size();
                }

                return results;
            }

            /**
             * Publish the results to the user-interface.
             * {@inheritDoc}
             */
            @SuppressWarnings("unchecked")
            @Override
            protected void publishResults(CharSequence constraint, FilterResults results) {
                //noinspection unchecked
                mFilteredFolders = Collections.unmodifiableList((ArrayList<FolderInfoHolder>) results.values);
                // Send notification that the data set changed now
                notifyDataSetChanged();
            }
        }
    }

    static class FolderViewHolder {
        public TextView folderName;

        public TextView folderStatus;

        public TextView newMessageCount;
        public TextView flaggedMessageCount;
        public View newMessageCountIcon;
        public View flaggedMessageCountIcon;
        public View newMessageCountWrapper;
        public View flaggedMessageCountWrapper;

        public RelativeLayout activeIcons;
        public String rawFolderName;
        public View chip;
        public LinearLayout folderListItemLayout;
    }

    private class FolderClickListener implements OnClickListener {

        final LocalSearch search;

        FolderClickListener(LocalSearch search) {
            this.search = search;
        }

        @Override
        public void onClick(View v) {
            MessageList.actionDisplaySearch(FolderList.this, search, true, false);
        }
    }
}
