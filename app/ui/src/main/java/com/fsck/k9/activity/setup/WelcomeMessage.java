package com.fsck.k9.activity.setup;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.TextView;

import com.fsck.k9.DI;
import com.fsck.k9.ui.R;
import com.fsck.k9.activity.Accounts;
import com.fsck.k9.activity.K9Activity;
import com.fsck.k9.ui.helper.HtmlToSpanned;


/**
 * Displays a welcome message when no accounts have been created yet.
 */
public class WelcomeMessage extends K9Activity implements OnClickListener{
    private static final HtmlToSpanned htmlToSpanned = DI.get(HtmlToSpanned.class);


    public static void showWelcomeMessage(Context context) {
        Intent intent = new Intent(context, WelcomeMessage.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setLayout(R.layout.welcome_message);

        TextView welcome = findViewById(R.id.welcome_message);
        welcome.setText(htmlToSpanned.convert(getString(R.string.accounts_welcome)));
        welcome.setMovementMethod(LinkMovementMethod.getInstance());

        findViewById(R.id.next).setOnClickListener(this);
        findViewById(R.id.import_settings).setOnClickListener(this);
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        if (id == R.id.next) {
            AccountSetupBasics.actionNewAccount(this);
            finish();
        } else if (id == R.id.import_settings) {
            Accounts.importSettings(this);
            finish();
        }
    }
}
