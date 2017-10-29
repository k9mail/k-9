
package com.fsck.k9.activity.setup;


import java.net.URI;
import java.net.URISyntaxException;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.DigitsKeyListener;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.fsck.k9.Account;
import com.fsck.k9.Preferences;
import com.fsck.k9.R;
import com.fsck.k9.account.AccountCreator;
import com.fsck.k9.activity.K9Activity;
import com.fsck.k9.activity.setup.AccountSetupCheckSettings.CheckDirection;
import com.fsck.k9.helper.Utility;
import com.fsck.k9.mail.AuthType;
import com.fsck.k9.mail.ConnectionSecurity;
import com.fsck.k9.mail.ServerSettings;
import com.fsck.k9.mail.ServerSettings.Type;
import com.fsck.k9.mail.Transport;
import com.fsck.k9.mail.TransportUris;
import com.fsck.k9.view.ClientCertificateSpinner;
import com.fsck.k9.view.ClientCertificateSpinner.OnClientCertificateChangedListener;
import timber.log.Timber;

public class AccountSetupOutgoing extends K9Activity implements OnClickListener,
    OnCheckedChangeListener {
    private static final String EXTRA_ACCOUNT = "account";

    private static final String EXTRA_MAKE_DEFAULT = "makeDefault";
    private static final String STATE_SECURITY_TYPE_POSITION = "stateSecurityTypePosition";
    private static final String STATE_AUTH_TYPE_POSITION = "authTypePosition";

    private EditText usernameView;
    private EditText passwordView;
    private ClientCertificateSpinner clientCertificateSpinner;
    private TextView clientCertificateLabelView;
    private TextView passwordLabelView;
    private EditText serverView;
    private EditText portView;
    private String currentPortViewSetting;
    private CheckBox requireLoginView;
    private ViewGroup requireLoginSettingsView;
    private Spinner securityTypeView;
    private int currentSecurityTypeViewPosition;
    private Spinner authTypeView;
    private int currentAuthTypeViewPosition;
    private AuthTypeAdapter authTypeAdapter;
    private Button nextButton;
    private Account account;
    private boolean makeDefault;

    public static void actionOutgoingSettings(Context context, Account account, boolean makeDefault) {
        Intent i = new Intent(context, AccountSetupOutgoing.class);
        i.putExtra(EXTRA_ACCOUNT, account.getUuid());
        i.putExtra(EXTRA_MAKE_DEFAULT, makeDefault);
        context.startActivity(i);
    }

    public static void actionEditOutgoingSettings(Context context, Account account) {
        context.startActivity(intentActionEditOutgoingSettings(context, account));
    }

    public static Intent intentActionEditOutgoingSettings(Context context, Account account) {
        Intent i = new Intent(context, AccountSetupOutgoing.class);
        i.setAction(Intent.ACTION_EDIT);
        i.putExtra(EXTRA_ACCOUNT, account.getUuid());
        return i;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.account_setup_outgoing);

        String accountUuid = getIntent().getStringExtra(EXTRA_ACCOUNT);
        account = Preferences.getPreferences(this).getAccount(accountUuid);

        try {
            if (new URI(account.getStoreUri()).getScheme().startsWith("webdav")) {
                account.setTransportUri(account.getStoreUri());
                AccountSetupCheckSettings.actionCheckSettings(this, account, CheckDirection.OUTGOING);
            }
        } catch (URISyntaxException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }


        usernameView = (EditText)findViewById(R.id.account_username);
        passwordView = (EditText)findViewById(R.id.account_password);
        clientCertificateSpinner = (ClientCertificateSpinner)findViewById(R.id.account_client_certificate_spinner);
        clientCertificateLabelView = (TextView)findViewById(R.id.account_client_certificate_label);
        passwordLabelView = (TextView)findViewById(R.id.account_password_label);
        serverView = (EditText)findViewById(R.id.account_server);
        portView = (EditText)findViewById(R.id.account_port);
        requireLoginView = (CheckBox)findViewById(R.id.account_require_login);
        requireLoginSettingsView = (ViewGroup)findViewById(R.id.account_require_login_settings);
        securityTypeView = (Spinner)findViewById(R.id.account_security_type);
        authTypeView = (Spinner)findViewById(R.id.account_auth_type);
        nextButton = (Button)findViewById(R.id.next);

        nextButton.setOnClickListener(this);

        securityTypeView.setAdapter(ConnectionSecurityAdapter.get(this));

        authTypeAdapter = AuthTypeAdapter.get(this);
        authTypeView.setAdapter(authTypeAdapter);

        /*
         * Only allow digits in the port field.
         */
        portView.setKeyListener(DigitsKeyListener.getInstance("0123456789"));

        //FIXME: get Account object again?
        accountUuid = getIntent().getStringExtra(EXTRA_ACCOUNT);
        account = Preferences.getPreferences(this).getAccount(accountUuid);
        makeDefault = getIntent().getBooleanExtra(EXTRA_MAKE_DEFAULT, false);

        /*
         * If we're being reloaded we override the original account with the one
         * we saved
         */
        if (savedInstanceState != null && savedInstanceState.containsKey(EXTRA_ACCOUNT)) {
            accountUuid = savedInstanceState.getString(EXTRA_ACCOUNT);
            account = Preferences.getPreferences(this).getAccount(accountUuid);
        }

        try {
            ServerSettings settings = TransportUris.decodeTransportUri(account.getTransportUri());

            updateAuthPlainTextFromSecurityType(settings.connectionSecurity);

            if (savedInstanceState == null) {
                // The first item is selected if settings.authenticationType is null or is not in authTypeAdapter
                currentAuthTypeViewPosition = authTypeAdapter.getAuthPosition(settings.authenticationType);
            } else {
                currentAuthTypeViewPosition = savedInstanceState.getInt(STATE_AUTH_TYPE_POSITION);
            }
            authTypeView.setSelection(currentAuthTypeViewPosition, false);
            updateViewFromAuthType();

            // Select currently configured security type
            if (savedInstanceState == null) {
                currentSecurityTypeViewPosition = settings.connectionSecurity.ordinal();
            } else {

                /*
                 * Restore the spinner state now, before calling
                 * setOnItemSelectedListener(), thus avoiding a call to
                 * onItemSelected(). Then, when the system restores the state
                 * (again) in onRestoreInstanceState(), The system will see that
                 * the new state is the same as the current state (set here), so
                 * once again onItemSelected() will not be called.
                 */
                currentSecurityTypeViewPosition = savedInstanceState.getInt(STATE_SECURITY_TYPE_POSITION);
            }
            securityTypeView.setSelection(currentSecurityTypeViewPosition, false);

            if (settings.username != null && !settings.username.isEmpty()) {
                usernameView.setText(settings.username);
                requireLoginView.setChecked(true);
                requireLoginSettingsView.setVisibility(View.VISIBLE);
            }

            if (settings.password != null) {
                passwordView.setText(settings.password);
            }

            if (settings.clientCertificateAlias != null) {
                clientCertificateSpinner.setAlias(settings.clientCertificateAlias);
            }

            if (settings.host != null) {
                serverView.setText(settings.host);
            }

            if (settings.port != -1) {
                portView.setText(String.format("%d", settings.port));
            } else {
                updatePortFromSecurityType();
            }
            currentPortViewSetting = portView.getText().toString();
        } catch (Exception e) {
            /*
             * We should always be able to parse our own settings.
             */
            failure(e);
        }

    }

    /**
     * Called at the end of either {@code onCreate()} or
     * {@code onRestoreInstanceState()}, after the views have been initialized,
     * so that the listeners are not triggered during the view initialization.
     * This avoids needless calls to {@code validateFields()} which is called
     * immediately after this is called.
     */
    private void initializeViewListeners() {

        /*
         * Updates the port when the user changes the security type. This allows
         * us to show a reasonable default which the user can change.
         */
        securityTypeView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position,
                    long id) {

                /*
                 * We keep our own record of the spinner state so we
                 * know for sure that onItemSelected() was called
                 * because of user input, not because of spinner
                 * state initialization. This assures that the port
                 * will not be replaced with a default value except
                 * on user input.
                 */
                if (currentSecurityTypeViewPosition != position) {
                    updatePortFromSecurityType();

                    boolean isInsecure = (ConnectionSecurity.NONE == getSelectedSecurity());
                    boolean isAuthExternal = (AuthType.EXTERNAL == getSelectedAuthType());
                    boolean loginNotRequired = !requireLoginView.isChecked();

                    /*
                     * If the user selects ConnectionSecurity.NONE, a
                     * warning would normally pop up if the authentication
                     * is AuthType.EXTERNAL (i.e., using client
                     * certificates). But such a warning is irrelevant if
                     * login is not required. So to avoid such a warning
                     * (generated in validateFields()) under those
                     * conditions, we change the (irrelevant) authentication
                     * method to PLAIN.
                     */
                    if (isInsecure && isAuthExternal && loginNotRequired) {
                        OnItemSelectedListener onItemSelectedListener = authTypeView.getOnItemSelectedListener();
                        authTypeView.setOnItemSelectedListener(null);
                        currentAuthTypeViewPosition = authTypeAdapter.getAuthPosition(AuthType.PLAIN);
                        authTypeView.setSelection(currentAuthTypeViewPosition, false);
                        authTypeView.setOnItemSelectedListener(onItemSelectedListener);
                        updateViewFromAuthType();
                    }

                    validateFields();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { /* unused */ }
        });

        authTypeView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position,
                    long id) {
                if (currentAuthTypeViewPosition == position) {
                    return;
                }

                updateViewFromAuthType();
                validateFields();
                AuthType selection = getSelectedAuthType();

                // Have the user select (or confirm) the client certificate
                if (AuthType.EXTERNAL == selection) {

                    // This may again invoke validateFields()
                    clientCertificateSpinner.chooseCertificate();
                } else {
                    passwordView.requestFocus();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { /* unused */ }
        });

        requireLoginView.setOnCheckedChangeListener(this);
        clientCertificateSpinner.setOnClientCertificateChangedListener(clientCertificateChangedListener);
        usernameView.addTextChangedListener(validationTextWatcher);
        passwordView.addTextChangedListener(validationTextWatcher);
        serverView.addTextChangedListener(validationTextWatcher);
        portView.addTextChangedListener(validationTextWatcher);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(EXTRA_ACCOUNT, account.getUuid());
        outState.putInt(STATE_SECURITY_TYPE_POSITION, currentSecurityTypeViewPosition);
        outState.putInt(STATE_AUTH_TYPE_POSITION, currentAuthTypeViewPosition);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);

        if (requireLoginView.isChecked()) {
            requireLoginSettingsView.setVisibility(View.VISIBLE);
        } else {
            requireLoginSettingsView.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        /*
         * We didn't want the listeners active while the state was being restored
         * because they could overwrite the restored port with a default port when
         * the security type was restored.
         */
        initializeViewListeners();
        validateFields();
    }

    /**
     * Shows/hides password field and client certificate spinner
     */
    private void updateViewFromAuthType() {
        AuthType authType = getSelectedAuthType();
        boolean isAuthTypeExternal = (AuthType.EXTERNAL == authType);

        if (isAuthTypeExternal) {

            // hide password fields, show client certificate fields
            passwordView.setVisibility(View.GONE);
            passwordLabelView.setVisibility(View.GONE);
            clientCertificateLabelView.setVisibility(View.VISIBLE);
            clientCertificateSpinner.setVisibility(View.VISIBLE);
        } else {

            // show password fields, hide client certificate fields
            passwordView.setVisibility(View.VISIBLE);
            passwordLabelView.setVisibility(View.VISIBLE);
            clientCertificateLabelView.setVisibility(View.GONE);
            clientCertificateSpinner.setVisibility(View.GONE);
        }
    }

    /**
     * This is invoked only when the user makes changes to a widget, not when
     * widgets are changed programmatically.  (The logic is simpler when you know
     * that this is the last thing called after an input change.)
     */
    private void validateFields() {
        AuthType authType = getSelectedAuthType();
        boolean isAuthTypeExternal = (AuthType.EXTERNAL == authType);

        ConnectionSecurity connectionSecurity = getSelectedSecurity();
        boolean hasConnectionSecurity = (connectionSecurity != ConnectionSecurity.NONE);

        if (isAuthTypeExternal && !hasConnectionSecurity) {

            // Notify user of an invalid combination of AuthType.EXTERNAL & ConnectionSecurity.NONE
            String toastText = getString(R.string.account_setup_outgoing_invalid_setting_combo_notice,
                    getString(R.string.account_setup_incoming_auth_type_label),
                    AuthType.EXTERNAL.toString(),
                    getString(R.string.account_setup_incoming_security_label),
                    ConnectionSecurity.NONE.toString());
            Toast.makeText(this, toastText, Toast.LENGTH_LONG).show();

            // Reset the views back to their previous settings without recursing through here again
            OnItemSelectedListener onItemSelectedListener = authTypeView.getOnItemSelectedListener();
            authTypeView.setOnItemSelectedListener(null);
            authTypeView.setSelection(currentAuthTypeViewPosition, false);
            authTypeView.setOnItemSelectedListener(onItemSelectedListener);
            updateViewFromAuthType();

            onItemSelectedListener = securityTypeView.getOnItemSelectedListener();
            securityTypeView.setOnItemSelectedListener(null);
            securityTypeView.setSelection(currentSecurityTypeViewPosition, false);
            securityTypeView.setOnItemSelectedListener(onItemSelectedListener);
            updateAuthPlainTextFromSecurityType(getSelectedSecurity());

            portView.removeTextChangedListener(validationTextWatcher);
            portView.setText(currentPortViewSetting);
            portView.addTextChangedListener(validationTextWatcher);

            authType = getSelectedAuthType();
            isAuthTypeExternal = (AuthType.EXTERNAL == authType);

            connectionSecurity = getSelectedSecurity();
            hasConnectionSecurity = (connectionSecurity != ConnectionSecurity.NONE);
        } else {
            currentAuthTypeViewPosition = authTypeView.getSelectedItemPosition();
            currentSecurityTypeViewPosition = securityTypeView.getSelectedItemPosition();
            currentPortViewSetting = portView.getText().toString();
        }

        boolean hasValidCertificateAlias = clientCertificateSpinner.getAlias() != null;
        boolean hasValidUserName = Utility.requiredFieldValid(usernameView);

        boolean hasValidPasswordSettings = hasValidUserName
                && !isAuthTypeExternal
                && Utility.requiredFieldValid(passwordView);

        boolean hasValidExternalAuthSettings = hasValidUserName
                && isAuthTypeExternal
                && hasConnectionSecurity
                && hasValidCertificateAlias;

        nextButton
                .setEnabled(Utility.domainFieldValid(serverView)
                        && Utility.requiredFieldValid(portView)
                        && (!requireLoginView.isChecked()
                                || hasValidPasswordSettings || hasValidExternalAuthSettings));
        Utility.setCompoundDrawablesAlpha(nextButton, nextButton.isEnabled() ? 255 : 128);
    }

    private void updatePortFromSecurityType() {
        ConnectionSecurity securityType = getSelectedSecurity();
        updateAuthPlainTextFromSecurityType(securityType);

        // Remove listener so as not to trigger validateFields() which is called
        // elsewhere as a result of user interaction.
        portView.removeTextChangedListener(validationTextWatcher);
        portView.setText(String.valueOf(AccountCreator.getDefaultPort(securityType, Type.SMTP)));
        portView.addTextChangedListener(validationTextWatcher);
    }

    private void updateAuthPlainTextFromSecurityType(ConnectionSecurity securityType) {
        authTypeAdapter.useInsecureText(securityType == ConnectionSecurity.NONE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == RESULT_OK) {
            if (Intent.ACTION_EDIT.equals(getIntent().getAction())) {
                account.save(Preferences.getPreferences(this));
                finish();
            } else {
                AccountSetupOptions.actionOptions(this, account, makeDefault);
                finish();
            }
        }
    }

    protected void onNext() {
        ConnectionSecurity securityType = getSelectedSecurity();
        String uri;
        String username = null;
        String password = null;
        String clientCertificateAlias = null;
        AuthType authType = null;
        if (requireLoginView.isChecked()) {
            username = usernameView.getText().toString();

            authType = getSelectedAuthType();
            if (AuthType.EXTERNAL == authType) {
                clientCertificateAlias = clientCertificateSpinner.getAlias();
            } else {
                password = passwordView.getText().toString();
            }
        }

        String newHost = serverView.getText().toString();
        int newPort = Integer.parseInt(portView.getText().toString());
        ServerSettings server = new ServerSettings(Type.SMTP, newHost, newPort, securityType, authType, username, password, clientCertificateAlias);
        uri = TransportUris.createTransportUri(server);
        account.deleteCertificate(newHost, newPort, CheckDirection.OUTGOING);
        account.setTransportUri(uri);
        AccountSetupCheckSettings.actionCheckSettings(this, account, CheckDirection.OUTGOING);
    }

    public void onClick(View v) {
        switch (v.getId()) {
        case R.id.next:
            onNext();
            break;
        }
    }

    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        requireLoginSettingsView.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        validateFields();
    }

    private void failure(Exception use) {
        Timber.e(use, "Failure");
        String toastText = getString(R.string.account_setup_bad_uri, use.getMessage());

        Toast toast = Toast.makeText(getApplication(), toastText, Toast.LENGTH_LONG);
        toast.show();
    }

    /*
     * Calls validateFields() which enables or disables the Next button
     * based on the fields' validity.
     */
    TextWatcher validationTextWatcher = new TextWatcher() {
        public void afterTextChanged(Editable s) {
            validateFields();
        }

        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }
    };

    OnClientCertificateChangedListener clientCertificateChangedListener = new OnClientCertificateChangedListener() {
        @Override
        public void onClientCertificateChanged(String alias) {
            validateFields();
        }
    };

    private AuthType getSelectedAuthType() {
        AuthTypeHolder holder = (AuthTypeHolder) authTypeView.getSelectedItem();
        return holder.authType;
    }

    private ConnectionSecurity getSelectedSecurity() {
        ConnectionSecurityHolder holder = (ConnectionSecurityHolder) securityTypeView.getSelectedItem();
        return holder.connectionSecurity;
    }
}
