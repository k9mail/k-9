package com.fsck.k9.activity;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Config;
import android.util.Log;
import android.view.*;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.*;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.AdapterView.OnItemClickListener;
import com.fsck.k9.*;
import com.fsck.k9.Account.FolderMode;
import com.fsck.k9.activity.setup.AccountSettings;
import com.fsck.k9.activity.setup.FolderSettings;
import com.fsck.k9.mail.Folder;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.Store;
import com.fsck.k9.service.MailService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;

/**
 * FolderList is the primary user interface for the program. This
 * Activity shows list of the Account's folders
 */

public class FolderList extends K9ListActivity
{

    private static final int DIALOG_MARK_ALL_AS_READ = 1;

    private static final String EXTRA_ACCOUNT = "account";

    private static final String EXTRA_INITIAL_FOLDER = "initialFolder";

    private static final String STATE_CURRENT_FOLDER = "com.fsck.k9.activity.folderlist_folder";

    private static final String EXTRA_CLEAR_NOTIFICATION = "clearNotification";

    private static final boolean REFRESH_REMOTE = true;

    private ListView mListView;

    private FolderListAdapter mAdapter;

    private LayoutInflater mInflater;

    private Account mAccount;

    private FolderListHandler mHandler = new FolderListHandler();

    private int mUnreadMessageCount;

    class FolderListHandler extends Handler
    {

        public void refreshTitle()
        {
            runOnUiThread(new Runnable()
            {
                public void run()
                {
                    String dispString = mAdapter.mListener.formatHeader(FolderList.this, getString(R.string.folder_list_title, mAccount.getDescription()), mUnreadMessageCount, getTimeFormat());


                    setTitle(dispString);
                }
            });
        }


        public void newFolders(final ArrayList<FolderInfoHolder> newFolders)
        {
            runOnUiThread(new Runnable()
            {
                public void run()
                {
                    mAdapter.mFolders.clear();
                    mAdapter.mFolders.addAll(newFolders);
                    mHandler.dataChanged();
                }
            });
        }

        public void workingAccount(final int res)
        {
            runOnUiThread(new Runnable()
            {
                public void run()
                {
                    String toastText = getString(res, mAccount.getDescription());
                    Toast toast = Toast.makeText(getApplication(), toastText, Toast.LENGTH_SHORT);
                    toast.show();
                }
            });
        }

        public void accountSizeChanged(final long oldSize, final long newSize)
        {
            runOnUiThread(new Runnable()
            {
                public void run()
                {
                    String toastText = getString(R.string.account_size_changed, mAccount.getDescription(), SizeFormatter.formatSize(getApplication(), oldSize), SizeFormatter.formatSize(getApplication(), newSize));

                    Toast toast = Toast.makeText(getApplication(), toastText, Toast.LENGTH_LONG);
                    toast.show();
                }
            });
        }

        public void folderLoading(final String folder, final boolean loading)
        {
            runOnUiThread(new Runnable()
            {
                public void run()
                {
                    FolderInfoHolder folderHolder = mAdapter.getFolder(folder);


                    if (folderHolder != null)
                    {
                        folderHolder.loading = loading;
                    }

                }
            });
        }

        public void progress(final boolean progress)
        {
            runOnUiThread(new Runnable()
            {
                public void run()
                {
                    setProgressBarIndeterminateVisibility(progress);
                }
            });

        }

