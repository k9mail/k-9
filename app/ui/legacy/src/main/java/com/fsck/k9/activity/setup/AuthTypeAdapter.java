package com.fsck.k9.activity.setup;

import android.content.Context;
import android.widget.ArrayAdapter;

import com.fsck.k9.mail.AuthType;


class AuthTypeAdapter extends ArrayAdapter<AuthTypeHolder> {
    public AuthTypeAdapter(Context context, int resource, AuthTypeHolder[] holders) {
        super(context, resource, holders);
    }

    public static AuthTypeAdapter get(Context context) {
        AuthType[] authTypes = new AuthType[]{AuthType.PLAIN, AuthType.CRAM_MD5, AuthType.EXTERNAL,AuthType.XOAUTH2};
        AuthTypeHolder[] holders = new AuthTypeHolder[authTypes.length];
        for (int i = 0; i < authTypes.length; i++) {
            holders[i] = new AuthTypeHolder(authTypes[i], context.getResources());
        }
        AuthTypeAdapter authTypesAdapter = new AuthTypeAdapter(context,
                android.R.layout.simple_spinner_item, holders);
        authTypesAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return authTypesAdapter;
    }

    /**
     * Used to select an appropriate localized text label for the
     * {@code AuthType.PLAIN} option presented to users.
     *
     * @param insecure
     *            <p>
     *            A value of {@code true} will use "Normal password".
     *            <p>
     *            A value of {@code false} will use
     *            "Password, transmitted insecurely"
     */
    public void useInsecureText(boolean insecure) {
        for (int i=0; i<getCount(); i++) {
            getItem(i).setInsecure(insecure);
        }
        notifyDataSetChanged();
    }

    public int getAuthPosition(AuthType authenticationType) {
        for (int i=0; i<getCount(); i++) {
            if (getItem(i).authType == authenticationType) {
                return i;
            }
        }
        return -1;
    }
}
