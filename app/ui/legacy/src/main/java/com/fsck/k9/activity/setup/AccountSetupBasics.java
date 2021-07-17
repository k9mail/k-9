package com.fsck.k9.activity.setup;


import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;

import com.fsck.k9.Account;
import com.fsck.k9.Core;
import com.fsck.k9.DI;
import com.fsck.k9.EmailAddressValidator;
import com.fsck.k9.Preferences;
import com.fsck.k9.account.AccountCreator;
import com.fsck.k9.mail.oauth.OAuth2Provider;
import com.fsck.k9.ui.base.K9Activity;
import com.fsck.k9.activity.setup.AccountSetupCheckSettings.CheckDirection;
import com.fsck.k9.autodiscovery.api.DiscoveredServerSettings;
import com.fsck.k9.autodiscovery.api.DiscoveryResults;
import com.fsck.k9.autodiscovery.api.DiscoveryTarget;
import com.fsck.k9.autodiscovery.providersxml.ProvidersXmlDiscovery;
import com.fsck.k9.helper.Utility;
import com.fsck.k9.mail.AuthType;
import com.fsck.k9.mail.ServerSettings;
import com.fsck.k9.mailstore.SpecialLocalFoldersCreator;
import com.fsck.k9.ui.R;
import com.fsck.k9.ui.ConnectionSettings;
import com.fsck.k9.view.ClientCertificateSpinner;
import com.fsck.k9.view.ClientCertificateSpinner.OnClientCertificateChangedListener;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import timber.log.Timber;

/**
 * Prompts the user for the email address and password.
 * Attempts to lookup default settings for the domain the user specified. If the
 * domain is known the settings are handed off to the AccountSetupCheckSettings
 * activity. If no settings are found the settings are handed off to the
 * AccountSetupAccountType activity.
 */
