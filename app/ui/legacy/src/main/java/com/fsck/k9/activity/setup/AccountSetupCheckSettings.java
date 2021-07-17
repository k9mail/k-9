
package com.fsck.k9.activity.setup;


import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;

import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentTransaction;
import com.fsck.k9.Account;
import com.fsck.k9.DI;
import com.fsck.k9.LocalKeyStoreManager;
import com.fsck.k9.Preferences;
import com.fsck.k9.mail.oauth.authorizationserver.codegrantflow.OAuth2NeedUserPromptException;
import com.fsck.k9.mail.oauth.authorizationserver.codegrantflow.OAuth2PromptRequestHandler;
import com.fsck.k9.mail.oauth.authorizationserver.codegrantflow.OAuth2CodeGrantFlowManager;
import com.fsck.k9.controller.MessagingController;
import com.fsck.k9.fragment.ConfirmationDialogFragment;
import com.fsck.k9.fragment.ConfirmationDialogFragment.ConfirmationDialogFragmentListener;
import com.fsck.k9.mail.AuthenticationFailedException;
import com.fsck.k9.mail.CertificateValidationException;
import com.fsck.k9.mail.MailServerDirection;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.filter.Hex;
import com.fsck.k9.preferences.Protocols;
import com.fsck.k9.ui.R;
import com.fsck.k9.ui.base.K9Activity;
import timber.log.Timber;


/**
 * Checks the given settings to make sure that they can be used to send and
 * receive mail.
 *
 * XXX NOTE: The manifest for this app has it ignore config changes, because
 * it doesn't correctly deal with restarting while its thread is running.
 */
