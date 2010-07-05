package com.fsck.k9;

import java.io.Serializable;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import com.fsck.k9.mail.Message;

abstract public class CryptoProvider implements Serializable
{
    static final long serialVersionUID = 0x21071234;

    protected long mEncryptionKeyIds[] = null;
    protected long mSignatureKeyId = 0;
    protected String mSignatureUserId = null;
    protected boolean mSignatureSuccess = false;
    protected boolean mSignatureUnknown = false;
    protected String mDecryptedData = null;
    protected String mEncryptedData = null;

    abstract public boolean isAvailable(Context context);
    abstract public boolean isEncrypted(Message message);
    abstract public boolean isSigned(Message message);
    abstract public boolean onActivityResult(Activity activity, int requestCode, int resultCode,
                                             Intent data);
    abstract public boolean selectSecretKey(Activity activity);
    abstract public boolean selectEncryptionKeys(Activity activity, String emails);
    abstract public boolean encrypt(Activity activity, String data);
    abstract public boolean decrypt(Activity activity, String data);
    abstract public long[] getSecretKeyIdsFromEmail(Context context, String email);
    abstract public String getUserId(Context context, long keyId);

    public static CryptoProvider createInstance() {
        return new Apg();
    }

    public void setSignatureKeyId(long keyId)
    {
        mSignatureKeyId = keyId;
    }

    public long getSignatureKeyId()
    {
        return mSignatureKeyId;
    }

    public void setEncryptionKeys(long keyIds[])
    {
        mEncryptionKeyIds = keyIds;
    }

    public long[] getEncryptionKeys()
    {
        return mEncryptionKeyIds;
    }

    public boolean hasSignatureKey()
    {
        return mSignatureKeyId != 0;
    }

    public boolean hasEncryptionKeys()
    {
        return (mEncryptionKeyIds != null) && (mEncryptionKeyIds.length > 0);
    }

    public String getEncryptedData()
    {
        return mEncryptedData;
    }

    public String getDecryptedData()
    {
        return mDecryptedData;
    }

    public String getSignatureUserId()
    {
        return mSignatureUserId;
    }

    public boolean getSignatureSuccess()
    {
        return mSignatureSuccess;
    }

    public boolean getSignatureUnknown()
    {
        return mSignatureUnknown;
    }
}
