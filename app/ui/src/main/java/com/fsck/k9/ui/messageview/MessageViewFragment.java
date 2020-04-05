package com.fsck.k9.ui.messageview;


import java.util.Collections;
import java.util.Locale;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.IntentSender.SendIntentException;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.os.SystemClock;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.appcompat.widget.PopupMenu.OnMenuItemClickListener;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import com.fsck.k9.Account;
import com.fsck.k9.DI;
import com.fsck.k9.K9;
import com.fsck.k9.Preferences;
import com.fsck.k9.ui.choosefolder.ChooseFolderActivity;
import com.fsck.k9.activity.MessageLoaderHelper;
import com.fsck.k9.activity.MessageLoaderHelper.MessageLoaderCallbacks;
import com.fsck.k9.activity.MessageLoaderHelperFactory;
import com.fsck.k9.controller.MessageReference;
import com.fsck.k9.controller.MessagingController;
import com.fsck.k9.fragment.AttachmentDownloadDialogFragment;
import com.fsck.k9.fragment.ConfirmationDialogFragment;
import com.fsck.k9.fragment.ConfirmationDialogFragment.ConfirmationDialogFragmentListener;
import com.fsck.k9.mail.Flag;
import com.fsck.k9.mailstore.AttachmentViewInfo;
import com.fsck.k9.mailstore.LocalMessage;
import com.fsck.k9.mailstore.MessageViewInfo;
import com.fsck.k9.ui.R;
import com.fsck.k9.ui.ThemeManager;
import com.fsck.k9.ui.messageview.CryptoInfoDialog.OnClickShowCryptoKeyListener;
import com.fsck.k9.ui.messageview.MessageCryptoPresenter.MessageCryptoMvpView;
import com.fsck.k9.ui.settings.account.AccountSettingsActivity;
import com.fsck.k9.view.MessageCryptoDisplayStatus;
import timber.log.Timber;