public class AccountSetupCheckSettings extends K9Activity implements OnClickListener,
        ConfirmationDialogFragmentListener{

    public static final int ACTIVITY_REQUEST_CODE = 1;

    private static final String EXTRA_ACCOUNT = "account";

    private static final String EXTRA_CHECK_DIRECTION ="checkDirection";

    public enum CheckDirection {
        INCOMING,
        OUTGOING;

        public MailServerDirection toMailServerDirection() {
            switch (this) {
                case INCOMING: return MailServerDirection.INCOMING;
                case OUTGOING: return MailServerDirection.OUTGOING;
            }

            throw new AssertionError("Unknown value: " + this);
        }
    }

    private final MessagingController messagingController = DI.get(MessagingController.class);

    private Handler mHandler = new Handler();

    private ProgressBar mProgressBar;

    private TextView mMessageView;

    private Account mAccount;

    private CheckDirection mDirection;

    private boolean mCanceled;

    private boolean mDestroyed;

    private Dialog authDialog;

    private OAuth2CodeGrantFlowManager oAuth2CodeGrantFlowManager;

    public static void actionCheckSettings(Activity context, Account account,
            CheckDirection direction) {
        Intent i = new Intent(context, AccountSetupCheckSettings.class);
        i.putExtra(EXTRA_ACCOUNT, account.getUuid());
        i.putExtra(EXTRA_CHECK_DIRECTION, direction);
        context.startActivityForResult(i, ACTIVITY_REQUEST_CODE);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setLayout(R.layout.account_setup_check_settings);
        mMessageView = findViewById(R.id.message);
        mProgressBar = findViewById(R.id.progress);
        findViewById(R.id.cancel).setOnClickListener(this);

        setMessage(R.string.account_setup_check_settings_retr_info_msg);
        mProgressBar.setIndeterminate(true);

        String accountUuid = getIntent().getStringExtra(EXTRA_ACCOUNT);
        mAccount = Preferences.getPreferences(this).getAccount(accountUuid);
        mDirection = (CheckDirection) getIntent().getSerializableExtra(EXTRA_CHECK_DIRECTION);

        oAuth2CodeGrantFlowManager = DI.get(OAuth2CodeGrantFlowManager.class);
        oAuth2CodeGrantFlowManager.setPromptRequestHandler(promptRequestHandler);

        new CheckAccountTask(mAccount).execute(mDirection);
    }

    private void handleCertificateValidationException(CertificateValidationException cve) {
        Timber.e(cve, "Error while testing settings");

        X509Certificate[] chain = cve.getCertChain();
        // Avoid NullPointerException in acceptKeyDialog()
        if (chain != null) {
            acceptKeyDialog(
                    R.string.account_setup_failed_dlg_certificate_message_fmt,
                    cve);
        } else {
            showErrorDialog(
                    R.string.account_setup_failed_dlg_server_message_fmt,
                    errorMessageForCertificateException(cve));
        }
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        oAuth2CodeGrantFlowManager.setPromptRequestHandler(null);
        mDestroyed = true;
        mCanceled = true;
    }

    private void setMessage(final int resId) {
        mMessageView.setText(getString(resId));
    }

    private void acceptKeyDialog(final int msgResId, final CertificateValidationException ex) {
        mHandler.post(new Runnable() {
            public void run() {
                if (mDestroyed) {
                    return;
                }
                String exMessage = "Unknown Error";

                if (ex != null) {
                    if (ex.getCause() != null) {
                        if (ex.getCause().getCause() != null) {
                            exMessage = ex.getCause().getCause().getMessage();

                        } else {
                            exMessage = ex.getCause().getMessage();
                        }
                    } else {
                        exMessage = ex.getMessage();
                    }
                }

                mProgressBar.setIndeterminate(false);
                StringBuilder chainInfo = new StringBuilder(200);
                final X509Certificate[] chain = ex.getCertChain();
                // We already know chain != null (tested before calling this method)
                for (int i = 0; i < chain.length; i++) {
                    // display certificate chain information
                    //TODO: localize this strings
                    chainInfo.append("Certificate chain[").append(i).append("]:\n");
                    chainInfo.append("Subject: ").append(chain[i].getSubjectDN().toString()).append("\n");

                    // display SubjectAltNames too
                    // (the user may be mislead into mistrusting a certificate
                    //  by a subjectDN not matching the server even though a
                    //  SubjectAltName matches)
                    try {
                        final Collection < List<? >> subjectAlternativeNames = chain[i].getSubjectAlternativeNames();
                        if (subjectAlternativeNames != null) {
                            // The list of SubjectAltNames may be very long
                            //TODO: localize this string
                            StringBuilder altNamesText = new StringBuilder();
                            altNamesText.append("Subject has ").append(subjectAlternativeNames.size()).append(" alternative names\n");

                            // we need these for matching
                            String incomingServerHost = mAccount.getIncomingServerSettings().host;
                            String outgoingServerHost = mAccount.getOutgoingServerSettings().host;

                            for (List<?> subjectAlternativeName : subjectAlternativeNames) {
                                Integer type = (Integer)subjectAlternativeName.get(0);
                                Object value = subjectAlternativeName.get(1);
                                String name;
                                switch (type) {
                                case 0:
                                    Timber.w("SubjectAltName of type OtherName not supported.");
                                    continue;
                                case 1: // RFC822Name
                                    name = (String)value;
                                    break;
                                case 2:  // DNSName
                                    name = (String)value;
                                    break;
                                case 3:
                                    Timber.w("unsupported SubjectAltName of type x400Address");
                                    continue;
                                case 4:
                                    Timber.w("unsupported SubjectAltName of type directoryName");
                                    continue;
                                case 5:
                                    Timber.w("unsupported SubjectAltName of type ediPartyName");
                                    continue;
                                case 6:  // Uri
                                    name = (String)value;
                                    break;
                                case 7: // ip-address
                                    name = (String)value;
                                    break;
                                default:
                                    Timber.w("unsupported SubjectAltName of unknown type");
                                    continue;
                                }

                                // if some of the SubjectAltNames match the store or transport -host,
                                // display them
                                if (name.equalsIgnoreCase(incomingServerHost) || name.equalsIgnoreCase(outgoingServerHost)) {
                                    //TODO: localize this string
                                    altNamesText.append("Subject(alt): ").append(name).append(",...\n");
                                } else if (name.startsWith("*.") && (
                                            incomingServerHost.endsWith(name.substring(2)) ||
                                            outgoingServerHost.endsWith(name.substring(2)))) {
                                    //TODO: localize this string
                                    altNamesText.append("Subject(alt): ").append(name).append(",...\n");
                                }
                            }
                            chainInfo.append(altNamesText);
                        }
                    } catch (Exception e1) {
                        // don't fail just because of subjectAltNames
                        Timber.w(e1, "cannot display SubjectAltNames in dialog");
                    }

                    chainInfo.append("Issuer: ").append(chain[i].getIssuerDN().toString()).append("\n");
                    String[] digestAlgorithms = new String[] {"SHA-1", "SHA-256", "SHA-512"};

                    for (String algorithm : digestAlgorithms) {
                        MessageDigest digest = null;
                        try {
                            digest = MessageDigest.getInstance(algorithm);
                        } catch (NoSuchAlgorithmException e) {
                            Timber.e(e, "Error while initializing MessageDigest (" + algorithm + ")");
                        }

                        if (digest != null) {
                            digest.reset();
                            try {
                                String hash = Hex.encodeHex(digest.digest(chain[i].getEncoded()));
                                chainInfo.append("Fingerprint ("+ algorithm +"): ").append("\n").append(hash).append("\n");
                            } catch (CertificateEncodingException e) {
                                Timber.e(e, "Error while encoding certificate");
                            }
                        }
                    }
                }

                // TODO: refactor with DialogFragment.
                // This is difficult because we need to pass through chain[0] for onClick()
                new AlertDialog.Builder(AccountSetupCheckSettings.this)
                .setTitle(getString(R.string.account_setup_failed_dlg_invalid_certificate_title))
                //.setMessage(getString(R.string.account_setup_failed_dlg_invalid_certificate)
                .setMessage(getString(msgResId, exMessage)
                            + " " + chainInfo.toString()
                           )
                .setCancelable(true)
                .setPositiveButton(
                    getString(R.string.account_setup_failed_dlg_invalid_certificate_accept),
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        acceptCertificate(chain[0]);
                    }
                })
                .setNegativeButton(
                    getString(R.string.account_setup_failed_dlg_invalid_certificate_reject),
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .show();
            }
        });
    }

    /**
     * Permanently accepts a certificate for the INCOMING or OUTGOING direction
     * by adding it to the local key store.
     * 
     * @param certificate
     */
    private void acceptCertificate(X509Certificate certificate) {
        try {
            DI.get(LocalKeyStoreManager.class).addCertificate(mAccount, mDirection.toMailServerDirection(), certificate);
        } catch (CertificateException e) {
            showErrorDialog(
                    R.string.account_setup_failed_dlg_certificate_message_fmt,
                    e.getMessage() == null ? "" : e.getMessage());
        }
        AccountSetupCheckSettings.actionCheckSettings(AccountSetupCheckSettings.this, mAccount,
                mDirection);
    }

    @Override
    public void onActivityResult(int reqCode, int resCode, Intent data) {
        if (reqCode == ACTIVITY_REQUEST_CODE) {
            setResult(resCode);
            finish();
        } else {
            super.onActivityResult(reqCode, resCode, data);
        }
    }

    private void onCancel() {
        mCanceled = true;
        setMessage(R.string.account_setup_check_settings_canceling_msg);
        setResult(RESULT_CANCELED);
        finish();
    }

    public void onClick(View v) {
        if (v.getId() == R.id.cancel) {
            onCancel();
        }
    }

    private void showErrorDialog(final int msgResId, final Object... args) {
        mHandler.post(new Runnable() {
            public void run() {
                showDialogFragment(R.id.dialog_account_setup_error, getString(msgResId, args));
            }
        });
    }

    private void showDialogFragment(int dialogId, String customMessage) {
        if (mDestroyed) {
            return;
        }
        mProgressBar.setIndeterminate(false);

        DialogFragment fragment;
        if (dialogId == R.id.dialog_account_setup_error) {
            fragment = ConfirmationDialogFragment.newInstance(dialogId,
                    getString(R.string.account_setup_failed_dlg_title),
                    customMessage,
                    getString(R.string.account_setup_failed_dlg_edit_details_action),
                    getString(R.string.account_setup_failed_dlg_continue_action)
            );
        } else {
            throw new RuntimeException("Called showDialog(int) with unknown dialog id.");
        }

        FragmentTransaction ta = getSupportFragmentManager().beginTransaction();
        ta.add(fragment, getDialogTag(dialogId));
        ta.commitAllowingStateLoss();

        // TODO: commitAllowingStateLoss() is used to prevent https://code.google.com/p/android/issues/detail?id=23761
        // but is a bad...
        //fragment.show(ta, getDialogTag(dialogId));
    }

    private String getDialogTag(int dialogId) {
        return String.format(Locale.US, "dialog-%d", dialogId);
    }

    @Override
    public void doPositiveClick(int dialogId) {
        if (dialogId == R.id.dialog_account_setup_error) {
            finish();
        }
    }

    @Override
    public void doNegativeClick(int dialogId) {
        if (dialogId == R.id.dialog_account_setup_error) {
            mCanceled = false;
            setResult(RESULT_OK);
            finish();
        }
    }

    @Override
    public void dialogCancelled(int dialogId) {
        // nothing to do here...
    }

    private String errorMessageForCertificateException(CertificateValidationException e) {
        switch (e.getReason()) {
            case Expired: return getString(R.string.client_certificate_expired, e.getAlias(), e.getMessage());
            case MissingCapability: return getString(R.string.auth_external_error);
            case RetrievalFailure: return getString(R.string.client_certificate_retrieval_failure, e.getAlias());
            case UseMessage: return e.getMessage();
            case Unknown:
            default: return "";
        }
    }

    /**
     * FIXME: Don't use an AsyncTask to perform network operations.
     * See also discussion in https://github.com/k9mail/k-9/pull/560
     */
    private class CheckAccountTask extends AsyncTask<CheckDirection, Integer, Void> {
        private final Account account;

        private CheckAccountTask(Account account) {
            this.account = account;
        }

        @Override
        protected Void doInBackground(CheckDirection... params) {
            final CheckDirection direction = params[0];
            try {
                /*
                 * This task could be interrupted at any point, but network operations can block,
                 * so relying on InterruptedException is not enough. Instead, check after
                 * each potentially long-running operation.
                 */
                if (cancelled()) {
                    return null;
                }

                try {
                    clearCertificateErrorNotifications(direction);

                    checkServerSettings(direction);

                    if (cancelled()) {
                        return null;
                    }

                    setResult(RESULT_OK);
                    finish();
                } catch (OAuth2NeedUserPromptException ignored) {
                    //let the user do oauth2 flow procedure through webview
                }

            } catch (AuthenticationFailedException afe) {
                if (afe.getMessage().equals(AuthenticationFailedException.OAUTH2_ERROR_INVALID_REFRESH_TOKEN)) {
                    //Do it it in another way
                    oAuth2CodeGrantFlowManager.invalidateRefreshToken(mAccount.getEmail());
                    runOnUiThread(() -> new CheckAccountTask(mAccount).execute(mDirection));
                } else {
                    Timber.e(afe, "Error while testing settings");
                    showErrorDialog(
                            R.string.account_setup_failed_dlg_auth_message_fmt,
                            afe.getMessage() == null ? "" : afe.getMessage());
                }
            } catch (CertificateValidationException cve) {
                handleCertificateValidationException(cve);
            } catch (Exception e) {
                Timber.e(e, "Error while testing settings");
                String message = e.getMessage() == null ? "" : e.getMessage();
                showErrorDialog(R.string.account_setup_failed_dlg_server_message_fmt, message);
            }
            return null;
        }

        private void clearCertificateErrorNotifications(CheckDirection direction) {
            final MessagingController ctrl = MessagingController.getInstance(getApplication());
            boolean incoming = (direction == CheckDirection.INCOMING);
            ctrl.clearCertificateErrorNotifications(account, incoming);
        }

        private boolean cancelled() {
            if (mDestroyed) {
                return true;
            }
            if (mCanceled) {
                finish();
                return true;
            }
            return false;
        }

        private void checkServerSettings(CheckDirection direction) throws MessagingException {
            switch (direction) {
                case INCOMING: {
                    checkIncoming();
                    break;
                }
                case OUTGOING: {
                    checkOutgoing();
                    break;
                }
            }
        }

        private void checkOutgoing() throws MessagingException {
            if (!isWebDavAccount()) {
                publishProgress(R.string.account_setup_check_settings_check_outgoing_msg);
            }

            messagingController.checkOutgoingServerSettings(account);
        }

        private void checkIncoming() throws MessagingException {
            if (isWebDavAccount()) {
                publishProgress(R.string.account_setup_check_settings_authenticate);
            } else {
                publishProgress(R.string.account_setup_check_settings_check_incoming_msg);
            }

            messagingController.checkIncomingServerSettings(account);

            if (isWebDavAccount()) {
                publishProgress(R.string.account_setup_check_settings_fetch);
            }

            messagingController.refreshFolderListSynchronous(account);
            Long inboxFolderId = account.getInboxFolderId();
            if (inboxFolderId != null) {
                messagingController.synchronizeMailbox(account, inboxFolderId, null);
            }
        }

        private boolean isWebDavAccount() {
            return account.getIncomingServerSettings().type.equals(Protocols.WEBDAV);
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            setMessage(values[0]);
        }
    }

    private final OAuth2PromptRequestHandler promptRequestHandler = new OAuth2PromptRequestHandler() {

        @Override
        public void handleRedirectUrl(WebViewClient webViewClient, String url) {
            openUrl(webViewClient, url);
        }

        @Override
        public void onObtainCodeSuccessful() {
            if (authDialog != null) {
                authDialog.dismiss();
                authDialog = null;
            }
        }
        @Override
        public void onObtainAccessTokenSuccessful() {
            //restart a settings check
            new CheckAccountTask(mAccount).execute(mDirection);
        }

        @Override
        public void onError(String errorMessage) {
            Toast.makeText(AccountSetupCheckSettings.this, errorMessage, Toast.LENGTH_LONG).show();
            finish();
        }
    };

    private void openUrl(WebViewClient webViewClient, String url) {
        runOnUiThread(() -> {
            CookieManager cookieManager = CookieManager.getInstance();
            //noinspection deprecation
            cookieManager.removeAllCookie();

            authDialog = new Dialog(this);
            authDialog.setContentView(R.layout.oauth_webview);
            WebView web = authDialog.findViewById(R.id.web_view);
            web.getSettings().setSaveFormData(false);
            web.getSettings().setJavaScriptEnabled(true);
            web.getSettings().setUserAgentString("K9 mail");

            web.setWebViewClient(webViewClient);

            web.getSettings().setUseWideViewPort(true);

            authDialog.setCancelable(false);
            authDialog.show();

            authDialog.setOnKeyListener((arg0, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    if (web.canGoBack()) {
                        web.goBack();
                    } else {
                        onCancel();
                    }
                    return true;
                }
                return false;
            });

            web.loadUrl(url);
        });
    }
}