        public void dataChanged()
        {
            runOnUiThread(new Runnable()
            {
                public void run()
                {
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

    private void checkMail(FolderInfoHolder folder)
    {
        if (mAccount.isStoreAttachmentOnSdCard()
            && !Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED))
        {
            Toast.makeText(this, R.string.sd_card_error, Toast.LENGTH_SHORT).show();
            return;
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        final WakeLock wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Email - UpdateWorker");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(K9.WAKE_LOCK_TIMEOUT);
        MessagingListener listener = new MessagingListener()
        {
            public void synchronizeMailboxFinished(Account account, String folder, int totalMessagesInMailbox, int numNewMessages)
            {
                if (!account.equals(mAccount))
                {
                    return;
                }
                wakeLock.release();
            }

            @Override
            public void synchronizeMailboxFailed(Account account, String folder,
                                                 String message)
            {
                if (!account.equals(mAccount))
                {
                    return;
                }
                wakeLock.release();
            }
        };
        MessagingController.getInstance(getApplication()).synchronizeMailbox(mAccount, folder.name, listener);
        sendMail(mAccount);
    }

    private static void actionHandleAccount(Context context, Account account, String initialFolder)
    {
        Intent intent = new Intent(context, FolderList.class);
        intent.putExtra(EXTRA_ACCOUNT, account);

        if (initialFolder != null)
        {
            intent.putExtra(EXTRA_INITIAL_FOLDER, initialFolder);
        }

        context.startActivity(intent);
    }

    public static void actionHandleAccount(Context context, Account account)
    {
        actionHandleAccount(context, account, null);
    }

    public static Intent actionHandleAccountIntent(Context context, Account account, String initialFolder)
    {
        Intent intent = new Intent(
            Intent.ACTION_VIEW,
            Uri.parse("email://accounts/" + account.getAccountNumber()),
            context,
            FolderList.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(EXTRA_ACCOUNT, account);
        intent.putExtra(EXTRA_CLEAR_NOTIFICATION, true);

        if (initialFolder != null)
        {
            intent.putExtra(EXTRA_INITIAL_FOLDER, initialFolder);
        }
        return intent;
    }

    public static Intent actionHandleAccountIntent(Context context, Account account)
    {
        return actionHandleAccountIntent(context, account, null);
    }

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);

        mListView = getListView();
        mListView.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_INSET);
        mListView.setLongClickable(true);
        mListView.setFastScrollEnabled(true);
        mListView.setScrollingCacheEnabled(true);
        mListView.setOnItemClickListener(new OnItemClickListener()
        {
            public void onItemClick(AdapterView parent, View v, int itemPosition, long id)
            {
                onOpenFolder(((FolderInfoHolder)mAdapter.getItem(id)).name);
            }
        });
        registerForContextMenu(mListView);
        
        mListView.setSaveEnabled(true);

        mInflater = getLayoutInflater();

        onNewIntent(getIntent());
    }

    public void onNewIntent(Intent intent)
    {
        setIntent(intent); // onNewIntent doesn't autoset our "internal" intent

        String savedFolderName = null;
        String initialFolder;

        mUnreadMessageCount = 0;
        mAccount = (Account)intent.getSerializableExtra(EXTRA_ACCOUNT);

        initialFolder = intent.getStringExtra(EXTRA_INITIAL_FOLDER);
        if (
            initialFolder != null
            && !K9.FOLDER_NONE.equals(initialFolder))
        {
            onOpenFolder(initialFolder);
            finish();
        }
        else
        {

            initializeActivityView();
        }
    }

    private void initializeActivityView()
    {
        mAdapter = new FolderListAdapter();

        final Object previousData = getLastNonConfigurationInstance();

        if (previousData != null)
        {
            //noinspection unchecked
            mAdapter.mFolders = (ArrayList<FolderInfoHolder>) previousData;
        }

        setListAdapter(mAdapter);

        setTitle(mAccount.getDescription());

    }


    @Override public Object onRetainNonConfigurationInstance()
    {
        return mAdapter.mFolders;
    }

    @Override public void onPause()
    {
        super.onPause();
        MessagingController.getInstance(getApplication()).removeListener(mAdapter.mListener);
    }

    /**
    * On resume we refresh the folder list (in the background) and we refresh the
    * messages for any folder that is currently open. This guarantees that things
    * like unread message count and read status are updated.
     */
    @Override public void onResume()
    {
        super.onResume();

        if (mAdapter == null)
            initializeActivityView();

        MessagingController.getInstance(getApplication()).addListener(mAdapter.mListener);
        mAccount.refresh(Preferences.getPreferences(this));
        MessagingController.getInstance(getApplication()).getAccountUnreadCount(this, mAccount, mAdapter.mListener);

        onRefresh(!REFRESH_REMOTE);

        NotificationManager notifMgr = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notifMgr.cancel(mAccount.getAccountNumber());
        notifMgr.cancel(-1000 - mAccount.getAccountNumber());

    }


    @Override public boolean onKeyDown(int keyCode, KeyEvent event)
    {
        //Shortcuts that work no matter what is selected
        switch (keyCode)
        {
            case KeyEvent.KEYCODE_Q:
                //case KeyEvent.KEYCODE_BACK:
            {
                onAccounts();
                return true;
            }

            case KeyEvent.KEYCODE_S:
            {
                onEditAccount();
                return true;
            }

            case KeyEvent.KEYCODE_H:
            {
                Toast toast = Toast.makeText(this, R.string.folder_list_help_key, Toast.LENGTH_LONG);
                toast.show();
                return true;
            }
            
            case KeyEvent.KEYCODE_1:
            {
                setDisplayMode(FolderMode.FIRST_CLASS);
                return true;
            }
            case KeyEvent.KEYCODE_2:
            {
                setDisplayMode(FolderMode.FIRST_AND_SECOND_CLASS);
                return true;
            }
            case KeyEvent.KEYCODE_3:
            {
                setDisplayMode(FolderMode.NOT_SECOND_CLASS);
                return true;
            }
            case KeyEvent.KEYCODE_4:
            {
                setDisplayMode(FolderMode.ALL);
                return true;
            }
        }//switch


        return super.onKeyDown(keyCode, event);
    }//onKeyDown

    private void setDisplayMode(FolderMode newMode)
    {
        mAccount.setFolderDisplayMode(newMode);
        mAccount.save(Preferences.getPreferences(this));
        if (mAccount.getFolderPushMode() != FolderMode.NONE)
        {
            MailService.actionRestartPushers(this, null);
        }
        onRefresh(false);
    }
    
    
    private void onRefresh(final boolean forceRemote)
    {

        MessagingController.getInstance(getApplication()).listFolders(mAccount, forceRemote, mAdapter.mListener);

    }

    private void onEditAccount()
    {
        AccountSettings.actionSettings(this, mAccount);
    }

    private void onEditFolder(Account account, String folderName)
    {
        FolderSettings.actionSettings(this, account, folderName);
    }

    private void onAccounts()
    {
        Accounts.listAccounts(this);
        finish();
    }

    private void onEmptyTrash(final Account account)
    {
        mHandler.dataChanged();

        MessagingController.getInstance(getApplication()).emptyTrash(account, null);
    }

    private void onExpunge(final Account account, String folderName)
    {
        MessagingController.getInstance(getApplication()).expunge(account, folderName, null);
    }

    private void checkMail(final Account account)
    {
        if (mAccount.isStoreAttachmentOnSdCard()
            && !Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED))
        {
            Toast.makeText(this, R.string.sd_card_error, Toast.LENGTH_SHORT).show();
            return;
        }

        MessagingController.getInstance(getApplication()).checkMail(this, account, true, true, mAdapter.mListener);
    }

    private void sendMail(Account account)
    {
        MessagingController.getInstance(getApplication()).sendPendingMessages(account, mAdapter.mListener);
    }

    @Override public boolean onOptionsItemSelected(MenuItem item)
    {
        switch (item.getItemId())
        {
            case R.id.compose:
                MessageCompose.actionCompose(this, mAccount);

                return true;

            case R.id.check_mail:
                checkMail(mAccount);

                return true;

            case R.id.send_messages:
                MessagingController.getInstance(getApplication()).sendPendingMessages(mAccount, null);
                return true;
            case R.id.accounts:
                onAccounts();

                return true;

            case R.id.list_folders:
                onRefresh(REFRESH_REMOTE);

                return true;

            case R.id.account_settings:
                onEditAccount();

                return true;

            case R.id.empty_trash:
                onEmptyTrash(mAccount);

                return true;

            case R.id.compact:
                onCompact(mAccount);

                return true;

            case R.id.clear:
                onClear(mAccount);

                return true;
            case R.id.display_1st_class:
            {
                setDisplayMode(FolderMode.FIRST_CLASS);
                return true;
            }
            case R.id.display_1st_and_2nd_class:
            {
                setDisplayMode(FolderMode.FIRST_AND_SECOND_CLASS);
                return true;
            }
            case R.id.display_not_second_class:
            {
                setDisplayMode(FolderMode.NOT_SECOND_CLASS);
                return true;
            }
            case R.id.display_all:
            {
                setDisplayMode(FolderMode.ALL);
                return true;
            }
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void onOpenFolder(String folder)
    {
        MessageList.actionHandleFolder(this, mAccount, folder);
    }

    private void onCompact(Account account)
    {
        mHandler.workingAccount(R.string.compacting_account);
        MessagingController.getInstance(getApplication()).compact(account, null);
    }

    private void onClear(Account account)
    {
        mHandler.workingAccount(R.string.clearing_account);
        MessagingController.getInstance(getApplication()).clear(account, null);
    }

    @Override public boolean onCreateOptionsMenu(Menu menu)
    {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.folder_list_option, menu);
        return true;
    }

    @Override public boolean onContextItemSelected(MenuItem item)
    {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item .getMenuInfo();
        FolderInfoHolder folder = (FolderInfoHolder) mAdapter.getItem(info.position);

        switch (item.getItemId())
        {
            case R.id.open_folder:
                onOpenFolder(folder.name);
                break;

            case R.id.mark_all_as_read:
                onMarkAllAsRead(mAccount, folder.name);
                break;

            case R.id.send_messages:
                sendMail(mAccount);

                break;

            case R.id.check_mail:
                checkMail(folder);

                break;

            case R.id.folder_settings:
                onEditFolder(mAccount, folder.name);

                break;

            case R.id.empty_trash:
                onEmptyTrash(mAccount);

                break;
            case R.id.expunge:
                onExpunge(mAccount, folder.name);

                break;
        }

        return super.onContextItemSelected(item);
    }

    private FolderInfoHolder mSelectedContextFolder = null;


    private void onMarkAllAsRead(final Account account, final String folder)
    {
        mSelectedContextFolder = mAdapter.getFolder(folder);
        showDialog(DIALOG_MARK_ALL_AS_READ);
    }


    @Override
    public Dialog onCreateDialog(int id)
    {
        switch (id)
        {
            case DIALOG_MARK_ALL_AS_READ:
                return createMarkAllAsReadDialog();
        }

        return super.onCreateDialog(id);
    }

    public void onPrepareDialog(int id, Dialog dialog)
    {
        switch (id)
        {
            case DIALOG_MARK_ALL_AS_READ:
                ((AlertDialog)dialog).setMessage(getString(R.string.mark_all_as_read_dlg_instructions_fmt,
                                                 mSelectedContextFolder.displayName));

                break;

            default:
                super.onPrepareDialog(id, dialog);
        }
    }

    private Dialog createMarkAllAsReadDialog()
    {
        return new AlertDialog.Builder(this)
               .setTitle(R.string.mark_all_as_read_dlg_title)
               .setMessage(getString(R.string.mark_all_as_read_dlg_instructions_fmt,
                                     mSelectedContextFolder.displayName))
               .setPositiveButton(R.string.okay_action, new DialogInterface.OnClickListener()
        {
            public void onClick(DialogInterface dialog, int whichButton)
            {
                dismissDialog(DIALOG_MARK_ALL_AS_READ);

                try
                {

                    MessagingController.getInstance(getApplication()).markAllMessagesRead(mAccount, mSelectedContextFolder.name);

                    mSelectedContextFolder.unreadMessageCount = 0;

                    mHandler.dataChanged();


                }
                catch (Exception e)
                {
                    // Ignore
                }
            }
        })

               .setNegativeButton(R.string.cancel_action, new DialogInterface.OnClickListener()
        {
            public void onClick(DialogInterface dialog, int whichButton)
            {
                dismissDialog(DIALOG_MARK_ALL_AS_READ);
            }
        })

               .create();
    }


    @Override public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo)
    {
        super.onCreateContextMenu(menu, v, menuInfo);
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        getMenuInflater().inflate(R.menu.folder_context, menu);

        FolderInfoHolder folder = (FolderInfoHolder) mAdapter.getItem(info.position);

        menu.setHeaderTitle((CharSequence) folder.displayName);

        if (!folder.name.equals(mAccount.getTrashFolderName()))
            menu.findItem(R.id.empty_trash).setVisible(false);

        if (folder.outbox)
        {
            menu.findItem(R.id.check_mail).setVisible(false);
        }
        else
        {
            menu.findItem(R.id.send_messages).setVisible(false);
        }
        if (K9.ERROR_FOLDER_NAME.equals(folder.name))
        {
            menu.findItem(R.id.expunge).setVisible(false);
        }

        menu.setHeaderTitle(folder.displayName);
    }

    private String truncateStatus(String mess)
    {
        if (mess != null && mess.length() > 27)
        {
            mess = mess.substring(0, 27);
        }

        return mess;
    }

    class FolderListAdapter extends BaseAdapter
    {
        private ArrayList<FolderInfoHolder> mFolders = new ArrayList<FolderInfoHolder>();

        public Object getItem(long position)
        {
            return getItem((int)position);
        }

        public Object getItem(int position)
        {
            return mFolders.get(position);
        }


        public long getItemId(int position)
        {
            return position ;
        }

        public int getCount()
        {
            return mFolders.size();
        }

        public boolean isEnabled(int item)
        {
            return true;
        }

        public boolean areAllItemsEnabled()
        {
            return true;
        }

        private ActivityListener mListener = new ActivityListener()
        {
            @Override
            public void accountStatusChanged(Account account, int unreadMessageCount)
            {
                mUnreadMessageCount = unreadMessageCount;
                mHandler.refreshTitle();
            }

            @Override
            public void listFoldersStarted(Account account)
            {
                if (!account.equals(mAccount))
                {
                    return;
                }

                mHandler.progress(true);
            }

            @Override
            public void listFoldersFailed(Account account, String message)
            {
                if (!account.equals(mAccount))
                {
                    return;
                }

                mHandler.progress(false);

                if (Config.LOGV)
                {
                    Log.v(K9.LOG_TAG, "listFoldersFailed " + message);
                }
            }

            @Override
            public void listFoldersFinished(Account account)
            {
                if (!account.equals(mAccount))
                {
                    return;
                }

                mHandler.progress(false);
                MessagingController.getInstance(getApplication()).refreshListener(mAdapter.mListener);
                mHandler.dataChanged();

            }

            @Override
            public void listFolders(Account account, Folder[] folders)
            {
                if (!account.equals(mAccount))
                {
                    return;
                }

                ArrayList<FolderInfoHolder> newFolders = new ArrayList<FolderInfoHolder>();

                Account.FolderMode aMode = account.getFolderDisplayMode();

                for (Folder folder : folders)
                {
                    try
                    {
                        folder.refresh(Preferences.getPreferences(getApplication().getApplicationContext()));

                        Folder.FolderClass fMode = folder.getDisplayClass();

                        if ((aMode == Account.FolderMode.FIRST_CLASS && fMode != Folder.FolderClass.FIRST_CLASS)
                                || (aMode == Account.FolderMode.FIRST_AND_SECOND_CLASS &&
                                    fMode != Folder.FolderClass.FIRST_CLASS &&
                                    fMode != Folder.FolderClass.SECOND_CLASS)
                                || (aMode == Account.FolderMode.NOT_SECOND_CLASS && fMode == Folder.FolderClass.SECOND_CLASS))
                        {
                            continue;
                        }
                    }
                    catch (MessagingException me)
                    {
                        Log.e(K9.LOG_TAG, "Couldn't get prefs to check for displayability of folder " + folder.getName(), me);
                    }

                    FolderInfoHolder holder = null;

                    int folderIndex = getFolderIndex(folder.getName());
                    if (folderIndex >= 0)
                    {
                        holder = (FolderInfoHolder) getItem(folderIndex);
                    }
                    int unreadMessageCount = 0;
                    try
                    {
                        unreadMessageCount  = folder.getUnreadMessageCount();
                    }
                    catch (Exception e)
                    {
                        Log.e(K9.LOG_TAG, "Unable to get unreadMessageCount for " + mAccount.getDescription() + ":"
                              + folder.getName());
                    }

                    if (holder == null)
                    {
                        holder = new FolderInfoHolder(folder, unreadMessageCount);
                    }
                    else
                    {
                        holder.populate(folder, unreadMessageCount);

                    }

                    newFolders.add(holder);
                }
                Collections.sort(newFolders);
                mHandler.newFolders(newFolders);
                mHandler.refreshTitle();

            }

            public void synchronizeMailboxStarted(Account account, String folder)
            {
                super.synchronizeMailboxStarted(account, folder);
                mHandler.refreshTitle();
                if (!account.equals(mAccount))
                {
                    return;
                }

                mHandler.progress(true);
                mHandler.folderLoading(folder, true);
                mHandler.dataChanged();

            }

            @Override
            public void synchronizeMailboxProgress(Account account, String folder, int completed, int total)
            {
                super.synchronizeMailboxProgress(account, folder, completed, total);
                mHandler.refreshTitle();
                if (true) return;
                if (!account.equals(mAccount))
                {
                    return;
                }
                mHandler.dataChanged();
            }

            @Override
            public void synchronizeMailboxFinished(Account account, String folder, int totalMessagesInMailbox, int numNewMessages)
            {
                super.synchronizeMailboxFinished(account, folder, totalMessagesInMailbox, numNewMessages);
                mHandler.refreshTitle();
                if (!account.equals(mAccount))
                {
                    return;
                }
                mHandler.progress(false);
                mHandler.folderLoading(folder, false);

                refreshFolder(account, folder);


            }

            private void refreshFolder(Account account, String folderName)
            {
                // There has to be a cheaper way to get at the localFolder object than this
                Folder localFolder = null;
                try
                {
                    if (account != null && folderName != null)
                    {
                        localFolder = account.getLocalStore().getFolder(folderName);
                        int unreadMessageCount = localFolder.getUnreadMessageCount();
                        if (localFolder != null)
                        {
                            FolderInfoHolder folderHolder = getFolder(folderName);
                            if (folderHolder != null)
                            {
                                folderHolder.populate(localFolder, unreadMessageCount);
                                mHandler.dataChanged();
                            }
                        }
                    }
                }
                catch (Exception e)
                {
                    Log.e(K9.LOG_TAG, "Exception while populating folder", e);
                }
                finally
                {
                    if (localFolder != null)
                    {
                        localFolder.close();
                    }
                }

            }

            @Override
            public void synchronizeMailboxFailed(Account account, String folder,
                                                 String message)
            {
                super.synchronizeMailboxFailed(account, folder, message);
                mHandler.refreshTitle();
                if (!account.equals(mAccount))
                {
                    return;
                }


                mHandler.progress(false);

                mHandler.folderLoading(folder, false);

                //   String mess = truncateStatus(message);

                //   mHandler.folderStatus(folder, mess);
                FolderInfoHolder holder = getFolder(folder);

                if (holder != null)
                {
                    holder.lastChecked = 0;
                }

                mHandler.dataChanged();

            }

            @Override
            public void setPushActive(Account account, String folderName, boolean enabled)
            {
                if (!account.equals(mAccount))
                {
                    return;
                }
                FolderInfoHolder holder = getFolder(folderName);

                if (holder != null)
                {
                    holder.pushActive = enabled;

                    mHandler.dataChanged();
                }
            }


            @Override
            public void messageDeleted(Account account,
                                       String folder, Message message)
            {
                synchronizeMailboxRemovedMessage(account,
                                                 folder, message);
            }

            @Override
            public void emptyTrashCompleted(Account account)
            {
                if (!account.equals(mAccount))
                {
                    return;
                }
                refreshFolder(account, mAccount.getTrashFolderName());
            }

            @Override
            public void folderStatusChanged(Account account, String folderName, int unreadMessageCount)
            {
                if (!account.equals(mAccount))
                {
                    return;
                }
                refreshFolder(account, folderName);
            }

            @Override
            public void sendPendingMessagesCompleted(Account account)
            {
                super.sendPendingMessagesCompleted(account);
                mHandler.refreshTitle();
                if (!account.equals(mAccount))
                {
                    return;
                }

                refreshFolder(account, mAccount.getOutboxFolderName());


            }

            @Override
            public void sendPendingMessagesStarted(Account account)
            {
                super.sendPendingMessagesStarted(account);
                mHandler.refreshTitle();

                if (!account.equals(mAccount))
                {
                    return;
                }

                mHandler.dataChanged();

            }

            @Override
            public void sendPendingMessagesFailed(Account account)
            {
                super.sendPendingMessagesFailed(account);
                mHandler.refreshTitle();
                if (!account.equals(mAccount))
                {
                    return;
                }

                refreshFolder(account, mAccount.getOutboxFolderName());

            }

            public void accountSizeChanged(Account account, long oldSize, long newSize)
            {
                if (!account.equals(mAccount))
                {
                    return;
                }

                mHandler.accountSizeChanged(oldSize, newSize);

            }
            public void pendingCommandsProcessing(Account account)
            {
                super.pendingCommandsProcessing(account);
                mHandler.refreshTitle();
            }
            public void pendingCommandsFinished(Account account)
            {
                super.pendingCommandsFinished(account);
                mHandler.refreshTitle();
            }
            public void pendingCommandStarted(Account account, String commandTitle)
            {
                super.pendingCommandStarted(account, commandTitle);
                mHandler.refreshTitle();
            }
            public void pendingCommandCompleted(Account account, String commandTitle)
            {
                super.pendingCommandCompleted(account, commandTitle);
                mHandler.refreshTitle();
            }

        };


        public int getFolderIndex(String folder)
        {
            FolderInfoHolder searchHolder = new FolderInfoHolder();
            searchHolder.name = folder;
            return   mFolders.indexOf((Object) searchHolder);
        }

        public FolderInfoHolder getFolder(String folder)
        {
            FolderInfoHolder holder = null;

            int index = getFolderIndex(folder);
            if (index >= 0)
            {
                holder = (FolderInfoHolder) getItem(index);
                if (holder != null)
                {
                    return holder;
                }
            }
            return null;
        }

        public View getView(int position, View convertView, ViewGroup parent)
        {
            if (position <= getCount())
            {
                return  getItemView(position, convertView, parent);
            }
            else
            {
                // XXX TODO - should catch an exception here
                return null;
            }
        }

        public View getItemView(int itemPosition, View convertView, ViewGroup parent)
        {
            FolderInfoHolder folder = (FolderInfoHolder) getItem(itemPosition);
            View view;
            if ((convertView != null) && (convertView.getId() == R.layout.folder_list_item))
            {
                view = convertView;
            }
            else
            {
                view = mInflater.inflate(R.layout.folder_list_item, parent, false);
                view.setId(R.layout.folder_list_item);
            }


            FolderViewHolder holder = (FolderViewHolder) view.getTag();

            if (holder == null)
            {
                holder = new FolderViewHolder();
                holder.folderName = (TextView) view.findViewById(R.id.folder_name);
                holder.newMessageCount = (TextView) view.findViewById(R.id.folder_unread_message_count);
                holder.folderStatus = (TextView) view.findViewById(R.id.folder_status);
                holder.chip = view.findViewById(R.id.chip);
                holder.rawFolderName = folder.name;

                view.setTag(holder);
            }

            if (folder == null)
            {
                return view;
            }

            holder.folderName.setText(folder.displayName);
            String statusText = "";

            if (folder.loading)
            {
                String progress = false && mAdapter.mListener.getFolderTotal() > 0 ? getString(R.string.folder_progress, mAdapter.mListener.getFolderCompleted(), mAdapter.mListener.getFolderTotal()) : "";
                statusText = getString(R.string.status_loading, progress);
            }
            else if (folder.status != null)
            {
                statusText = folder.status;
            }
            else if (folder.lastChecked != 0)
            {
                Date lastCheckedDate = new Date(folder.lastChecked);

                statusText = getTimeFormat().format(lastCheckedDate) + " "+
                             getDateFormat().format(lastCheckedDate);
            }

            if (folder.pushActive)
            {
                statusText = getString(R.string.folder_push_active_symbol) + " "+ statusText;
            }

            if (statusText != null)
            {
                holder.folderStatus.setText(statusText);
                holder.folderStatus.setVisibility(View.VISIBLE);
            }
            else
            {
                holder.folderStatus.setText(null);
                holder.folderStatus.setVisibility(View.GONE);
            }

            if (folder.unreadMessageCount != 0)
            {
                holder.newMessageCount.setText(Integer
                                               .toString(folder.unreadMessageCount));
                holder.newMessageCount.setVisibility(View.VISIBLE);
            }
            else
            {
                holder.newMessageCount.setVisibility(View.GONE);
            }

            holder.chip.setBackgroundResource(K9.COLOR_CHIP_RES_IDS[mAccount.getAccountNumber() % K9.COLOR_CHIP_RES_IDS.length]);

            holder.chip.getBackground().setAlpha(folder.unreadMessageCount == 0 ? 127 : 255);

            return view;
        }

        public boolean hasStableIds()
        {
            return false;
        }

        public boolean isItemSelectable(int position)
        {
            return true;
        }

    }

    public class FolderInfoHolder implements Comparable<FolderInfoHolder>
    {
        public String name;

        public String displayName;

        public long lastChecked;

        public int unreadMessageCount;

        public boolean loading;

        public String status;

        public boolean pushActive;

        public boolean lastCheckFailed;

        /**
         * Outbox is handled differently from any other folder.
         */
        public boolean outbox;


        public boolean equals(Object o)
        {
            if (this.name.equals(((FolderInfoHolder)o).name))
            {
                return true;
            }
            else
            {
                return false;
            }
        }

        public int compareTo(FolderInfoHolder o)
        {
            String s1 = this.name;
            String s2 = o.name;

            if (K9.INBOX.equalsIgnoreCase(s1) && K9.INBOX.equalsIgnoreCase(s2))
            {
                return 0;
            }
            else if (K9.INBOX.equalsIgnoreCase(s1))
            {
                return -1;
            }
            else if (K9.INBOX.equalsIgnoreCase(s2))
            {
                return 1;
            }
            else
            {
                int ret = s1.compareToIgnoreCase(s2);
                if (ret != 0)
                {
                    return ret;
                }
                else
                {
                    return s1.compareTo(s2);
                }
            }

        }

        // constructor for an empty object for comparisons
        public FolderInfoHolder()
        {
        }

        public FolderInfoHolder(Folder folder, int unreadCount)
        {
            populate(folder, unreadCount);
        }
        public void populate(Folder folder, int unreadCount)
        {

            try
            {
                folder.open(Folder.OpenMode.READ_WRITE);
                unreadCount = folder.getUnreadMessageCount();
            }
            catch (MessagingException me)
            {
                Log.e(K9.LOG_TAG, "Folder.getUnreadMessageCount() failed", me);
            }

            this.name = folder.getName();

            if (this.name.equalsIgnoreCase(K9.INBOX))
            {
                this.displayName = getString(R.string.special_mailbox_name_inbox);
            }
            else
            {
                this.displayName = folder.getName();
            }

            if (this.name.equals(mAccount.getOutboxFolderName()))
            {
                this.displayName = String.format(getString(R.string.special_mailbox_name_outbox_fmt), this.name);
                this.outbox = true;
            }

            if (this.name.equals(mAccount.getDraftsFolderName()))
            {
                this.displayName = String.format(getString(R.string.special_mailbox_name_drafts_fmt), this.name);
            }

            if (this.name.equals(mAccount.getTrashFolderName()))
            {
                this.displayName = String.format(getString(R.string.special_mailbox_name_trash_fmt), this.name);
            }

            if (this.name.equals(mAccount.getSentFolderName()))
            {
                this.displayName = String.format(getString(R.string.special_mailbox_name_sent_fmt), this.name);
            }

            this.lastChecked = folder.getLastUpdate();

            String mess = truncateStatus(folder.getStatus());

            this.status = mess;

            this.unreadMessageCount = unreadCount;

            folder.close();

        }
    }

    class FolderViewHolder
    {
        public TextView folderName;

        public TextView folderStatus;

        public TextView newMessageCount;

        public String rawFolderName;
        public View chip;
    }

}
