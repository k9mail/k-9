package com.fsck.k9.activity;


import java.io.File;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentSender;
import android.content.IntentSender.SendIntentException;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.ActionBar;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnFocusChangeListener;
import android.view.ViewStub;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.fsck.k9.Account;
import com.fsck.k9.Account.MessageFormat;
import com.fsck.k9.DI;
import com.fsck.k9.Identity;
import com.fsck.k9.K9;
import com.fsck.k9.Preferences;
import com.fsck.k9.activity.MessageLoaderHelper.MessageLoaderCallbacks;
import com.fsck.k9.activity.compose.AttachmentPresenter;
import com.fsck.k9.activity.compose.AttachmentPresenter.AttachmentMvpView;
import com.fsck.k9.activity.compose.AttachmentPresenter.WaitingAction;
import com.fsck.k9.activity.compose.ComposeCryptoStatus;
import com.fsck.k9.activity.compose.ComposeCryptoStatus.SendErrorState;
import com.fsck.k9.activity.compose.IdentityAdapter;
import com.fsck.k9.activity.compose.IdentityAdapter.IdentityContainer;
import com.fsck.k9.activity.compose.PgpEnabledErrorDialog.OnOpenPgpDisableListener;
import com.fsck.k9.activity.compose.PgpInlineDialog.OnOpenPgpInlineChangeListener;
import com.fsck.k9.activity.compose.PgpSignOnlyDialog.OnOpenPgpSignOnlyChangeListener;
import com.fsck.k9.activity.compose.RecipientMvpView;
import com.fsck.k9.activity.compose.RecipientPresenter;
import com.fsck.k9.activity.compose.SaveMessageTask;
import com.fsck.k9.activity.misc.Attachment;
import com.fsck.k9.autocrypt.AutocryptDraftStateHeaderParser;
import com.fsck.k9.controller.MessageReference;
import com.fsck.k9.controller.MessagingController;
import com.fsck.k9.controller.MessagingListener;
import com.fsck.k9.controller.SimpleMessagingListener;
import com.fsck.k9.fragment.AttachmentDownloadDialogFragment;
import com.fsck.k9.fragment.AttachmentDownloadDialogFragment.AttachmentDownloadCancelListener;
import com.fsck.k9.fragment.ProgressDialogFragment;
import com.fsck.k9.fragment.ProgressDialogFragment.CancelListener;
import com.fsck.k9.helper.Contacts;
import com.fsck.k9.helper.IdentityHelper;
import com.fsck.k9.helper.MailTo;
import com.fsck.k9.helper.ReplyToParser;
import com.fsck.k9.helper.SimpleTextWatcher;
import com.fsck.k9.helper.Utility;
import com.fsck.k9.mail.Flag;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.Message.RecipientType;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.internet.MimeMessage;
import com.fsck.k9.mail.internet.MimeUtility;
import com.fsck.k9.mailstore.LocalMessage;
import com.fsck.k9.mailstore.MessageViewInfo;
import com.fsck.k9.message.AutocryptStatusInteractor;
import com.fsck.k9.message.ComposePgpEnableByDefaultDecider;
import com.fsck.k9.message.ComposePgpInlineDecider;
import com.fsck.k9.message.IdentityField;
import com.fsck.k9.message.IdentityHeaderParser;
import com.fsck.k9.message.MessageBuilder;
import com.fsck.k9.message.PgpMessageBuilder;
import com.fsck.k9.message.QuotedTextMode;
import com.fsck.k9.message.SimpleMessageBuilder;
import com.fsck.k9.message.SimpleMessageFormat;
import com.fsck.k9.search.LocalSearch;
import com.fsck.k9.ui.EolConvertingEditText;
import com.fsck.k9.ui.R;
import com.fsck.k9.ui.ThemeManager;
import com.fsck.k9.ui.compose.QuotedMessageMvpView;
import com.fsck.k9.ui.compose.QuotedMessagePresenter;
import com.fsck.k9.ui.helper.SizeFormatter;
import com.fsck.k9.ui.messagelist.DefaultFolderProvider;

import org.openintents.openpgp.OpenPgpApiManager;
import org.openintents.openpgp.util.OpenPgpApi;
import timber.log.Timber;


