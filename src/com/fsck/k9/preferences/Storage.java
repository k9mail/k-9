package com.fsck.k9.preferences;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.util.Log;
import com.fsck.k9.K9;
import com.fsck.k9.Utility;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class Storage implements SharedPreferences
{
    private static ConcurrentHashMap<Context, Storage> storages =
        new ConcurrentHashMap<Context, Storage>();

    private int DB_VERSION = 3;
    private String DB_NAME = "preferences_storage";

    private volatile ConcurrentHashMap<String, String> storage =
        new ConcurrentHashMap<String, String>();

    private CopyOnWriteArrayList<OnSharedPreferenceChangeListener> listeners =
        new CopyOnWriteArrayList<OnSharedPreferenceChangeListener>();

    private ThreadLocal<ConcurrentHashMap<String, String>> workingStorage =
        new ThreadLocal<ConcurrentHashMap<String, String>>();

    private ThreadLocal<SQLiteDatabase> workingDB =
        new ThreadLocal<SQLiteDatabase>();

    private ThreadLocal<ArrayList<String>> workingChangedKeys =
        new ThreadLocal<ArrayList<String>>();

    private Context context = null;


    private SQLiteDatabase openDB()
    {
        SQLiteDatabase mDb = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null);

        if (mDb.getVersion() != DB_VERSION)
        {
            doDbUpgrade(mDb);
        }

        if (mDb.getVersion() != DB_VERSION)
        {
            Log.i(K9.LOG_TAG, "Creating Storage database");
            mDb.execSQL("DROP TABLE IF EXISTS preferences_storage");
            mDb.execSQL("CREATE TABLE preferences_storage " +
                        "(primkey TEXT PRIMARY KEY ON CONFLICT REPLACE, value TEXT)");
            mDb.setVersion(DB_VERSION);
        }
        return mDb;
    }

    private void doDbUpgrade(SQLiteDatabase mDb)
    {
        try
        {
            if (mDb.getVersion() < 2)
            {
                Log.i(K9.LOG_TAG, "Updating preferences to urlencoded username/password");

                String accountUuids = readValue(mDb, "accountUuids");
                if (accountUuids != null && accountUuids.length() != 0)
                {
                    String[] uuids = accountUuids.split(",");
                    for (int i = 0, length = uuids.length; i < length; i++)
                    {
                        String uuid = uuids[i];
                        try
                        {
                            String storeUriStr = Utility.base64Decode(readValue(mDb, uuid + ".storeUri"));
                            String transportUriStr = Utility.base64Decode(readValue(mDb, uuid + ".transportUri"));

                            URI uri = new URI(transportUriStr);
                            String newUserInfo = null;
                            if (transportUriStr != null)
                            {
                                String[] userInfoParts = uri.getUserInfo().split(":");

                                String usernameEnc = URLEncoder.encode(userInfoParts[0], "UTF-8");
                                String passwordEnc = "";
                                String authType = "";
                                if (userInfoParts.length > 1)
                                {
                                    passwordEnc = ":" + URLEncoder.encode(userInfoParts[1], "UTF-8");
                                }
                                if (userInfoParts.length > 2)
                                {
                                    authType = ":" + userInfoParts[2];
                                }

                                newUserInfo = usernameEnc + passwordEnc + authType;
                            }

                            if (newUserInfo != null)
                            {
                                URI newUri = new URI(uri.getScheme(), newUserInfo, uri.getHost(), uri.getPort(), uri.getPath(), uri.getQuery(), uri.getFragment());
                                String newTransportUriStr = Utility.base64Encode(newUri.toString());
                                writeValue(mDb, uuid + ".transportUri", newTransportUriStr);
                            }

                            uri = new URI(storeUriStr);
                            newUserInfo = null;
                            if (storeUriStr.startsWith("imap"))
                            {
                                String[] userInfoParts = uri.getUserInfo().split(":");
                                if (userInfoParts.length == 2)
                                {
                                    String usernameEnc = URLEncoder.encode(userInfoParts[0], "UTF-8");
                                    String passwordEnc = URLEncoder.encode(userInfoParts[1], "UTF-8");

                                    newUserInfo = usernameEnc + ":" + passwordEnc;
                                }
                                else
                                {
                                    String authType = userInfoParts[0];
                                    String usernameEnc = URLEncoder.encode(userInfoParts[1], "UTF-8");
                                    String passwordEnc = URLEncoder.encode(userInfoParts[2], "UTF-8");

                                    newUserInfo = authType + ":" + usernameEnc + ":" + passwordEnc;
                                }
                            }
                            else if (storeUriStr.startsWith("pop3"))
                            {
                                String[] userInfoParts = uri.getUserInfo().split(":", 2);
                                String usernameEnc = URLEncoder.encode(userInfoParts[0], "UTF-8");

                                String passwordEnc = "";
                                if (userInfoParts.length > 1)
                                {
                                    passwordEnc = ":" + URLEncoder.encode(userInfoParts[1], "UTF-8");
                                }

                                newUserInfo = usernameEnc + passwordEnc;
                            }
                            else if (storeUriStr.startsWith("webdav"))
                            {
                                String[] userInfoParts = uri.getUserInfo().split(":", 2);
                                String usernameEnc = URLEncoder.encode(userInfoParts[0], "UTF-8");

                                String passwordEnc = "";
                                if (userInfoParts.length > 1)
                                {
                                    passwordEnc = ":" + URLEncoder.encode(userInfoParts[1], "UTF-8");
                                }

                                newUserInfo = usernameEnc + passwordEnc;
                            }

                            if (newUserInfo != null)
                            {
                                URI newUri = new URI(uri.getScheme(), newUserInfo, uri.getHost(), uri.getPort(), uri.getPath(), uri.getQuery(), uri.getFragment());
                                String newStoreUriStr = Utility.base64Encode(newUri.toString());
                                writeValue(mDb, uuid + ".storeUri", newStoreUriStr);
                            }
                        }
                        catch (UnsupportedEncodingException e)
                        {
                            // This shouldn't happen
                        }
                        catch (URISyntaxException e)
                        {
                            Log.e(K9.LOG_TAG, "Malformed store or transport URI. Check your account settings.", e);
                        }
                    }
                }
            }

            if (mDb.getVersion() < 3)
            {
                Log.i(K9.LOG_TAG, "Updating account identities to new UUID format");

                String accountUuids = readValue(mDb, "accountUuids");
                String[] uuids = accountUuids.split(",");

                for (int i = 0, length = uuids.length; i < length; i++)
                {
                    List<String> identities = new ArrayList<String>();
                    String accountUuid = uuids[i];
                    int ident = 0;
                    boolean gotOne = false;
                    do
                    {
                        gotOne = false;
                        String name = readValue(mDb, accountUuid + ".name." + ident);
                        String email = readValue(mDb, accountUuid + ".email." + ident);
                        String signatureUse = readValue(mDb, accountUuid + ".signatureUse." + ident);
                        String signature = readValue(mDb, accountUuid + ".signature." + ident);
                        String description = readValue(mDb, accountUuid + ".description." + ident);

                        if (email != null)
                        {
                            Log.v(K9.LOG_TAG, "Updating account " + accountUuid + ", identity: " + description);

                            gotOne = true;
                            String uuid = UUID.randomUUID().toString();
                            writeValue(mDb, accountUuid + "." + uuid + ".name", name);
                            writeValue(mDb, accountUuid + "." + uuid + ".email", email);
                            writeValue(mDb, accountUuid + "." + uuid + ".signatureUse", signatureUse);
                            writeValue(mDb, accountUuid + "." + uuid + ".signature", signature);
                            writeValue(mDb, accountUuid + "." + uuid + ".description", description);
                            identities.add(uuid);
                        }

                        removeKey(mDb, accountUuid + ".name." + ident);
                        removeKey(mDb, accountUuid + ".email." + ident);
                        removeKey(mDb, accountUuid + ".signatureUse." + ident);
                        removeKey(mDb, accountUuid + ".signature." + ident);
                        removeKey(mDb, accountUuid + ".description." + ident);

                        ident++;
                    }
                    while (gotOne);

                    if (identities.size() == 0)
                    {
                        String name = readValue(mDb, accountUuid + ".name");
                        String email = readValue(mDb, accountUuid + ".email");
                        String signatureUse = readValue(mDb, accountUuid + ".signatureUse");
                        String signature = readValue(mDb, accountUuid + ".signature");

                        Log.v(K9.LOG_TAG, "Updating account " + accountUuid + ", identity: " + email);

                        String uuid = UUID.randomUUID().toString();
                        writeValue(mDb, accountUuid + "." + uuid + ".name", name);
                        writeValue(mDb, accountUuid + "." + uuid + ".email", email);
                        writeValue(mDb, accountUuid + "." + uuid + ".signatureUse", signatureUse);
                        writeValue(mDb, accountUuid + "." + uuid + ".signature", signature);
                        writeValue(mDb, accountUuid + "." + uuid + ".description", email);
                        identities.add(uuid);
                    }

                    removeKey(mDb, accountUuid + ".name");
                    removeKey(mDb, accountUuid + ".email");
                    removeKey(mDb, accountUuid + ".signatureUse");
                    removeKey(mDb, accountUuid + ".signature");

                    StringBuffer sb = new StringBuffer();
                    for (String uuid : identities)
                    {
                        if (sb.length() > 0)
                        {
                            sb.append(',');
                        }
                        sb.append(uuid);
                    }
                    String identityUuids = sb.toString();
                    writeValue(mDb, accountUuid + ".identityUuids", identityUuids);
                }
            }

            mDb.setVersion(DB_VERSION);
        }
        catch (SQLiteException e)
        {
            Log.e(K9.LOG_TAG, "Exception while upgrading preferences database. Resetting the DB to v0");
            mDb.setVersion(0);
        }
    }

    public static Storage getStorage(Context context)
    {
        Storage tmpStorage = storages.get(context);
        if (tmpStorage != null)
        {
            if (K9.DEBUG)
            {
                Log.d(K9.LOG_TAG, "Returning already existing Storage");
            }
            return tmpStorage;
        }
        else
        {
            if (K9.DEBUG)
            {
                Log.d(K9.LOG_TAG, "Creating provisional storage");
            }
            tmpStorage = new Storage(context);
            Storage oldStorage = storages.putIfAbsent(context, tmpStorage);
            if (oldStorage != null)
            {
                if (K9.DEBUG)
                {
                    Log.d(K9.LOG_TAG, "Another thread beat us to creating the Storage, returning that one");
                }
                return oldStorage;
            }
            else
            {
                if (K9.DEBUG)
                {
                    Log.d(K9.LOG_TAG, "Returning the Storage we created");
                }
                return tmpStorage;
            }
        }
    }

    private void loadValues()
    {
        long startTime = System.currentTimeMillis();
        Log.i(K9.LOG_TAG, "Loading preferences from DB into Storage");
        Cursor cursor = null;
        SQLiteDatabase mDb = null;
        try
        {
            mDb = openDB();

            cursor = mDb.rawQuery("SELECT primkey, value FROM preferences_storage", null);
            while (cursor.moveToNext())
            {
                String key = cursor.getString(0);
                String value = cursor.getString(1);
                if (K9.DEBUG)
                {
                    Log.d(K9.LOG_TAG, "Loading key '" + key + "', value = '" + value + "'");
                }
                storage.put(key, value);
            }
        }
        finally
        {
            if (cursor != null)
            {
                cursor.close();
            }
            if (mDb != null)
            {
                mDb.close();
            }
            long endTime = System.currentTimeMillis();
            Log.i(K9.LOG_TAG, "Preferences load took " + (endTime - startTime) + "ms");
        }
    }

    private Storage(Context context)
    {
        this.context = context;
        loadValues();
    }

    private void keyChange(String key)
    {
        ArrayList<String> changedKeys = workingChangedKeys.get();
        if (changedKeys.contains(key) == false)
        {
            changedKeys.add(key);
        }
    }

    protected void put(String key, String value)
    {
        ContentValues cv = new ContentValues();
        cv.put("primkey", key);
        cv.put("value", value);
        workingDB.get().insert("preferences_storage", "primkey", cv);
        workingStorage.get().put(key, value);

        keyChange(key);
    }

    protected void remove(String key)
    {
        workingDB.get().delete("preferences_storage", "primkey = ?", new String[] { key });
        workingStorage.get().remove(key);

        keyChange(key);
    }

    protected void removeAll()
    {
        for (String key : workingStorage.get().keySet())
        {
            keyChange(key);
        }
        workingDB.get().execSQL("DELETE FROM preferences_storage");
        workingStorage.get().clear();
    }

    protected void doInTransaction(Runnable dbWork)
    {
        ConcurrentHashMap<String, String> newStorage = new ConcurrentHashMap<String, String>();
        newStorage.putAll(storage);
        workingStorage.set(newStorage);

        SQLiteDatabase mDb = openDB();
        workingDB.set(mDb);

        ArrayList<String> changedKeys = new ArrayList<String>();
        workingChangedKeys.set(changedKeys);

        mDb.beginTransaction();
        try
        {
            dbWork.run();
            mDb.setTransactionSuccessful();
            storage = newStorage;
            for (String changedKey : changedKeys)
            {
                for (OnSharedPreferenceChangeListener listener : listeners)
                {
                    listener.onSharedPreferenceChanged(this, changedKey);
                }
            }
        }
        finally
        {
            workingDB.remove();
            workingStorage.remove();
            workingChangedKeys.remove();
            mDb.endTransaction();
            if (mDb != null)
            {
                mDb.close();
            }
        }
    }

    public long size()
    {
        return storage.size();
    }

    public boolean contains(String key)
    {
        return storage.contains(key);
    }

    public com.fsck.k9.preferences.Editor edit()
    {
        return new com.fsck.k9.preferences.Editor(this);
    }

    public Map<String, String> getAll()
    {
        return storage;
    }

    public boolean getBoolean(String key, boolean defValue)
    {
        String val = storage.get(key);
        if (val == null)
        {
            return defValue;
        }
        return Boolean.parseBoolean(val);
    }

    public float getFloat(String key, float defValue)
    {
        String val = storage.get(key);
        if (val == null)
        {
            return defValue;
        }
        return Float.parseFloat(val);
    }

    public int getInt(String key, int defValue)
    {
        String val = storage.get(key);
        if (val == null)
        {
            return defValue;
        }
        return Integer.parseInt(val);
    }

    public long getLong(String key, long defValue)
    {
        String val = storage.get(key);
        if (val == null)
        {
            return defValue;
        }
        return Long.parseLong(val);
    }

    public String getString(String key, String defValue)
    {
        String val = storage.get(key);
        if (val == null)
        {
            return defValue;
        }
        return val;
    }

    public void registerOnSharedPreferenceChangeListener(
        OnSharedPreferenceChangeListener listener)
    {
        listeners.addIfAbsent(listener);
    }

    public void unregisterOnSharedPreferenceChangeListener(
        OnSharedPreferenceChangeListener listener)
    {
        listeners.remove(listener);
    }

    private String readValue(SQLiteDatabase mDb, String key)
    {
        Cursor cursor = null;
        String value = null;
        try
        {
            cursor = mDb.query(
                    DB_NAME,
                    new String[] {"value"},
                    "primkey = ?",
                    new String[] {key},
                    null,
                    null,
                    null);

            if (cursor.moveToNext())
            {
                value = cursor.getString(0);
                if (K9.DEBUG)
                {
                    Log.d(K9.LOG_TAG, "Loading key '" + key + "', value = '" + value + "'");
                }
            }
        }
        finally
        {
            if (cursor != null)
            {
                cursor.close();
            }
        }

        return value;
    }

    private void writeValue(SQLiteDatabase mDb, String key, String value)
    {
        ContentValues cv = new ContentValues();
        cv.put("primkey", key);
        cv.put("value", value);

        long result = mDb.insert(DB_NAME, "primkey", cv);

        if (result == -1)
        {
            Log.e(K9.LOG_TAG, "Error writing key '" + key + "', value = '" + value + "'");
        }
    }

    private void removeKey(SQLiteDatabase mDb, String key)
    {
        mDb.delete(DB_NAME, "primkey = ?", new String[] {key});
    }
}