public class AccountSetupBasics extends K9Activity
    implements OnClickListener, TextWatcher, OnCheckedChangeListener, OnClientCertificateChangedListener {
    private final static String EXTRA_ACCOUNT = "com.fsck.k9.AccountSetupBasics.account";
    private final static String STATE_KEY_CHECKED_INCOMING = "com.fsck.k9.AccountSetupBasics.checkedIncoming";


    private final ProvidersXmlDiscovery providersXmlDiscovery = DI.get(ProvidersXmlDiscovery.class);
    private final AccountCreator accountCreator = DI.get(AccountCreator.class);
    private final SpecialLocalFoldersCreator localFoldersCreator = DI.get(SpecialLocalFoldersCreator.class);

    private TextInputEditText mEmailView;
    private TextInputEditText mPasswordView;
    private TextInputLayout mPasswordLayoutView;
    private CheckBox mClientCertificateCheckBox;
    private ClientCertificateSpinner mClientCertificateSpinner;
    private Button mNextButton;
    private Button mManualSetupButton;
    private View mAdvandedOptionsFoldable;
    private Account mAccount;
    private ViewGroup mAllowClientCertificateView;

    private EmailAddressValidator mEmailValidator = new EmailAddressValidator();
    private boolean mCheckedIncoming = false;

    public static void actionNewAccount(Context context) {
        Intent i = new Intent(context, AccountSetupBasics.class);
        context.startActivity(i);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setLayout(R.layout.account_setup_basics);
        setTitle(R.string.account_setup_basics_title);
        mEmailView = findViewById(R.id.account_email);
        mPasswordView = findViewById(R.id.account_password);
        mPasswordLayoutView = findViewById(R.id.account_password_input_layout);
        mClientCertificateCheckBox = findViewById(R.id.account_client_certificate);
        mClientCertificateSpinner = findViewById(R.id.account_client_certificate_spinner);
        mAllowClientCertificateView = findViewById(R.id.account_allow_client_certificate);

        mNextButton = findViewById(R.id.next);
        mManualSetupButton = findViewById(R.id.manual_setup);
        mAdvandedOptionsFoldable = findViewById(R.id.foldable_advanced_options);
        mNextButton.setOnClickListener(this);
        mManualSetupButton.setOnClickListener(this);
    }

    private void initializeViewListeners() {
        mEmailView.addTextChangedListener(this);
        mPasswordView.addTextChangedListener(this);
        mClientCertificateCheckBox.setOnCheckedChangeListener(this);
        mClientCertificateSpinner.setOnClientCertificateChangedListener(this);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mAccount != null) {
            outState.putString(EXTRA_ACCOUNT, mAccount.getUuid());
        }
        outState.putBoolean(STATE_KEY_CHECKED_INCOMING, mCheckedIncoming);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);

        if (savedInstanceState.containsKey(EXTRA_ACCOUNT)) {
            String accountUuid = savedInstanceState.getString(EXTRA_ACCOUNT);
            mAccount = Preferences.getPreferences(this).getAccount(accountUuid);
        }

        mCheckedIncoming = savedInstanceState.getBoolean(STATE_KEY_CHECKED_INCOMING);

        updateViewVisibility(mClientCertificateCheckBox.isChecked());
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        /*
         * We wait until now to initialize the listeners because we didn't want
         * the OnCheckedChangeListener active while the
         * mClientCertificateCheckBox state was being restored because it could
         * trigger the pop-up of a ClientCertificateSpinner.chooseCertificate()
         * dialog.
         */
        initializeViewListeners();
        validateFields();
    }

    public void afterTextChanged(Editable s) {
        validateFields();
    }

    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    @Override
    public void onClientCertificateChanged(String alias) {
        validateFields();
    }

    /**
     * Called when checking the client certificate CheckBox
     */
    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        updateViewVisibility(isChecked);
        validateFields();

        // Have the user select the client certificate if not already selected
        if ((isChecked) && (mClientCertificateSpinner.getAlias() == null)) {
            mClientCertificateSpinner.chooseCertificate();
        }
    }

    private void updateViewVisibility(boolean usingCertificates) {
        if (usingCertificates) {
            // show client certificate spinner
            mAllowClientCertificateView.setVisibility(View.VISIBLE);
        } else {
            // hide client certificate spinner
            mAllowClientCertificateView.setVisibility(View.GONE);
        }
    }

    private void validateFields() {
        boolean clientCertificateChecked = mClientCertificateCheckBox.isChecked();
        String clientCertificateAlias = mClientCertificateSpinner.getAlias();
        String email = mEmailView.getText().toString();

        boolean xoauth2 = false;

        if (email.contains("@")) {
            String[] split = email.split("@");
            if (split.length == 2) {
                String domain = split[1];
                xoauth2 = OAuth2Provider.Companion.isXOAuth2(domain);
            }
        }

        boolean valid = Utility.requiredFieldValid(mEmailView)
                && ((!clientCertificateChecked && (Utility.requiredFieldValid(mPasswordView) || xoauth2))
                        || (clientCertificateChecked && clientCertificateAlias != null))
                && mEmailValidator.isValidAddressOnly(email);

        mNextButton.setEnabled(valid);
        mNextButton.setFocusable(valid);
        mManualSetupButton.setEnabled(valid);

        mPasswordLayoutView.setVisibility(xoauth2 ? View.GONE : View.VISIBLE);
        mAdvandedOptionsFoldable.setVisibility(xoauth2 ? View.GONE : View.VISIBLE);
        mManualSetupButton.setVisibility(xoauth2 ? View.GONE : View.VISIBLE);

        /*
         * Dim the next button's icon to 50% if the button is disabled.
         * TODO this can probably be done with a stateful drawable. Check into it.
         * android:state_enabled
         */
        Utility.setCompoundDrawablesAlpha(mNextButton, mNextButton.isEnabled() ? 255 : 128);
    }

    private String getOwnerName() {
        String name = null;
        try {
            name = getDefaultAccountName();
        } catch (Exception e) {
            Timber.e(e, "Could not get default account name");
        }

        if (name == null) {
            name = "";
        }
        return name;
    }

    private String getDefaultAccountName() {
        String name = null;
        Account account = Preferences.getPreferences(this).getDefaultAccount();
        if (account != null) {
            name = account.getName();
        }
        return name;
    }

    private void finishAutoSetup(ConnectionSettings connectionSettings) {
        String email = mEmailView.getText().toString();
        String password = mPasswordView.getText().toString();

        if (mAccount == null) {
            mAccount = Preferences.getPreferences(this).newAccount();
            mAccount.setChipColor(accountCreator.pickColor());
        }

        mAccount.setName(getOwnerName());
        mAccount.setEmail(email);

        ServerSettings incomingServerSettings = connectionSettings.getIncoming().newPassword(password);
        mAccount.setIncomingServerSettings(incomingServerSettings);

        ServerSettings outgoingServerSettings = connectionSettings.getOutgoing().newPassword(password);
        mAccount.setOutgoingServerSettings(outgoingServerSettings);

        mAccount.setDeletePolicy(accountCreator.getDefaultDeletePolicy(incomingServerSettings.type));

        localFoldersCreator.createSpecialLocalFolders(mAccount);

        // Check incoming here.  Then check outgoing in onActivityResult()
        AccountSetupCheckSettings.actionCheckSettings(this, mAccount, CheckDirection.INCOMING);
    }

    private ConnectionSettings providersXmlDiscoveryDiscover(String email, DiscoveryTarget discoveryTarget) {
        DiscoveryResults discoveryResults = providersXmlDiscovery.discover(email, DiscoveryTarget.INCOMING_AND_OUTGOING);
        if (discoveryResults == null || (discoveryResults.getIncoming().size() < 1 || discoveryResults.getOutgoing().size() < 1)) {
            return null;
        }
        DiscoveredServerSettings incoming = discoveryResults.getIncoming().get(0);
        DiscoveredServerSettings outgoing = discoveryResults.getOutgoing().get(0);
        return new ConnectionSettings(new ServerSettings(
                incoming.getProtocol(),
                incoming.getHost(),
                incoming.getPort(),
                incoming.getSecurity(),
                incoming.getAuthType(),
                incoming.getUsername(),
                null,
                null
        ), new ServerSettings(
                outgoing.getProtocol(),
                outgoing.getHost(),
                outgoing.getPort(),
                outgoing.getSecurity(),
                outgoing.getAuthType(),
                outgoing.getUsername(),
                null,
                null
        ));
    }

    private void onNext() {
        if (mClientCertificateCheckBox.isChecked()) {

            // Auto-setup doesn't support client certificates.
            onManualSetup();
            return;
        }

        String email = mEmailView.getText().toString();

        ConnectionSettings connectionSettings = providersXmlDiscoveryDiscover(email, DiscoveryTarget.INCOMING_AND_OUTGOING);
        if (connectionSettings != null) {
            finishAutoSetup(connectionSettings);
        } else {
            // We don't have default settings for this account, start the manual setup process.
            onManualSetup();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != AccountSetupCheckSettings.ACTIVITY_REQUEST_CODE) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }

        if (resultCode == RESULT_OK) {
            if (!mCheckedIncoming) {
                //We've successfully checked incoming.  Now check outgoing.
                mCheckedIncoming = true;
                AccountSetupCheckSettings.actionCheckSettings(this, mAccount, CheckDirection.OUTGOING);
            } else {
                //We've successfully checked outgoing as well.
                mAccount.setDescription(mAccount.getEmail());
                Preferences.getPreferences(this).saveAccount(mAccount);
                Core.setServicesEnabled(this);
                AccountSetupNames.actionSetNames(this, mAccount);
            }
        }
    }

    private void onManualSetup() {
        String email = mEmailView.getText().toString();

        String password = null;
        String clientCertificateAlias = null;
        AuthType authenticationType;

        authenticationType = AuthType.PLAIN;
        password = mPasswordView.getText().toString();
        if (mClientCertificateCheckBox.isChecked()) {
            clientCertificateAlias = mClientCertificateSpinner.getAlias();
            if (mPasswordView.getText().toString().equals("")) {
                authenticationType = AuthType.EXTERNAL;
                password = null;
            }
        }

        if (mAccount == null) {
            mAccount = Preferences.getPreferences(this).newAccount();
            mAccount.setChipColor(accountCreator.pickColor());
        }
        mAccount.setName(getOwnerName());
        mAccount.setEmail(email);

        InitialAccountSettings initialAccountSettings = new InitialAccountSettings(authenticationType, email, password,
                clientCertificateAlias);

        AccountSetupAccountType.actionSelectAccountType(this, mAccount, false, initialAccountSettings);
    }

    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.next) {
            onNext();
        } else if (id == R.id.manual_setup) {
            onManualSetup();
        }
    }
}