@SuppressWarnings("deprecation") // TODO get rid of activity dialogs and indeterminate progress bars
public class MessageCompose extends K9Activity implements OnClickListener,
        CancelListener, AttachmentDownloadCancelListener, OnFocusChangeListener,
        OnOpenPgpInlineChangeListener, OnOpenPgpSignOnlyChangeListener, MessageBuilder.Callback,
        AttachmentPresenter.AttachmentsChangedListener, OnOpenPgpDisableListener {

    private static final int DIALOG_SAVE_OR_DISCARD_DRAFT_MESSAGE = 1;
    private static final int DIALOG_CONFIRM_DISCARD_ON_BACK = 2;
    private static final int DIALOG_CHOOSE_IDENTITY = 3;
    private static final int DIALOG_CONFIRM_DISCARD = 4;

    private static final long INVALID_DRAFT_ID = MessagingController.INVALID_MESSAGE_ID;

    public static final String ACTION_COMPOSE = "com.fsck.k9.intent.action.COMPOSE";
    public static final String ACTION_REPLY = "com.fsck.k9.intent.action.REPLY";
    public static final String ACTION_REPLY_ALL = "com.fsck.k9.intent.action.REPLY_ALL";
    public static final String ACTION_FORWARD = "com.fsck.k9.intent.action.FORWARD";
    public static final String ACTION_FORWARD_AS_ATTACHMENT = "com.fsck.k9.intent.action.FORWARD_AS_ATTACHMENT";
    public static final String ACTION_EDIT_DRAFT = "com.fsck.k9.intent.action.EDIT_DRAFT";
    private static final String ACTION_AUTOCRYPT_PEER = "org.autocrypt.PEER_ACTION";

    public static final String EXTRA_ACCOUNT = "account";
    public static final String EXTRA_MESSAGE_REFERENCE = "message_reference";
    public static final String EXTRA_MESSAGE_DECRYPTION_RESULT = "message_decryption_result";

    private static final String STATE_KEY_SOURCE_MESSAGE_PROCED =
            "com.fsck.k9.activity.MessageCompose.stateKeySourceMessageProced";
    private static final String STATE_KEY_DRAFT_ID = "com.fsck.k9.activity.MessageCompose.draftId";
    private static final String STATE_IDENTITY_CHANGED =
            "com.fsck.k9.activity.MessageCompose.identityChanged";
    private static final String STATE_IDENTITY =
            "com.fsck.k9.activity.MessageCompose.identity";
    private static final String STATE_IN_REPLY_TO = "com.fsck.k9.activity.MessageCompose.inReplyTo";
    private static final String STATE_REFERENCES = "com.fsck.k9.activity.MessageCompose.references";
    private static final String STATE_KEY_READ_RECEIPT = "com.fsck.k9.activity.MessageCompose.messageReadReceipt";
    private static final String STATE_KEY_CHANGES_MADE_SINCE_LAST_SAVE = "com.fsck.k9.activity.MessageCompose.changesMadeSinceLastSave";
    private static final String STATE_ALREADY_NOTIFIED_USER_OF_EMPTY_SUBJECT = "alreadyNotifiedUserOfEmptySubject";

    private static final String FRAGMENT_WAITING_FOR_ATTACHMENT = "waitingForAttachment";

    private static final int MSG_PROGRESS_ON = 1;
    private static final int MSG_PROGRESS_OFF = 2;
    public static final int MSG_SAVED_DRAFT = 4;
    private static final int MSG_DISCARDED_DRAFT = 5;

    private static final int REQUEST_MASK_RECIPIENT_PRESENTER = (1 << 8);
    private static final int REQUEST_MASK_LOADER_HELPER = (1 << 9);
    private static final int REQUEST_MASK_ATTACHMENT_PRESENTER = (1 << 10);
    private static final int REQUEST_MASK_MESSAGE_BUILDER = (1 << 11);

    /**
     * Regular expression to remove the first localized "Re:" prefix in subjects.
     *
     * Currently:
     * - "Aw:" (german: abbreviation for "Antwort")
     */
    private static final Pattern PREFIX = Pattern.compile("^AW[:\\s]\\s*", Pattern.CASE_INSENSITIVE);

    private final MessageLoaderHelperFactory messageLoaderHelperFactory = DI.get(MessageLoaderHelperFactory.class);
    private final DefaultFolderProvider defaultFolderProvider = DI.get(DefaultFolderProvider.class);

    private QuotedMessagePresenter quotedMessagePresenter;
    private MessageLoaderHelper messageLoaderHelper;
    private AttachmentPresenter attachmentPresenter;
    private SizeFormatter sizeFormatter;

    private Contacts contacts;

    /**
     * The account used for message composition.
     */
    private Account account;
    private Identity identity;
    private boolean identityChanged = false;
    private boolean signatureChanged = false;

    // relates to the message being replied to, forwarded, or edited TODO split up?
    private MessageReference relatedMessageReference;
    /**
     * Indicates that the source message has been processed at least once and should not
     * be processed on any subsequent loads. This protects us from adding attachments that
     * have already been added from the restore of the view state.
     */
    private boolean relatedMessageProcessed = false;
    private MessageViewInfo currentMessageViewInfo;

    private RecipientPresenter recipientPresenter;
    private MessageBuilder currentMessageBuilder;
    private boolean finishAfterDraftSaved;
    private boolean alreadyNotifiedUserOfEmptySubject = false;
    private boolean changesMadeSinceLastSave = false;

    /**
     * The database ID of this message's draft. This is used when saving drafts so the message in
     * the database is updated instead of being created anew. This property is INVALID_DRAFT_ID
     * until the first save.
     */
    private long draftId = INVALID_DRAFT_ID;

    private Action action;

    private boolean requestReadReceipt = false;

    private TextView chooseIdentityButton;
    private EditText subjectView;
    private EolConvertingEditText signatureView;
    private EolConvertingEditText messageContentView;
    private LinearLayout attachmentsView;

    private String referencedMessageIds;
    private String repliedToMessageId;

    // The currently used message format.
    private SimpleMessageFormat currentMessageFormat;

    private boolean isInSubActivity = false;

    private boolean navigateUp;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (UpgradeDatabases.actionUpgradeDatabases(this, getIntent())) {
            finishAndRemoveTask();
            return;
        }
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);

        setLayout(R.layout.message_compose);
        ViewStub contentContainer = findViewById(R.id.message_compose_content);

        sizeFormatter = new SizeFormatter(getResources());

        ThemeManager themeManager = getThemeManager();
        int messageComposeThemeResourceId = themeManager.getMessageComposeThemeResourceId();
        ContextThemeWrapper themeContext = new ContextThemeWrapper(this, messageComposeThemeResourceId);

        LayoutInflater themedLayoutInflater = LayoutInflater.from(themeContext);
        contentContainer.setLayoutInflater(themedLayoutInflater);

        View contentView = contentContainer.inflate();

        // background color needs to be forced
        //TODO: Change themes to use appropriate background colors that don't need overriding.
        TypedValue outValue = new TypedValue();
        themeContext.getTheme().resolveAttribute(R.attr.messageViewBackgroundColor, outValue, true);

        contentView.setBackgroundColor(outValue.data);

        initializeActionBar();

        // on api level 15, setContentView() shows the progress bar for some reason...
        setProgressBarIndeterminateVisibility(false);

        final Intent intent = getIntent();

        String messageReferenceString = intent.getStringExtra(EXTRA_MESSAGE_REFERENCE);
        relatedMessageReference = MessageReference.parse(messageReferenceString);

        final String accountUuid = (relatedMessageReference != null) ?
                relatedMessageReference.getAccountUuid() :
                intent.getStringExtra(EXTRA_ACCOUNT);

        account = Preferences.getPreferences(this).getAccount(accountUuid);

        if (account == null) {
            account = Preferences.getPreferences(this).getDefaultAccount();
        }

        if (account == null) {
            /*
             * There are no accounts set up. This should not have happened. Prompt the
             * user to set up an account as an acceptable bailout.
             */
            MessageList.launch(this);
            changesMadeSinceLastSave = false;
            finishAndRemoveTask();
            return;
        }

        contacts = Contacts.getInstance(MessageCompose.this);

        chooseIdentityButton = findViewById(R.id.identity);
        chooseIdentityButton.setOnClickListener(this);

        RecipientMvpView recipientMvpView = new RecipientMvpView(this);
        ComposePgpInlineDecider composePgpInlineDecider = new ComposePgpInlineDecider();
        ComposePgpEnableByDefaultDecider composePgpEnableByDefaultDecider = new ComposePgpEnableByDefaultDecider();

        OpenPgpApiManager openPgpApiManager = new OpenPgpApiManager(getApplicationContext(), this);
        recipientPresenter = new RecipientPresenter(getApplicationContext(), getSupportLoaderManager(),
                openPgpApiManager, recipientMvpView, account, composePgpInlineDecider, composePgpEnableByDefaultDecider,
                AutocryptStatusInteractor.getInstance(), new ReplyToParser(),
                DI.get(AutocryptDraftStateHeaderParser.class));
        recipientPresenter.asyncUpdateCryptoStatus();


        subjectView = findViewById(R.id.subject);
        subjectView.getInputExtras(true).putBoolean("allowEmoji", true);

        EolConvertingEditText upperSignature = findViewById(R.id.upper_signature);
        EolConvertingEditText lowerSignature = findViewById(R.id.lower_signature);

        QuotedMessageMvpView quotedMessageMvpView = new QuotedMessageMvpView(this);
        quotedMessagePresenter = new QuotedMessagePresenter(this, quotedMessageMvpView, account);
        attachmentPresenter = new AttachmentPresenter(getApplicationContext(), attachmentMvpView,
                getSupportLoaderManager(), this);

        messageContentView = findViewById(R.id.message_content);
        messageContentView.getInputExtras(true).putBoolean("allowEmoji", true);

        attachmentsView = findViewById(R.id.attachments);

        TextWatcher draftNeedsChangingTextWatcher = new SimpleTextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                changesMadeSinceLastSave = true;
            }
        };

        TextWatcher signTextWatcher = new SimpleTextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                changesMadeSinceLastSave = true;
                signatureChanged = true;
            }
        };

        recipientMvpView.addTextChangedListener(draftNeedsChangingTextWatcher);
        quotedMessageMvpView.addTextChangedListener(draftNeedsChangingTextWatcher);

        subjectView.addTextChangedListener(draftNeedsChangingTextWatcher);

        messageContentView.addTextChangedListener(draftNeedsChangingTextWatcher);

        /*
         * We set this to invisible by default. Other methods will turn it back on if it's
         * needed.
         */

        quotedMessagePresenter.showOrHideQuotedText(QuotedTextMode.NONE);

        subjectView.setOnFocusChangeListener(this);
        messageContentView.setOnFocusChangeListener(this);

        if (savedInstanceState != null) {
            /*
             * This data gets used in onCreate, so grab it here instead of onRestoreInstanceState
             */
            relatedMessageProcessed = savedInstanceState.getBoolean(STATE_KEY_SOURCE_MESSAGE_PROCED, false);
        }


        if (initFromIntent(intent)) {
            action = Action.COMPOSE;
            changesMadeSinceLastSave = true;
        } else {
            String action = intent.getAction();
            if (ACTION_COMPOSE.equals(action)) {
                this.action = Action.COMPOSE;
            } else if (ACTION_REPLY.equals(action)) {
                this.action = Action.REPLY;
            } else if (ACTION_REPLY_ALL.equals(action)) {
                this.action = Action.REPLY_ALL;
            } else if (ACTION_FORWARD.equals(action)) {
                this.action = Action.FORWARD;
            } else if (ACTION_FORWARD_AS_ATTACHMENT.equals(action)) {
                this.action = Action.FORWARD_AS_ATTACHMENT;
            } else if (ACTION_EDIT_DRAFT.equals(action)) {
                this.action = Action.EDIT_DRAFT;
            } else {
                // This shouldn't happen
                Timber.w("MessageCompose was started with an unsupported action");
                this.action = Action.COMPOSE;
            }
        }

        if (identity == null) {
            identity = account.getIdentity(0);
        }

        if (account.isSignatureBeforeQuotedText()) {
            signatureView = upperSignature;
            lowerSignature.setVisibility(View.GONE);
        } else {
            signatureView = lowerSignature;
            upperSignature.setVisibility(View.GONE);
        }
        updateSignature();
        signatureView.addTextChangedListener(signTextWatcher);

        if (!identity.getSignatureUse()) {
            signatureView.setVisibility(View.GONE);
        }

        requestReadReceipt = account.isMessageReadReceipt();

        updateFrom();

        if (!relatedMessageProcessed) {
            if (action == Action.REPLY || action == Action.REPLY_ALL ||
                    action == Action.FORWARD || action == Action.FORWARD_AS_ATTACHMENT ||
                    action == Action.EDIT_DRAFT) {
                messageLoaderHelper = messageLoaderHelperFactory.createForMessageCompose(this,
                        getSupportLoaderManager(), getSupportFragmentManager(), messageLoaderCallbacks);
                internalMessageHandler.sendEmptyMessage(MSG_PROGRESS_ON);

                if (action == Action.FORWARD_AS_ATTACHMENT) {
                    messageLoaderHelper.asyncStartOrResumeLoadingMessageMetadata(relatedMessageReference);
                } else {
                    Parcelable cachedDecryptionResult = intent.getParcelableExtra(EXTRA_MESSAGE_DECRYPTION_RESULT);
                    messageLoaderHelper.asyncStartOrResumeLoadingMessage(
                            relatedMessageReference, cachedDecryptionResult);
                }
            }
        }

        if (action == Action.REPLY || action == Action.REPLY_ALL) {
            relatedMessageReference = relatedMessageReference.withModifiedFlag(Flag.ANSWERED);
        }

        if (action == Action.REPLY || action == Action.REPLY_ALL ||
                action == Action.EDIT_DRAFT) {
            //change focus to message body.
            messageContentView.requestFocus();
        } else {
            // Explicitly set focus to "To:" input field (see issue 2998)
            recipientMvpView.requestFocusOnToField();
        }

        if (action == Action.FORWARD || action == Action.FORWARD_AS_ATTACHMENT) {
            relatedMessageReference = relatedMessageReference.withModifiedFlag(Flag.FORWARDED);
        }

        updateMessageFormat();

        // Set font size of input controls
        int fontSize = K9.getFontSizes().getMessageComposeInput();
        recipientMvpView.setFontSizes(K9.getFontSizes(), fontSize);
        quotedMessageMvpView.setFontSizes(K9.getFontSizes(), fontSize);
        K9.getFontSizes().setViewTextSize(subjectView, fontSize);
        K9.getFontSizes().setViewTextSize(messageContentView, fontSize);
        K9.getFontSizes().setViewTextSize(signatureView, fontSize);


        updateMessageFormat();

        setTitle();

        currentMessageBuilder = (MessageBuilder) getLastCustomNonConfigurationInstance();
        if (currentMessageBuilder != null) {
            setProgressBarIndeterminateVisibility(true);
            currentMessageBuilder.reattachCallback(this);
        }

        if (savedInstanceState == null) {
            checkAndRequestPermissions();
        }
    }

    /**
     * Handle external intents that trigger the message compose activity.
     *
     * <p>
     * Supported external intents:
     * <ul>
     *   <li>{@link Intent#ACTION_VIEW}</li>
     *   <li>{@link Intent#ACTION_SENDTO}</li>
     *   <li>{@link Intent#ACTION_SEND}</li>
     *   <li>{@link Intent#ACTION_SEND_MULTIPLE}</li>
     * </ul>
     * </p>
     *
     * @param intent
     *         The (external) intent that started the activity.
     *
     * @return {@code true}, if this activity was started by an external intent. {@code false},
     *         otherwise.
     */
    private boolean initFromIntent(final Intent intent) {
        boolean startedByExternalIntent = false;
        final String action = intent.getAction();

        if (Intent.ACTION_VIEW.equals(action) || Intent.ACTION_SENDTO.equals(action)) {
            /*
             * Someone has clicked a mailto: link. The address is in the URI.
             */
            if (intent.getData() != null) {
                Uri uri = intent.getData();
                if (MailTo.isMailTo(uri)) {
                    MailTo mailTo = MailTo.parse(uri);
                    initializeFromMailto(mailTo);
                }
            }

            /*
             * Note: According to the documentation ACTION_VIEW and ACTION_SENDTO don't accept
             * EXTRA_* parameters.
             * And previously we didn't process these EXTRAs. But it looks like nobody bothers to
             * read the official documentation and just copies wrong sample code that happens to
             * work with the AOSP Email application. And because even big players get this wrong,
             * we're now finally giving in and read the EXTRAs for those actions (below).
             */
        }

        if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action) ||
                Intent.ACTION_SENDTO.equals(action) || Intent.ACTION_VIEW.equals(action)) {
            startedByExternalIntent = true;

            /*
             * Note: Here we allow a slight deviation from the documented behavior.
             * EXTRA_TEXT is used as message body (if available) regardless of the MIME
             * type of the intent. In addition one or multiple attachments can be added
             * using EXTRA_STREAM.
             */
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            // Only use EXTRA_TEXT if the body hasn't already been set by the mailto URI
            if (text != null && messageContentView.getText().length() == 0) {
                messageContentView.setCharacters(text);
            }

            String type = intent.getType();
            if (Intent.ACTION_SEND.equals(action)) {
                Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (stream != null) {
                    attachmentPresenter.addAttachment(stream, type);
                }
            } else {
                List<Parcelable> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (list != null) {
                    for (Parcelable parcelable : list) {
                        Uri stream = (Uri) parcelable;
                        if (stream != null) {
                            attachmentPresenter.addAttachment(stream, type);
                        }
                    }
                }
            }

            String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
            // Only use EXTRA_SUBJECT if the subject hasn't already been set by the mailto URI
            if (subject != null && subjectView.getText().length() == 0) {
                subjectView.setText(subject);
            }

            recipientPresenter.initFromSendOrViewIntent(intent);
        }

        if (ACTION_AUTOCRYPT_PEER.equals(action)) {
            String trustId = intent.getStringExtra(OpenPgpApi.EXTRA_AUTOCRYPT_PEER_ID);
            if (trustId != null) {
                recipientPresenter.initFromTrustIdAction(trustId);
                startedByExternalIntent = true;
            }
        }

        return startedByExternalIntent;
    }

    @Override
    protected void onResume() {
        super.onResume();
        MessagingController.getInstance(this).addListener(messagingListener);
    }

    @Override
    public void onPause() {
        super.onPause();
        MessagingController.getInstance(this).removeListener(messagingListener);

        boolean isPausingOnConfigurationChange = (getChangingConfigurations() & ActivityInfo.CONFIG_ORIENTATION)
                == ActivityInfo.CONFIG_ORIENTATION;
        boolean isCurrentlyBuildingMessage = currentMessageBuilder != null;

        if (isPausingOnConfigurationChange || isCurrentlyBuildingMessage || isInSubActivity) {
            return;
        }

        checkToSaveDraftImplicitly();
    }

    /**
     * The framework handles most of the fields, but we need to handle stuff that we
     * dynamically show and hide:
     * Attachment list,
     * Cc field,
     * Bcc field,
     * Quoted text,
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putBoolean(STATE_KEY_SOURCE_MESSAGE_PROCED, relatedMessageProcessed);
        outState.putLong(STATE_KEY_DRAFT_ID, draftId);
        outState.putParcelable(STATE_IDENTITY, identity);
        outState.putBoolean(STATE_IDENTITY_CHANGED, identityChanged);
        outState.putString(STATE_IN_REPLY_TO, repliedToMessageId);
        outState.putString(STATE_REFERENCES, referencedMessageIds);
        outState.putBoolean(STATE_KEY_READ_RECEIPT, requestReadReceipt);
        outState.putBoolean(STATE_KEY_CHANGES_MADE_SINCE_LAST_SAVE, changesMadeSinceLastSave);
        outState.putBoolean(STATE_ALREADY_NOTIFIED_USER_OF_EMPTY_SUBJECT, alreadyNotifiedUserOfEmptySubject);

        recipientPresenter.onSaveInstanceState(outState);
        quotedMessagePresenter.onSaveInstanceState(outState);
        attachmentPresenter.onSaveInstanceState(outState);
    }

    @Override
    public Object onRetainCustomNonConfigurationInstance() {
        if (currentMessageBuilder != null) {
            currentMessageBuilder.detachCallback();
        }
        return currentMessageBuilder;
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);

        attachmentsView.removeAllViews();

        requestReadReceipt = savedInstanceState.getBoolean(STATE_KEY_READ_RECEIPT);

        recipientPresenter.onRestoreInstanceState(savedInstanceState);
        quotedMessagePresenter.onRestoreInstanceState(savedInstanceState);
        attachmentPresenter.onRestoreInstanceState(savedInstanceState);

        draftId = savedInstanceState.getLong(STATE_KEY_DRAFT_ID);
        identity = savedInstanceState.getParcelable(STATE_IDENTITY);
        identityChanged = savedInstanceState.getBoolean(STATE_IDENTITY_CHANGED);
        repliedToMessageId = savedInstanceState.getString(STATE_IN_REPLY_TO);
        referencedMessageIds = savedInstanceState.getString(STATE_REFERENCES);
        changesMadeSinceLastSave = savedInstanceState.getBoolean(STATE_KEY_CHANGES_MADE_SINCE_LAST_SAVE);
        alreadyNotifiedUserOfEmptySubject = savedInstanceState.getBoolean(STATE_ALREADY_NOTIFIED_USER_OF_EMPTY_SUBJECT);

        updateFrom();

        updateMessageFormat();
    }

    private void checkAndRequestPermissions() {
        if (!hasPermission(Permission.READ_CONTACTS)) {
            requestPermissionOrShowRationale(Permission.READ_CONTACTS);
        }
    }

    private void setTitle() {
        setTitle(action.getTitleResource());
    }

    @Nullable
    private MessageBuilder createMessageBuilder(boolean isDraft) {
        MessageBuilder builder;

        ComposeCryptoStatus cryptoStatus = recipientPresenter.getCurrentCachedCryptoStatus();
        if (cryptoStatus == null) {
            return null;
        }

        boolean shouldUsePgpMessageBuilder = cryptoStatus.isOpenPgpConfigured();
        if (shouldUsePgpMessageBuilder) {
            SendErrorState maybeSendErrorState = cryptoStatus.getSendErrorStateOrNull();
            if (maybeSendErrorState != null) {
                recipientPresenter.showPgpSendError(maybeSendErrorState);
                return null;
            }

            PgpMessageBuilder pgpBuilder = PgpMessageBuilder.newInstance();
            recipientPresenter.builderSetProperties(pgpBuilder, cryptoStatus);
            builder = pgpBuilder;
        } else {
            builder = SimpleMessageBuilder.newInstance();
            recipientPresenter.builderSetProperties(builder);
        }

        builder.setSubject(Utility.stripNewLines(subjectView.getText().toString()))
                .setSentDate(new Date())
                .setHideTimeZone(K9.isHideTimeZone())
                .setInReplyTo(repliedToMessageId)
                .setReferences(referencedMessageIds)
                .setRequestReadReceipt(requestReadReceipt)
                .setIdentity(identity)
                .setMessageFormat(currentMessageFormat)
                .setText(messageContentView.getCharacters())
                .setAttachments(attachmentPresenter.getAttachments())
                .setSignature(signatureView.getCharacters())
                .setSignatureBeforeQuotedText(account.isSignatureBeforeQuotedText())
                .setIdentityChanged(identityChanged)
                .setSignatureChanged(signatureChanged)
                .setCursorPosition(messageContentView.getSelectionStart())
                .setMessageReference(relatedMessageReference)
                .setDraft(isDraft)
                .setIsPgpInlineEnabled(cryptoStatus.isPgpInlineModeEnabled());

        quotedMessagePresenter.builderSetProperties(builder);

        return builder;
    }

    private void checkToSendMessage() {
        if (subjectView.getText().length() == 0 && !alreadyNotifiedUserOfEmptySubject) {
            Toast.makeText(this, R.string.empty_subject, Toast.LENGTH_LONG).show();
            alreadyNotifiedUserOfEmptySubject = true;
            return;
        }

        if (recipientPresenter.checkRecipientsOkForSending()) {
            return;
        }

        if (attachmentPresenter.checkOkForSendingOrDraftSaving()) {
            return;
        }

        performSendAfterChecks();
    }

    private void checkToSaveDraftAndSave() {
        if (!account.hasDraftsFolder()) {
            Toast.makeText(this, R.string.compose_error_no_draft_folder, Toast.LENGTH_SHORT).show();
            return;
        }

        if (attachmentPresenter.checkOkForSendingOrDraftSaving()) {
            return;
        }

        finishAfterDraftSaved = true;
        performSaveAfterChecks();
    }

    private void checkToSaveDraftImplicitly() {
        if (!account.hasDraftsFolder()) {
            return;
        }

        if (!changesMadeSinceLastSave) {
            return;
        }

        finishAfterDraftSaved = false;
        performSaveAfterChecks();
    }

    private void performSaveAfterChecks() {
        currentMessageBuilder = createMessageBuilder(true);
        if (currentMessageBuilder != null) {
            setProgressBarIndeterminateVisibility(true);
            currentMessageBuilder.buildAsync(this);
        }
    }

    public void performSendAfterChecks() {
        currentMessageBuilder = createMessageBuilder(false);
        if (currentMessageBuilder != null) {
            changesMadeSinceLastSave = false;
            setProgressBarIndeterminateVisibility(true);
            currentMessageBuilder.buildAsync(this);
        }
    }

    private void onDiscard() {
        if (draftId != INVALID_DRAFT_ID) {
            MessagingController.getInstance(getApplication()).deleteDraft(account, draftId);
            draftId = INVALID_DRAFT_ID;
        }
        internalMessageHandler.sendEmptyMessage(MSG_DISCARDED_DRAFT);
        changesMadeSinceLastSave = false;
        if (navigateUp) {
            openDefaultFolder();
        } else {
            finishAndRemoveTask();
        }
    }

    private void onReadReceipt() {
        CharSequence txt;
        if (!requestReadReceipt) {
            txt = getString(R.string.read_receipt_enabled);
            requestReadReceipt = true;
        } else {
            txt = getString(R.string.read_receipt_disabled);
            requestReadReceipt = false;
        }
        Context context = getApplicationContext();
        Toast toast = Toast.makeText(context, txt, Toast.LENGTH_SHORT);
        toast.show();
    }

    public void showContactPicker(int requestCode) {
        requestCode |= REQUEST_MASK_RECIPIENT_PRESENTER;
        isInSubActivity = true;
        startActivityForResult(contacts.contactPickerIntent(), requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        isInSubActivity = false;

        if ((requestCode & REQUEST_MASK_MESSAGE_BUILDER) == REQUEST_MASK_MESSAGE_BUILDER) {
            requestCode ^= REQUEST_MASK_MESSAGE_BUILDER;
            if (currentMessageBuilder == null) {
                Timber.e("Got a message builder activity result for no message builder, " +
                        "this is an illegal state!");
                return;
            }
            currentMessageBuilder.onActivityResult(requestCode, resultCode, data, this);
            return;
        }

        if ((requestCode & REQUEST_MASK_RECIPIENT_PRESENTER) == REQUEST_MASK_RECIPIENT_PRESENTER) {
            requestCode ^= REQUEST_MASK_RECIPIENT_PRESENTER;
            recipientPresenter.onActivityResult(requestCode, resultCode, data);
            return;
        }

        if ((requestCode & REQUEST_MASK_LOADER_HELPER) == REQUEST_MASK_LOADER_HELPER) {
            requestCode ^= REQUEST_MASK_LOADER_HELPER;
            messageLoaderHelper.onActivityResult(requestCode, resultCode, data);
            return;
        }

        if ((requestCode & REQUEST_MASK_ATTACHMENT_PRESENTER) == REQUEST_MASK_ATTACHMENT_PRESENTER) {
            requestCode ^= REQUEST_MASK_ATTACHMENT_PRESENTER;
            attachmentPresenter.onActivityResult(resultCode, requestCode, data);
            return;
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    private void onAccountChosen(Account account, Identity identity) {
        if (!this.account.equals(account)) {
            Timber.v("Switching account from %s to %s", this.account, account);

            // on draft edit, make sure we don't keep previous message UID
            if (action == Action.EDIT_DRAFT) {
                relatedMessageReference = null;
            }

            // test whether there is something to save
            if (changesMadeSinceLastSave || (draftId != INVALID_DRAFT_ID)) {
                final long previousDraftId = draftId;
                final Account previousAccount = this.account;

                // make current message appear as new
                draftId = INVALID_DRAFT_ID;

                // actual account switch
                this.account = account;

                Timber.v("Account switch, saving new draft in new account");
                checkToSaveDraftImplicitly();

                if (previousDraftId != INVALID_DRAFT_ID) {
                    Timber.v("Account switch, deleting draft from previous account: %d", previousDraftId);

                    MessagingController.getInstance(getApplication()).deleteDraft(previousAccount,
                            previousDraftId);
                }
            } else {
                this.account = account;
            }

            // Show CC/BCC text input field when switching to an account that always wants them
            // displayed.
            // Please note that we're not hiding the fields if the user switches back to an account
            // that doesn't have this setting checked.
            recipientPresenter.onSwitchAccount(this.account);
            quotedMessagePresenter.onSwitchAccount(this.account);

            // not sure how to handle mFolder, mSourceMessage?
        }

        switchToIdentity(identity);
    }

    private void switchToIdentity(Identity identity) {
        this.identity = identity;
        identityChanged = true;
        changesMadeSinceLastSave = true;
        updateFrom();
        updateSignature();
        updateMessageFormat();
        recipientPresenter.onSwitchIdentity(identity);
    }

    private void updateFrom() {
        chooseIdentityButton.setText(identity.getEmail());
    }

    private void updateSignature() {
        if (identity.getSignatureUse()) {
            signatureView.setCharacters(identity.getSignature());
            signatureView.setVisibility(View.VISIBLE);
        } else {
            signatureView.setVisibility(View.GONE);
        }
    }

    @Override
    public void onFocusChange(View v, boolean hasFocus) {
        int id = v.getId();
        if (id == R.id.message_content || id == R.id.subject) {
            if (hasFocus) {
                recipientPresenter.onNonRecipientFieldFocused();
            }
        }
    }

    @Override
    public void onOpenPgpInlineChange(boolean enabled) {
        recipientPresenter.onCryptoPgpInlineChanged(enabled);
    }

    @Override
    public void onOpenPgpSignOnlyChange(boolean enabled) {
        recipientPresenter.onCryptoPgpSignOnlyDisabled();
    }

    @Override
    public void onOpenPgpClickDisable() {
        recipientPresenter.onCryptoPgpClickDisable();
    }

    @Override
    public void onAttachmentAdded() {
        changesMadeSinceLastSave = true;
    }

    @Override
    public void onAttachmentRemoved() {
        changesMadeSinceLastSave = true;
    }

    @Override
    public void onClick(View view) {
        if (view.getId() == R.id.identity) {
            showDialog(DIALOG_CHOOSE_IDENTITY);
        }
    }

    private void askBeforeDiscard() {
        if (K9.isConfirmDiscardMessage()) {
            showDialog(DIALOG_CONFIRM_DISCARD);
        } else {
            onDiscard();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            prepareToFinish(true);
        } else if (id == R.id.send) {
            checkToSendMessage();
        } else if (id == R.id.save) {
            checkToSaveDraftAndSave();
        } else if (id == R.id.discard) {
            askBeforeDiscard();
        } else if (id == R.id.add_from_contacts) {
            recipientPresenter.onMenuAddFromContacts();
        } else if (id == R.id.openpgp_encrypt_disable) {
            recipientPresenter.onMenuToggleEncryption();
            updateMessageFormat();
        } else if (id == R.id.openpgp_encrypt_enable) {
            recipientPresenter.onMenuToggleEncryption();
            updateMessageFormat();
        } else if (id == R.id.openpgp_inline_enable) {
            recipientPresenter.onMenuSetPgpInline(true);
            updateMessageFormat();
        } else if (id == R.id.openpgp_inline_disable) {
            recipientPresenter.onMenuSetPgpInline(false);
            updateMessageFormat();
        } else if (id == R.id.openpgp_sign_only) {
            recipientPresenter.onMenuSetSignOnly(true);
        } else if (id == R.id.openpgp_sign_only_disable) {
            recipientPresenter.onMenuSetSignOnly(false);
        } else if (id == R.id.add_attachment) {
            attachmentPresenter.onClickAddAttachment(recipientPresenter);
        } else if (id == R.id.read_receipt) {
            onReadReceipt();
        } else {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);

        if (isFinishing()) {
            return false;
        }

        getMenuInflater().inflate(R.menu.message_compose_option, menu);

        // Disable the 'Save' menu option if Drafts folder is set to -NONE-
        if (!account.hasDraftsFolder()) {
            menu.findItem(R.id.save).setEnabled(false);
        }

        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

        recipientPresenter.onPrepareOptionsMenu(menu);

        return true;
    }

    @Override
    public void onBackPressed() {
        prepareToFinish(false);
    }

    private void prepareToFinish(boolean shouldNavigateUp) {
        navigateUp = shouldNavigateUp;

        if (changesMadeSinceLastSave && draftIsNotEmpty()) {
            if (!account.hasDraftsFolder()) {
                showDialog(DIALOG_CONFIRM_DISCARD_ON_BACK);
            } else {
                showDialog(DIALOG_SAVE_OR_DISCARD_DRAFT_MESSAGE);
            }
        } else {
            // Check if editing an existing draft.
            if (draftId == INVALID_DRAFT_ID) {
                onDiscard();
            } else {
                if (navigateUp) {
                    openDefaultFolder();
                } else {
                    super.onBackPressed();
                }
            }
        }
    }

    private void openDefaultFolder() {
        long folderId = defaultFolderProvider.getDefaultFolder(account);
        LocalSearch search = new LocalSearch();
        search.addAccountUuid(account.getUuid());
        search.addAllowedFolder(folderId);
        MessageList.actionDisplaySearch(this, search, false, true);
        finishAndRemoveTask();
    }

    private boolean draftIsNotEmpty() {
        if (messageContentView.getText().length() != 0) {
            return true;
        }
        if (!attachmentPresenter.createAttachmentList().isEmpty()) {
            return true;
        }
        return subjectView.getText().length() != 0 ||
                !recipientPresenter.getToAddresses().isEmpty() ||
                !recipientPresenter.getCcAddresses().isEmpty() ||
                !recipientPresenter.getBccAddresses().isEmpty();
    }

    @Override
    public void onProgressCancel(AttachmentDownloadDialogFragment fragment) {
        attachmentPresenter.attachmentProgressDialogCancelled();
    }

    public void onProgressCancel(ProgressDialogFragment fragment) {
        attachmentPresenter.attachmentProgressDialogCancelled();
    }

    @Override
    public Dialog onCreateDialog(int id) {
        switch (id) {
            case DIALOG_SAVE_OR_DISCARD_DRAFT_MESSAGE:
                return new AlertDialog.Builder(this)
                        .setTitle(R.string.save_or_discard_draft_message_dlg_title)
                        .setMessage(R.string.save_or_discard_draft_message_instructions_fmt)
                        .setPositiveButton(R.string.save_draft_action, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int whichButton) {
                                dismissDialog(DIALOG_SAVE_OR_DISCARD_DRAFT_MESSAGE);
                                checkToSaveDraftAndSave();
                            }
                        })
                        .setNegativeButton(R.string.discard_action, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int whichButton) {
                                dismissDialog(DIALOG_SAVE_OR_DISCARD_DRAFT_MESSAGE);
                                onDiscard();
                            }
                        })
                        .create();
            case DIALOG_CONFIRM_DISCARD_ON_BACK:
                return new AlertDialog.Builder(this)
                        .setTitle(R.string.confirm_discard_draft_message_title)
                        .setMessage(R.string.confirm_discard_draft_message)
                        .setPositiveButton(R.string.cancel_action, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int whichButton) {
                                dismissDialog(DIALOG_CONFIRM_DISCARD_ON_BACK);
                            }
                        })
                        .setNegativeButton(R.string.discard_action, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int whichButton) {
                                dismissDialog(DIALOG_CONFIRM_DISCARD_ON_BACK);
                                Toast.makeText(MessageCompose.this,
                                        getString(R.string.message_discarded_toast),
                                        Toast.LENGTH_LONG).show();
                                onDiscard();
                            }
                        })
                        .create();
            case DIALOG_CHOOSE_IDENTITY:
                int dialogThemeResourceId = getThemeManager().getDialogThemeResourceId();
                Context context = new ContextThemeWrapper(this, dialogThemeResourceId);
                Builder builder = new AlertDialog.Builder(context);
                builder.setTitle(R.string.send_as);
                final IdentityAdapter adapter = new IdentityAdapter(context);
                builder.setAdapter(adapter, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        IdentityContainer container = (IdentityContainer) adapter.getItem(which);
                        onAccountChosen(container.account, container.identity);
                    }
                });

                return builder.create();
            case DIALOG_CONFIRM_DISCARD: {
                return new AlertDialog.Builder(this)
                        .setTitle(R.string.dialog_confirm_delete_title)
                        .setMessage(R.string.dialog_confirm_delete_message)
                        .setPositiveButton(R.string.dialog_confirm_delete_confirm_button,
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int which) {
                                        onDiscard();
                                    }
                                })
                        .setNegativeButton(R.string.dialog_confirm_delete_cancel_button,
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int which) {
                                    }
                                })
                        .create();
            }
        }
        return super.onCreateDialog(id);
    }

    public void saveDraftEventually() {
        changesMadeSinceLastSave = true;
    }

    public void loadQuotedTextForEdit() {
        if (relatedMessageReference == null) { // shouldn't happen...
            throw new IllegalStateException("tried to edit quoted message with no referenced message");
        }

        if (currentMessageViewInfo != null) {
            loadLocalMessageForDisplay(currentMessageViewInfo, action);
        }
    }

    /**
     * Pull out the parts of the now loaded source message and apply them to the new message
     * depending on the type of message being composed.
     *
     * @param messageViewInfo
     *         The source message used to populate the various text fields.
     */
    private void processSourceMessage(MessageViewInfo messageViewInfo) {
        try {
            switch (action) {
                case REPLY:
                case REPLY_ALL: {
                    processMessageToReplyTo(messageViewInfo);
                    break;
                }
                case FORWARD: {
                    processMessageToForward(messageViewInfo, false);
                    break;
                }
                case FORWARD_AS_ATTACHMENT: {
                    processMessageToForward(messageViewInfo, true);
                    break;
                }
                case EDIT_DRAFT: {
                    processDraftMessage(messageViewInfo);
                    break;
                }
                default: {
                    Timber.w("processSourceMessage() called with unsupported action");
                    break;
                }
            }
        } catch (MessagingException e) {
            /*
             * Let the user continue composing their message even if we have a problem processing
             * the source message. Log it as an error, though.
             */
            Timber.e(e, "Error while processing source message: ");
        } finally {
            relatedMessageProcessed = true;
            changesMadeSinceLastSave = false;
        }

        updateMessageFormat();
    }

    private void processMessageToReplyTo(MessageViewInfo messageViewInfo) throws MessagingException {
        Message message = messageViewInfo.message;

        if (messageViewInfo.subject != null) {
            final String subject = PREFIX.matcher(messageViewInfo.subject).replaceFirst("");

            if (!subject.toLowerCase(Locale.US).startsWith("re:")) {
                subjectView.setText("Re: " + subject);
            } else {
                subjectView.setText(subject);
            }
        } else {
            subjectView.setText("");
        }

        /*
         * If a reply-to was included with the message use that, otherwise use the from
         * or sender address.
         */
        boolean isReplyAll = action == Action.REPLY_ALL;
        recipientPresenter.initFromReplyToMessage(message, isReplyAll);

        if (message.getMessageId() != null && message.getMessageId().length() > 0) {
            repliedToMessageId = message.getMessageId();

            String[] refs = message.getReferences();
            if (refs != null && refs.length > 0) {
                referencedMessageIds = TextUtils.join("", refs) + " " + repliedToMessageId;
            } else {
                referencedMessageIds = repliedToMessageId;
            }

        } else {
            Timber.d("could not get Message-ID.");
        }

        // Quote the message and setup the UI.
        quotedMessagePresenter.initFromReplyToMessage(messageViewInfo, action);

        if (action == Action.REPLY || action == Action.REPLY_ALL) {
            Identity useIdentity = IdentityHelper.getRecipientIdentityFromMessage(account, message);
            Identity defaultIdentity = account.getIdentity(0);
            if (useIdentity != defaultIdentity) {
                switchToIdentity(useIdentity);
            }
        }

    }

    private void processMessageToForward(MessageViewInfo messageViewInfo, boolean asAttachment) throws MessagingException {
        Message message = messageViewInfo.message;

        String subject = messageViewInfo.subject;
        if (subject != null && !subject.toLowerCase(Locale.US).startsWith("fwd:")) {
            subjectView.setText("Fwd: " + subject);
        } else {
            subjectView.setText(subject);
        }

        // "Be Like Thunderbird" - on forwarded messages, set the message ID
        // of the forwarded message in the references and the reply to.  TB
        // only includes ID of the message being forwarded in the reference,
        // even if there are multiple references.
        if (!TextUtils.isEmpty(message.getMessageId())) {
            repliedToMessageId = message.getMessageId();
            referencedMessageIds = repliedToMessageId;
        } else {
            Timber.d("could not get Message-ID.");
        }

        // Quote the message and setup the UI.
        if (asAttachment) {
            attachmentPresenter.processMessageToForwardAsAttachment(messageViewInfo);
        } else {
            quotedMessagePresenter.processMessageToForward(messageViewInfo);
            attachmentPresenter.processMessageToForward(messageViewInfo);
        }
    }

    private void processDraftMessage(MessageViewInfo messageViewInfo) {
        Message message = messageViewInfo.message;
        draftId = MessagingController.getInstance(getApplication()).getId(message);
        subjectView.setText(messageViewInfo.subject);

        recipientPresenter.initFromDraftMessage(message);

        // Read In-Reply-To header from draft
        final String[] inReplyTo = message.getHeader("In-Reply-To");
        if (inReplyTo.length >= 1) {
            repliedToMessageId = inReplyTo[0];
        }

        // Read References header from draft
        final String[] references = message.getHeader("References");
        if (references.length >= 1) {
            referencedMessageIds = references[0];
        }

        if (!relatedMessageProcessed) {
            attachmentPresenter.loadNonInlineAttachments(messageViewInfo);
        }

        // Decode the identity header when loading a draft.
        // See buildIdentityHeader(TextBody) for a detailed description of the composition of this blob.
        Map<IdentityField, String> k9identity = new HashMap<>();
        String[] identityHeaders = message.getHeader(K9.IDENTITY_HEADER);
        if (identityHeaders.length == 0) {
            identityHeaders = messageViewInfo.rootPart.getHeader(K9.IDENTITY_HEADER);
        }

        if (identityHeaders.length > 0 && identityHeaders[0] != null) {
            k9identity = IdentityHeaderParser.parse(identityHeaders[0]);
        }

        Identity newIdentity = new Identity();
        if (k9identity.containsKey(IdentityField.SIGNATURE)) {
            newIdentity = newIdentity
                    .withSignatureUse(true)
                    .withSignature(k9identity.get(IdentityField.SIGNATURE));
            signatureChanged = true;
        } else {
            if (message instanceof LocalMessage) {
                newIdentity = newIdentity.withSignatureUse(((LocalMessage) message).getFolder().getSignatureUse());
            }
            newIdentity = newIdentity.withSignature(identity.getSignature());
        }

        if (k9identity.containsKey(IdentityField.NAME)) {
            newIdentity = newIdentity.withName(k9identity.get(IdentityField.NAME));
            identityChanged = true;
        } else {
            newIdentity = newIdentity.withName(identity.getName());
        }

        if (k9identity.containsKey(IdentityField.EMAIL)) {
            newIdentity = newIdentity.withEmail(k9identity.get(IdentityField.EMAIL));
            identityChanged = true;
        } else {
            newIdentity = newIdentity.withEmail(identity.getEmail());
        }

        if (k9identity.containsKey(IdentityField.ORIGINAL_MESSAGE)) {
            relatedMessageReference = null;
            String originalMessage = k9identity.get(IdentityField.ORIGINAL_MESSAGE);
            MessageReference messageReference = MessageReference.parse(originalMessage);

            if (messageReference != null) {
                // Check if this is a valid account in our database
                Preferences prefs = Preferences.getPreferences(getApplicationContext());
                Account account = prefs.getAccount(messageReference.getAccountUuid());
                if (account != null) {
                    relatedMessageReference = messageReference;
                }
            }
        }

        identity = newIdentity;

        updateSignature();
        updateFrom();

        quotedMessagePresenter.processDraftMessage(messageViewInfo, k9identity);
    }

    static class SendMessageTask extends AsyncTask<Void, Void, Void> {
        final Context context;
        final Account account;
        final Contacts contacts;
        final Message message;
        final Long draftId;
        final String plaintextSubject;
        final MessageReference messageReference;

        SendMessageTask(Context context, Account account, Contacts contacts, Message message,
                Long draftId, String plaintextSubject, MessageReference messageReference) {
            this.context = context;
            this.account = account;
            this.contacts = contacts;
            this.message = message;
            this.draftId = draftId;
            this.plaintextSubject = plaintextSubject;
            this.messageReference = messageReference;
        }

        @Override
        protected Void doInBackground(Void... params) {
            try {
                contacts.markAsContacted(message.getRecipients(RecipientType.TO));
                contacts.markAsContacted(message.getRecipients(RecipientType.CC));
                contacts.markAsContacted(message.getRecipients(RecipientType.BCC));
                updateReferencedMessage();
            } catch (Exception e) {
                Timber.e(e, "Failed to mark contact as contacted.");
            }

            MessagingController.getInstance(context).sendMessage(account, message, plaintextSubject, null);
            if (draftId != null) {
                // TODO set draft id to invalid in MessageCompose!
                MessagingController.getInstance(context).deleteDraft(account, draftId);
            }

            return null;
        }

        /**
         * Set the flag on the referenced message(indicated we replied / forwarded the message)
         **/
        private void updateReferencedMessage() {
            if (messageReference != null && messageReference.getFlag() != null) {
                String accountUuid = messageReference.getAccountUuid();
                Account account = Preferences.getPreferences(context).getAccount(accountUuid);
                long folderId = messageReference.getFolderId();
                String sourceMessageUid = messageReference.getUid();
                Flag flag = messageReference.getFlag();

                Timber.d("Setting referenced message (%d, %s) flag to %s", folderId, sourceMessageUid, flag);

                MessagingController messagingController = MessagingController.getInstance(context);
                messagingController.setFlag(account, folderId, sourceMessageUid, flag, true);
            }
        }
    }

    /**
     * When we are launched with an intent that includes a mailto: URI, we can actually
     * gather quite a few of our message fields from it.
     *
     * @param mailTo
     *         The MailTo object we use to initialize message field
     */
    private void initializeFromMailto(MailTo mailTo) {
        recipientPresenter.initFromMailto(mailTo);

        String subject = mailTo.getSubject();
        if (subject != null && !subject.isEmpty()) {
            subjectView.setText(subject);
        }

        String body = mailTo.getBody();
        if (body != null && !body.isEmpty()) {
            messageContentView.setCharacters(body);
        }
    }

    private void setCurrentMessageFormat(SimpleMessageFormat format) {
        // This method will later be used to enable/disable the rich text editing mode.

        currentMessageFormat = format;
    }

    public void updateMessageFormat() {
        MessageFormat origMessageFormat = account.getMessageFormat();
        SimpleMessageFormat messageFormat;
        if (origMessageFormat == MessageFormat.TEXT) {
            // The user wants to send text/plain messages. We don't override that choice under
            // any circumstances.
            messageFormat = SimpleMessageFormat.TEXT;
        } else if (quotedMessagePresenter.isForcePlainText()
                && quotedMessagePresenter.includeQuotedText()) {
            // Right now we send a text/plain-only message when the quoted text was edited, no
            // matter what the user selected for the message format.
            messageFormat = SimpleMessageFormat.TEXT;
        } else if (recipientPresenter.isForceTextMessageFormat()) {
            // Right now we only support PGP inline which doesn't play well with HTML. So force
            // plain text in those cases.
            messageFormat = SimpleMessageFormat.TEXT;
        } else if (origMessageFormat == MessageFormat.AUTO) {
            if (action == Action.COMPOSE || quotedMessagePresenter.isQuotedTextText() ||
                    !quotedMessagePresenter.includeQuotedText()) {
                // If the message format is set to "AUTO" we use text/plain whenever possible. That
                // is, when composing new messages and replying to or forwarding text/plain
                // messages.
                messageFormat = SimpleMessageFormat.TEXT;
            } else {
                messageFormat = SimpleMessageFormat.HTML;
            }
        } else {
            // In all other cases use HTML
            messageFormat = SimpleMessageFormat.HTML;
        }

        setCurrentMessageFormat(messageFormat);
    }

    @Override
    public void onMessageBuildSuccess(MimeMessage message, boolean isDraft) {
        String plaintextSubject =
                (currentMessageBuilder instanceof PgpMessageBuilder) ? currentMessageBuilder.getSubject() : null;

        if (isDraft) {
            changesMadeSinceLastSave = false;
            currentMessageBuilder = null;

            if (action == Action.EDIT_DRAFT && relatedMessageReference != null) {
                message.setUid(relatedMessageReference.getUid());
            }

            new SaveMessageTask(getApplicationContext(), account, contacts, internalMessageHandler,
                    message, draftId, plaintextSubject, true).execute();
            if (finishAfterDraftSaved) {
                finishAndRemoveTask();
            } else {
                setProgressBarIndeterminateVisibility(false);
            }
        } else {
            currentMessageBuilder = null;
            new SendMessageTask(getApplicationContext(), account, contacts, message,
                    draftId != INVALID_DRAFT_ID ? draftId : null, plaintextSubject, relatedMessageReference).execute();
            finishAndRemoveTask();
        }
    }

    @Override
    public void onMessageBuildCancel() {
        currentMessageBuilder = null;
        setProgressBarIndeterminateVisibility(false);
    }

    @Override
    public void onMessageBuildException(MessagingException me) {
        Timber.e(me, "Error sending message");
        Toast.makeText(MessageCompose.this,
                getString(R.string.send_failed_reason, me.getLocalizedMessage()), Toast.LENGTH_LONG).show();
        currentMessageBuilder = null;
        setProgressBarIndeterminateVisibility(false);
    }

    @Override
    public void onMessageBuildReturnPendingIntent(PendingIntent pendingIntent, int requestCode) {
        requestCode |= REQUEST_MASK_MESSAGE_BUILDER;
        try {
            startIntentSenderForResult(pendingIntent.getIntentSender(), requestCode, null, 0, 0, 0);
        } catch (SendIntentException e) {
            Timber.e(e, "Error starting pending intent from builder!");
        }
    }

    public void launchUserInteractionPendingIntent(PendingIntent pendingIntent, int requestCode) {
        requestCode |= REQUEST_MASK_RECIPIENT_PRESENTER;
        try {
            startIntentSenderForResult(pendingIntent.getIntentSender(), requestCode, null, 0, 0, 0);
        } catch (SendIntentException e) {
            e.printStackTrace();
        }
    }

    public void loadLocalMessageForDisplay(MessageViewInfo messageViewInfo, Action action) {
        currentMessageViewInfo = messageViewInfo;

        // We check to see if we've previously processed the source message since this
        // could be called when switching from HTML to text replies. If that happens, we
        // only want to update the UI with quoted text (which picks the appropriate
        // part).
        if (relatedMessageProcessed) {
            try {
                quotedMessagePresenter.populateUIWithQuotedMessage(messageViewInfo, true, action);
            } catch (MessagingException e) {
                // Hm, if we couldn't populate the UI after source reprocessing, let's just delete it?
                quotedMessagePresenter.showOrHideQuotedText(QuotedTextMode.HIDE);
                Timber.e(e, "Could not re-process source message; deleting quoted text to be safe.");
            }
            updateMessageFormat();
        } else {
            processSourceMessage(messageViewInfo);
            relatedMessageProcessed = true;
        }
    }

    private MessageLoaderCallbacks messageLoaderCallbacks = new MessageLoaderCallbacks() {
        @Override
        public void onMessageDataLoadFinished(LocalMessage message) {
            // nothing to do here, we don't care about message headers
        }

        @Override
        public void onMessageDataLoadFailed() {
            internalMessageHandler.sendEmptyMessage(MSG_PROGRESS_OFF);
            Toast.makeText(MessageCompose.this, R.string.status_invalid_id_error, Toast.LENGTH_LONG).show();
        }

        @Override
        public void onMessageViewInfoLoadFinished(MessageViewInfo messageViewInfo) {
            internalMessageHandler.sendEmptyMessage(MSG_PROGRESS_OFF);
            loadLocalMessageForDisplay(messageViewInfo, action);
        }

        @Override
        public void onMessageViewInfoLoadFailed(MessageViewInfo messageViewInfo) {
            internalMessageHandler.sendEmptyMessage(MSG_PROGRESS_OFF);
            Toast.makeText(MessageCompose.this, R.string.status_invalid_id_error, Toast.LENGTH_LONG).show();
        }

        @Override
        public void setLoadingProgress(int current, int max) {
            // nvm - we don't have a progress bar
        }

        @Override
        public void startIntentSenderForMessageLoaderHelper(IntentSender si, int requestCode, Intent fillIntent,
                int flagsMask, int flagValues, int extraFlags) {
            try {
                requestCode |= REQUEST_MASK_LOADER_HELPER;
                startIntentSenderForResult(si, requestCode, fillIntent, flagsMask, flagValues, extraFlags);
            } catch (SendIntentException e) {
                Timber.e(e, "Irrecoverable error calling PendingIntent!");
            }
        }

        @Override
        public void onDownloadErrorMessageNotFound() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MessageCompose.this, R.string.status_invalid_id_error, Toast.LENGTH_LONG).show();
                }
            });
        }

        @Override
        public void onDownloadErrorNetworkError() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MessageCompose.this, R.string.status_network_error, Toast.LENGTH_LONG).show();
                }
            });
        }
    };

    private void initializeActionBar() {
        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayHomeAsUpEnabled(true);
    }

    // TODO We miss callbacks for this listener if they happens while we are paused!
    public MessagingListener messagingListener = new SimpleMessagingListener() {

        @Override
        public void messageUidChanged(Account account, long folderId, String oldUid, String newUid) {
            if (relatedMessageReference == null) {
                return;
            }

            String sourceAccountUuid = relatedMessageReference.getAccountUuid();
            long sourceFolderId = relatedMessageReference.getFolderId();
            String sourceMessageUid = relatedMessageReference.getUid();

            boolean changedMessageIsCurrent = account.getUuid().equals(sourceAccountUuid) &&
                    folderId == sourceFolderId &&
                    oldUid.equals(sourceMessageUid);

            if (changedMessageIsCurrent) {
                relatedMessageReference = relatedMessageReference.withModifiedUid(newUid);
            }
        }

    };

    AttachmentMvpView attachmentMvpView = new AttachmentMvpView() {
        private HashMap<Uri, View> attachmentViews = new HashMap<>();

        @Override
        public void showWaitingForAttachmentDialog(WaitingAction waitingAction) {
            String title;

            switch (waitingAction) {
                case SEND: {
                    title = getString(R.string.fetching_attachment_dialog_title_send);
                    break;
                }
                case SAVE: {
                    title = getString(R.string.fetching_attachment_dialog_title_save);
                    break;
                }
                default: {
                    return;
                }
            }

            ProgressDialogFragment fragment = ProgressDialogFragment.newInstance(title,
                    getString(R.string.fetching_attachment_dialog_message));
            fragment.show(getSupportFragmentManager(), FRAGMENT_WAITING_FOR_ATTACHMENT);
        }

        @Override
        public void dismissWaitingForAttachmentDialog() {
            ProgressDialogFragment fragment = (ProgressDialogFragment)
                    getSupportFragmentManager().findFragmentByTag(FRAGMENT_WAITING_FOR_ATTACHMENT);

            if (fragment != null) {
                fragment.dismiss();
            }
        }

        @Override
        @SuppressLint("InlinedApi")
        public void showPickAttachmentDialog(int requestCode) {
            requestCode |= REQUEST_MASK_ATTACHMENT_PRESENTER;

            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            isInSubActivity = true;

            startActivityForResult(Intent.createChooser(i, null), requestCode);
        }

        @Override
        public void addAttachmentView(final Attachment attachment) {
            attachmentsView.setVisibility(View.VISIBLE);

            View view = getLayoutInflater().inflate(R.layout.message_compose_attachment, attachmentsView, false);
            attachmentViews.put(attachment.uri, view);

            View deleteButton = view.findViewById(R.id.attachment_delete);
            deleteButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View view) {
                    attachmentPresenter.onClickRemoveAttachment(attachment.uri);
                }
            });

            updateAttachmentView(attachment);
            attachmentsView.addView(view);
        }

        @Override
        public void updateAttachmentView(Attachment attachment) {
            View view = attachmentViews.get(attachment.uri);
            if (view == null) {
                throw new IllegalArgumentException();
            }

            TextView nameView = view.findViewById(R.id.attachment_name);
            boolean hasMetadata = (attachment.state != Attachment.LoadingState.URI_ONLY);
            if (hasMetadata) {
                nameView.setText(attachment.name);
            } else {
                nameView.setText(R.string.loading_attachment);
            }

            if (attachment.size != null && attachment.size >= 0) {
                TextView sizeView = view.findViewById(R.id.attachment_size);
                sizeView.setText(sizeFormatter.formatSize(attachment.size));
            }

            View progressBar = view.findViewById(R.id.progressBar);
            boolean isLoadingComplete = (attachment.state == Attachment.LoadingState.COMPLETE);
            if (isLoadingComplete) {
                if (MimeUtility.isSupportedImageType(attachment.contentType)) {
                    ImageView attachmentTypeView = view.findViewById(R.id.attachment_type);
                    attachmentTypeView.setImageResource(R.drawable.ic_attachment_image);

                    ImageView preview = view.findViewById(R.id.attachment_preview);
                    preview.setVisibility(View.VISIBLE);
                    Glide.with(MessageCompose.this)
                            .load(new File(attachment.filename))
                            .centerCrop()
                            .diskCacheStrategy(DiskCacheStrategy.NONE)
                            .into(preview);
                }
                progressBar.setVisibility(View.GONE);
            } else {
                progressBar.setVisibility(View.VISIBLE);
            }
        }

        @Override
        public void removeAttachmentView(Attachment attachment) {
            View view = attachmentViews.get(attachment.uri);
            attachmentsView.removeView(view);
            attachmentViews.remove(attachment.uri);

            if (attachmentViews.isEmpty()) {
                attachmentsView.setVisibility(View.GONE);
            }
        }

        @Override
        public void performSendAfterChecks() {
            MessageCompose.this.performSendAfterChecks();
        }

        @Override
        public void performSaveAfterChecks() {
            MessageCompose.this.performSaveAfterChecks();
        }

        @Override
        public void showMissingAttachmentsPartialMessageWarning() {
            Toast.makeText(MessageCompose.this,
                    getString(R.string.message_compose_attachments_skipped_toast), Toast.LENGTH_LONG).show();
        }

        @Override
        public void showMissingAttachmentsPartialMessageForwardWarning() {
            Toast.makeText(MessageCompose.this,
                    getString(R.string.message_compose_attachments_forward_toast), Toast.LENGTH_LONG).show();
        }
    };

    private Handler internalMessageHandler = new Handler() {
        @Override
        public void handleMessage(android.os.Message msg) {
            switch (msg.what) {
                case MSG_PROGRESS_ON:
                    setProgressBarIndeterminateVisibility(true);
                    break;
                case MSG_PROGRESS_OFF:
                    setProgressBarIndeterminateVisibility(false);
                    break;
                case MSG_SAVED_DRAFT:
                    draftId = (Long) msg.obj;
                    Toast.makeText(
                            MessageCompose.this,
                            getString(R.string.message_saved_toast),
                            Toast.LENGTH_LONG).show();
                    break;
                case MSG_DISCARDED_DRAFT:
                    Toast.makeText(
                            MessageCompose.this,
                            getString(R.string.message_discarded_toast),
                            Toast.LENGTH_LONG).show();
                    break;
                default:
                    super.handleMessage(msg);
                    break;
            }
        }
    };

    public enum Action {
        COMPOSE(R.string.compose_title_compose),
        REPLY(R.string.compose_title_reply),
        REPLY_ALL(R.string.compose_title_reply_all),
        FORWARD(R.string.compose_title_forward),
        FORWARD_AS_ATTACHMENT(R.string.compose_title_forward_as_attachment),
        EDIT_DRAFT(R.string.compose_title_compose);

        private final int titleResource;

        Action(@StringRes int titleResource) {
            this.titleResource = titleResource;
        }

        @StringRes
        public int getTitleResource() {
            return titleResource;
        }
    }
}