public class MessageViewFragment extends Fragment implements ConfirmationDialogFragmentListener,
        AttachmentViewCallback, OnClickShowCryptoKeyListener {

    private static final String ARG_REFERENCE = "reference";

    private static final int ACTIVITY_CHOOSE_FOLDER_MOVE = 1;
    private static final int ACTIVITY_CHOOSE_FOLDER_COPY = 2;
    private static final int REQUEST_CODE_CREATE_DOCUMENT = 3;

    public static final int REQUEST_MASK_LOADER_HELPER = (1 << 8);
    public static final int REQUEST_MASK_CRYPTO_PRESENTER = (1 << 9);

    public static final int PROGRESS_THRESHOLD_MILLIS = 500 * 1000;

    public static MessageViewFragment newInstance(MessageReference reference) {
        MessageViewFragment fragment = new MessageViewFragment();

        Bundle args = new Bundle();
        args.putString(ARG_REFERENCE, reference.toIdentityString());
        fragment.setArguments(args);

        return fragment;
    }

    private final ThemeManager themeManager = DI.get(ThemeManager.class);
    private final MessageLoaderHelperFactory messageLoaderHelperFactory = DI.get(MessageLoaderHelperFactory.class);

    private MessageTopView mMessageView;

    private Account mAccount;
    private MessageReference mMessageReference;
    private LocalMessage mMessage;
    private MessagingController mController;
    private DownloadManager downloadManager;
    private Handler handler = new Handler();
    private MessageLoaderHelper messageLoaderHelper;
    private MessageCryptoPresenter messageCryptoPresenter;
    private Long showProgressThreshold;

    /**
     * Used to temporarily store the destination folder for refile operations if a confirmation
     * dialog is shown.
     */
    private String mDstFolder;

    private MessageViewFragmentListener mFragmentListener;

    /**
     * {@code true} after {@link #onCreate(Bundle)} has been executed. This is used by
     * {@code MessageList.configureMenu()} to make sure the fragment has been initialized before
     * it is used.
     */
    private boolean mInitialized = false;

    private Context mContext;

    private AttachmentViewInfo currentAttachmentViewInfo;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        mContext = context.getApplicationContext();

        try {
            mFragmentListener = (MessageViewFragmentListener) getActivity();
        } catch (ClassCastException e) {
            throw new ClassCastException("This fragment must be attached to a MessageViewFragmentListener");
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // This fragments adds options to the action bar
        setHasOptionsMenu(true);

        Context context = getActivity().getApplicationContext();
        mController = MessagingController.getInstance(context);
        downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        messageCryptoPresenter = new MessageCryptoPresenter(messageCryptoMvpView);
        messageLoaderHelper = messageLoaderHelperFactory.createForMessageView(
                context, getLoaderManager(), getParentFragmentManager(), messageLoaderCallbacks);
        mInitialized = true;
    }

    @Override
    public void onResume() {
        super.onResume();

        messageCryptoPresenter.onResume();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Activity activity = getActivity();
        boolean isChangingConfigurations = activity != null && activity.isChangingConfigurations();
        if (isChangingConfigurations) {
            messageLoaderHelper.onDestroyChangingConfigurations();
            return;
        }

        messageLoaderHelper.onDestroy();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        int messageViewThemeResourceId = themeManager.getMessageViewThemeResourceId();
        Context context = new ContextThemeWrapper(inflater.getContext(), messageViewThemeResourceId);
        LayoutInflater layoutInflater = LayoutInflater.from(context);
        View view = layoutInflater.inflate(R.layout.message, container, false);

        mMessageView = view.findViewById(R.id.message_view);
        mMessageView.setAttachmentCallback(this);
        mMessageView.setMessageCryptoPresenter(messageCryptoPresenter);

        mMessageView.setOnToggleFlagClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                onToggleFlagged();
            }
        });

        mMessageView.setOnMenuItemClickListener(new OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                int id = item.getItemId();
                if (id == R.id.reply) {
                    onReply();
                    return true;
                } else if (id == R.id.reply_all) {
                    onReplyAll();
                    return true;
                } else if (id == R.id.forward) {
                    onForward();
                    return true;
                } else if (id == R.id.forward_as_attachment) {
                    onForwardAsAttachment();
                    return true;
                } else if (id == R.id.share) {
                    onSendAlternate();
                    return true;
                }
                return false;
            }
        });

        mMessageView.setOnDownloadButtonClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mMessageView.disableDownloadButton();
                messageLoaderHelper.downloadCompleteMessage();
            }
        });

        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        Bundle arguments = getArguments();
        String messageReferenceString = arguments.getString(ARG_REFERENCE);
        MessageReference messageReference = MessageReference.parse(messageReferenceString);

        displayMessage(messageReference);
    }

    private void displayMessage(MessageReference messageReference) {
        mMessageReference = messageReference;
        Timber.d("MessageView displaying message %s", mMessageReference);

        mAccount = Preferences.getPreferences(getApplicationContext()).getAccount(mMessageReference.getAccountUuid());
        messageLoaderHelper.asyncStartOrResumeLoadingMessage(messageReference, null);

        mFragmentListener.updateMenu();
    }

    private void hideKeyboard() {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        View decorView = activity.getWindow().getDecorView();
        if (decorView != null) {
            imm.hideSoftInputFromWindow(decorView.getApplicationWindowToken(), 0);
        }
    }

    private void showUnableToDecodeError() {
        Context context = getActivity().getApplicationContext();
        Toast.makeText(context, R.string.message_view_toast_unable_to_display_message, Toast.LENGTH_SHORT).show();
    }

    private void showMessage(MessageViewInfo messageViewInfo) {
        hideKeyboard();

        boolean handledByCryptoPresenter = messageCryptoPresenter.maybeHandleShowMessage(
                mMessageView, mAccount, messageViewInfo);
        if (!handledByCryptoPresenter) {
            mMessageView.showMessage(mAccount, messageViewInfo);
            if (mAccount.isOpenPgpProviderConfigured()) {
                mMessageView.getMessageHeaderView().setCryptoStatusDisabled();
            } else {
                mMessageView.getMessageHeaderView().hideCryptoStatus();
            }
        }

        if (messageViewInfo.subject != null) {
            displaySubject(messageViewInfo.subject);
        }
    }

    private void displayHeaderForLoadingMessage(LocalMessage message) {
        mMessageView.setHeaders(message, mAccount);
        if (mAccount.isOpenPgpProviderConfigured()) {
            mMessageView.getMessageHeaderView().setCryptoStatusLoading();
        }
        displaySubject(message.getSubject());
        mFragmentListener.updateMenu();
    }

    private void displaySubject(String subject) {
        if (TextUtils.isEmpty(subject)) {
            subject = mContext.getString(R.string.general_no_subject);
        }

        mMessageView.setSubject(subject);
    }

    /**
     * Called from UI thread when user select Delete
     */
    public void onDelete() {
        if (K9.isConfirmDelete() || (K9.isConfirmDeleteStarred() && mMessage.isSet(Flag.FLAGGED))) {
            showDialog(R.id.dialog_confirm_delete);
        } else {
            delete();
        }
    }

    public void onToggleAllHeadersView() {
        mMessageView.getMessageHeaderView().onShowAdditionalHeaders();
    }

    public boolean allHeadersVisible() {
        return mMessageView.getMessageHeaderView().additionalHeadersVisible();
    }

    private void delete() {
        if (mMessage != null) {
            // Disable the delete button after it's tapped (to try to prevent
            // accidental clicks)
            mFragmentListener.disableDeleteAction();
            LocalMessage messageToDelete = mMessage;
            mFragmentListener.showNextMessageOrReturn();
            mController.deleteMessage(mMessageReference, null);
        }
    }

    public void onRefile(String dstFolder) {
        if (!mController.isMoveCapable(mAccount)) {
            return;
        }
        if (!mController.isMoveCapable(mMessageReference)) {
            Toast toast = Toast.makeText(getActivity(), R.string.move_copy_cannot_copy_unsynced_message, Toast.LENGTH_LONG);
            toast.show();
            return;
        }

        if (dstFolder == null) {
            return;
        }

        if (dstFolder.equals(mAccount.getSpamFolder()) && K9.isConfirmSpam()) {
            mDstFolder = dstFolder;
            showDialog(R.id.dialog_confirm_spam);
        } else {
            refileMessage(dstFolder);
        }
    }

    private void refileMessage(String dstFolder) {
        String srcFolder = mMessageReference.getFolderServerId();
        MessageReference messageToMove = mMessageReference;
        mFragmentListener.showNextMessageOrReturn();
        mController.moveMessage(mAccount, srcFolder, messageToMove, dstFolder);
    }

    public void onReply() {
        if (mMessage != null) {
            mFragmentListener.onReply(mMessage.makeMessageReference(), messageCryptoPresenter.getDecryptionResultForReply());
        }
    }

    public void onReplyAll() {
        if (mMessage != null) {
            mFragmentListener.onReplyAll(mMessage.makeMessageReference(), messageCryptoPresenter.getDecryptionResultForReply());
        }
    }

    public void onForward() {
        if (mMessage != null) {
            mFragmentListener.onForward(mMessage.makeMessageReference(), messageCryptoPresenter.getDecryptionResultForReply());
        }
    }

    public void onForwardAsAttachment() {
        if (mMessage != null) {
            mFragmentListener.onForwardAsAttachment(mMessage.makeMessageReference(), messageCryptoPresenter.getDecryptionResultForReply());
        }
    }

    public void onEditAsNewMessage() {
        if (mMessage != null) {
            mFragmentListener.onEditAsNewMessage(mMessage.makeMessageReference());
        }
    }

    public void onToggleFlagged() {
        if (mMessage != null) {
            boolean newState = !mMessage.isSet(Flag.FLAGGED);
            mController.setFlag(mAccount, mMessage.getFolder().getServerId(),
                    Collections.singletonList(mMessage), Flag.FLAGGED, newState);
            mMessageView.setHeaders(mMessage, mAccount);
        }
    }

    public void onMove() {
        if ((!mController.isMoveCapable(mAccount))
                || (mMessage == null)) {
            return;
        }
        if (!mController.isMoveCapable(mMessageReference)) {
            Toast toast = Toast.makeText(getActivity(), R.string.move_copy_cannot_copy_unsynced_message, Toast.LENGTH_LONG);
            toast.show();
            return;
        }

        startRefileActivity(ACTIVITY_CHOOSE_FOLDER_MOVE);

    }

    public void onCopy() {
        if ((!mController.isCopyCapable(mAccount))
                || (mMessage == null)) {
            return;
        }
        if (!mController.isCopyCapable(mMessageReference)) {
            Toast toast = Toast.makeText(getActivity(), R.string.move_copy_cannot_copy_unsynced_message, Toast.LENGTH_LONG);
            toast.show();
            return;
        }

        startRefileActivity(ACTIVITY_CHOOSE_FOLDER_COPY);
    }

    public void onArchive() {
        onRefile(mAccount.getArchiveFolder());
    }

    public void onSpam() {
        onRefile(mAccount.getSpamFolder());
    }

    private void startRefileActivity(int requestCode) {
        String accountUuid = mAccount.getUuid();
        String currentFolder = mMessageReference.getFolderServerId();
        String scrollToFolder = mAccount.getLastSelectedFolder();
        Intent intent = ChooseFolderActivity.buildLaunchIntent(requireActivity(), accountUuid, currentFolder,
                scrollToFolder, false, mMessageReference);

        startActivityForResult(intent, requestCode);
    }

    public void onPendingIntentResult(int requestCode, int resultCode, Intent data) {
        if ((requestCode & REQUEST_MASK_LOADER_HELPER) == REQUEST_MASK_LOADER_HELPER) {
            requestCode ^= REQUEST_MASK_LOADER_HELPER;
            messageLoaderHelper.onActivityResult(requestCode, resultCode, data);
            return;
        }

        if ((requestCode & REQUEST_MASK_CRYPTO_PRESENTER) == REQUEST_MASK_CRYPTO_PRESENTER) {
            requestCode ^= REQUEST_MASK_CRYPTO_PRESENTER;
            messageCryptoPresenter.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK) {
            return;
        }

        // Note: because fragments do not have a startIntentSenderForResult method, pending intent activities are
        // launched through the MessageList activity, and delivered back via onPendingIntentResult()

        switch (requestCode) {
            case REQUEST_CODE_CREATE_DOCUMENT: {
                if (data != null && data.getData() != null) {
                    getAttachmentController(currentAttachmentViewInfo).saveAttachmentTo(data.getData());
                }
                break;
            }
            case ACTIVITY_CHOOSE_FOLDER_MOVE:
            case ACTIVITY_CHOOSE_FOLDER_COPY: {
                if (data == null) {
                    return;
                }

                String destFolder = data.getStringExtra(ChooseFolderActivity.RESULT_SELECTED_FOLDER);
                String messageReferenceString = data.getStringExtra(ChooseFolderActivity.RESULT_MESSAGE_REFERENCE);
                MessageReference ref = MessageReference.parse(messageReferenceString);
                if (mMessageReference.equals(ref)) {
                    mAccount.setLastSelectedFolder(destFolder);
                    switch (requestCode) {
                        case ACTIVITY_CHOOSE_FOLDER_MOVE: {
                            mFragmentListener.showNextMessageOrReturn();
                            moveMessage(ref, destFolder);
                            break;
                        }
                        case ACTIVITY_CHOOSE_FOLDER_COPY: {
                            copyMessage(ref, destFolder);
                            break;
                        }
                    }
                }
                break;
            }
        }
    }

    public void onSendAlternate() {
        if (mMessage != null) {
            mController.sendAlternate(getActivity(), mAccount, mMessage);
        }
    }

    public void onToggleRead() {
        if (mMessage != null) {
            mController.setFlag(mAccount, mMessage.getFolder().getServerId(),
                    Collections.singletonList(mMessage), Flag.SEEN, !mMessage.isSet(Flag.SEEN));
            mMessageView.setHeaders(mMessage, mAccount);
            mFragmentListener.updateMenu();
        }
    }

    private void setProgress(boolean enable) {
        if (mFragmentListener != null) {
            mFragmentListener.setProgress(enable);
        }
    }

    public void moveMessage(MessageReference reference, String destFolderName) {
        mController.moveMessage(mAccount, mMessageReference.getFolderServerId(), reference, destFolderName);
    }

    public void copyMessage(MessageReference reference, String destFolderName) {
        mController.copyMessage(mAccount, mMessageReference.getFolderServerId(), reference, destFolderName);
    }

    private void showDialog(int dialogId) {
        DialogFragment fragment;
        if (dialogId == R.id.dialog_confirm_delete) {
            String title = getString(R.string.dialog_confirm_delete_title);
            String message = getString(R.string.dialog_confirm_delete_message);
            String confirmText = getString(R.string.dialog_confirm_delete_confirm_button);
            String cancelText = getString(R.string.dialog_confirm_delete_cancel_button);

            fragment = ConfirmationDialogFragment.newInstance(dialogId, title, message,
                    confirmText, cancelText);
        } else if (dialogId == R.id.dialog_confirm_spam) {
            String title = getString(R.string.dialog_confirm_spam_title);
            String message = getResources().getQuantityString(R.plurals.dialog_confirm_spam_message, 1);
            String confirmText = getString(R.string.dialog_confirm_spam_confirm_button);
            String cancelText = getString(R.string.dialog_confirm_spam_cancel_button);

            fragment = ConfirmationDialogFragment.newInstance(dialogId, title, message,
                    confirmText, cancelText);
        } else if (dialogId == R.id.dialog_attachment_progress) {
            String message = getString(R.string.dialog_attachment_progress_title);
            long size = currentAttachmentViewInfo.size;
            fragment = AttachmentDownloadDialogFragment.newInstance(size, message);
        } else {
            throw new RuntimeException("Called showDialog(int) with unknown dialog id.");
        }

        fragment.setTargetFragment(this, dialogId);
        fragment.show(getParentFragmentManager(), getDialogTag(dialogId));
    }

    private void removeDialog(int dialogId) {
        if (!isAdded()) {
            return;
        }

        FragmentManager fm = getParentFragmentManager();

        // Make sure the "show dialog" transaction has been processed when we call
        // findFragmentByTag() below. Otherwise the fragment won't be found and the dialog will
        // never be dismissed.
        fm.executePendingTransactions();

        DialogFragment fragment = (DialogFragment) fm.findFragmentByTag(getDialogTag(dialogId));

        if (fragment != null) {
            fragment.dismissAllowingStateLoss();
        }
    }

    private String getDialogTag(int dialogId) {
        return String.format(Locale.US, "dialog-%d", dialogId);
    }

    public void zoom(KeyEvent event) {
        // mMessageView.zoom(event);
    }

    @Override
    public void doPositiveClick(int dialogId) {
        if (dialogId == R.id.dialog_confirm_delete) {
            delete();
        } else if (dialogId == R.id.dialog_confirm_spam) {
            refileMessage(mDstFolder);
            mDstFolder = null;
        }
    }

    @Override
    public void doNegativeClick(int dialogId) {
        /* do nothing */
    }

    @Override
    public void dialogCancelled(int dialogId) {
        /* do nothing */
    }

    /**
     * Get the {@link MessageReference} of the currently displayed message.
     */
    public MessageReference getMessageReference() {
        return mMessageReference;
    }

    public boolean isMessageRead() {
        return (mMessage != null) && mMessage.isSet(Flag.SEEN);
    }

    public boolean isCopyCapable() {
        return mController.isCopyCapable(mAccount);
    }

    public boolean isMoveCapable() {
        return mController.isMoveCapable(mAccount);
    }

    public boolean canMessageBeArchived() {
        return (!mMessageReference.getFolderServerId().equals(mAccount.getArchiveFolder())
                && mAccount.hasArchiveFolder());
    }

    public boolean canMessageBeMovedToSpam() {
        return (!mMessageReference.getFolderServerId().equals(mAccount.getSpamFolder())
                && mAccount.hasSpamFolder());
    }

    public Context getApplicationContext() {
        return mContext;
    }

    public void disableAttachmentButtons(AttachmentViewInfo attachment) {
        // mMessageView.disableAttachmentButtons(attachment);
    }

    public void enableAttachmentButtons(AttachmentViewInfo attachment) {
        // mMessageView.enableAttachmentButtons(attachment);
    }

    public void runOnMainThread(Runnable runnable) {
        handler.post(runnable);
    }

    public void showAttachmentLoadingDialog() {
        // mMessageView.disableAttachmentButtons();
        showDialog(R.id.dialog_attachment_progress);
    }

    public void hideAttachmentLoadingDialogOnMainThread() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                removeDialog(R.id.dialog_attachment_progress);
                // mMessageView.enableAttachmentButtons();
            }
        });
    }

    public void refreshAttachmentThumbnail(AttachmentViewInfo attachment) {
        // mMessageView.refreshAttachmentThumbnail(attachment);
    }

    private MessageCryptoMvpView messageCryptoMvpView = new MessageCryptoMvpView() {
        @Override
        public void redisplayMessage() {
            messageLoaderHelper.asyncReloadMessage();
        }

        @Override
        public void startPendingIntentForCryptoPresenter(IntentSender si, Integer requestCode, Intent fillIntent,
                int flagsMask, int flagValues, int extraFlags) throws SendIntentException {
            if (requestCode == null) {
                getActivity().startIntentSender(si, fillIntent, flagsMask, flagValues, extraFlags);
                return;
            }

            requestCode |= REQUEST_MASK_CRYPTO_PRESENTER;
            getActivity().startIntentSenderForResult(
                    si, requestCode, fillIntent, flagsMask, flagValues, extraFlags);
        }

        @Override
        public void showCryptoInfoDialog(MessageCryptoDisplayStatus displayStatus, boolean hasSecurityWarning) {
            CryptoInfoDialog dialog = CryptoInfoDialog.newInstance(displayStatus, hasSecurityWarning);
            dialog.setTargetFragment(MessageViewFragment.this, 0);
            dialog.show(getParentFragmentManager(), "crypto_info_dialog");
        }

        @Override
        public void restartMessageCryptoProcessing() {
            mMessageView.setToLoadingState();
            messageLoaderHelper.asyncRestartMessageCryptoProcessing();
        }

        @Override
        public void showCryptoConfigDialog() {
            AccountSettingsActivity.startCryptoSettings(getActivity(), mAccount.getUuid());
        }
    };

    @Override
    public void onClickShowSecurityWarning() {
        messageCryptoPresenter.onClickShowCryptoWarningDetails();
    }

    @Override
    public void onClickSearchKey() {
        messageCryptoPresenter.onClickSearchKey();
    }

    @Override
    public void onClickShowCryptoKey() {
        messageCryptoPresenter.onClickShowCryptoKey();
    }

    public void asyncReloadMessage() {
        messageLoaderHelper.asyncReloadMessage();
    }

    public void setRenderPlainFormat(final boolean b) {
        mMessageView.setRenderPlainFormat(b);
    }

    public boolean getRenderPlainFormat() {
        return mMessageView.getRenderPlainFormat();
    }

    public interface MessageViewFragmentListener {
        void onForward(MessageReference messageReference, Parcelable decryptionResultForReply);
        void onForwardAsAttachment(MessageReference messageReference, Parcelable decryptionResultForReply);
        void onEditAsNewMessage(MessageReference messageReference);
        void disableDeleteAction();
        void onReplyAll(MessageReference messageReference, Parcelable decryptionResultForReply);
        void onReply(MessageReference messageReference, Parcelable decryptionResultForReply);
        void setProgress(boolean b);
        void showNextMessageOrReturn();
        void updateMenu();
    }

    public boolean isInitialized() {
        return mInitialized ;
    }


    private MessageLoaderCallbacks messageLoaderCallbacks = new MessageLoaderCallbacks() {
        @Override
        public void onMessageDataLoadFinished(LocalMessage message) {
            mMessage = message;

            displayHeaderForLoadingMessage(message);
            mMessageView.setToLoadingState();
            showProgressThreshold = null;
        }

        @Override
        public void onMessageDataLoadFailed() {
            Toast.makeText(getActivity(), R.string.status_loading_error, Toast.LENGTH_LONG).show();
            showProgressThreshold = null;
        }

        @Override
        public void onMessageViewInfoLoadFinished(MessageViewInfo messageViewInfo) {
            showMessage(messageViewInfo);
            showProgressThreshold = null;
        }

        @Override
        public void onMessageViewInfoLoadFailed(MessageViewInfo messageViewInfo) {
            showMessage(messageViewInfo);
            showProgressThreshold = null;
        }

        @Override
        public void setLoadingProgress(int current, int max) {
            if (showProgressThreshold == null) {
                showProgressThreshold = SystemClock.elapsedRealtime() + PROGRESS_THRESHOLD_MILLIS;
            } else if (showProgressThreshold == 0L || SystemClock.elapsedRealtime() > showProgressThreshold) {
                showProgressThreshold = 0L;
                mMessageView.setLoadingProgress(current, max);
            }
        }

        @Override
        public void onDownloadErrorMessageNotFound() {
            mMessageView.enableDownloadButton();
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getActivity(), R.string.status_invalid_id_error, Toast.LENGTH_LONG).show();
                }
            });
        }

        @Override
        public void onDownloadErrorNetworkError() {
            mMessageView.enableDownloadButton();
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getActivity(), R.string.status_network_error, Toast.LENGTH_LONG).show();
                }
            });
        }

        @Override
        public void startIntentSenderForMessageLoaderHelper(IntentSender si, int requestCode, Intent fillIntent,
                int flagsMask, int flagValues, int extraFlags) {
            showProgressThreshold = null;
            try {
                requestCode |= REQUEST_MASK_LOADER_HELPER;
                getActivity().startIntentSenderForResult(
                        si, requestCode, fillIntent, flagsMask, flagValues, extraFlags);
            } catch (SendIntentException e) {
                Timber.e(e, "Irrecoverable error calling PendingIntent!");
            }
        }
    };


    @Override
    public void onViewAttachment(AttachmentViewInfo attachment) {
        currentAttachmentViewInfo = attachment;
        getAttachmentController(attachment).viewAttachment();
    }

    @Override
    public void onSaveAttachment(final AttachmentViewInfo attachment) {
        currentAttachmentViewInfo = attachment;

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType(attachment.mimeType);
        intent.putExtra(Intent.EXTRA_TITLE, attachment.displayName);
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        try {
            startActivityForResult(intent, REQUEST_CODE_CREATE_DOCUMENT);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), R.string.error_activity_not_found, Toast.LENGTH_LONG).show();
        }
    }

    private AttachmentController getAttachmentController(AttachmentViewInfo attachment) {
        return new AttachmentController(mController, this, attachment);
    }
}
