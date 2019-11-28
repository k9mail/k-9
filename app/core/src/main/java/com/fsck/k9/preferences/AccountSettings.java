package com.fsck.k9.preferences;


import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import android.content.Context;

import com.fsck.k9.Account;
import com.fsck.k9.Account.DeletePolicy;
import com.fsck.k9.Account.Expunge;
import com.fsck.k9.Account.FolderMode;
import com.fsck.k9.Account.MessageFormat;
import com.fsck.k9.Account.QuoteStyle;
import com.fsck.k9.Account.Searchable;
import com.fsck.k9.Account.ShowPictures;
import com.fsck.k9.Account.SortType;
import com.fsck.k9.Account.SpecialFolderSelection;
import com.fsck.k9.AccountPreferenceSerializer;
import com.fsck.k9.DI;
import com.fsck.k9.K9;
import com.fsck.k9.core.R;
import com.fsck.k9.mailstore.StorageManager;
import com.fsck.k9.preferences.Settings.BooleanSetting;
import com.fsck.k9.preferences.Settings.ColorSetting;
import com.fsck.k9.preferences.Settings.EnumSetting;
import com.fsck.k9.preferences.Settings.IntegerRangeSetting;
import com.fsck.k9.preferences.Settings.InvalidSettingValueException;
import com.fsck.k9.preferences.Settings.PseudoEnumSetting;
import com.fsck.k9.preferences.Settings.SettingsDescription;
import com.fsck.k9.preferences.Settings.SettingsUpgrader;
import com.fsck.k9.preferences.Settings.StringSetting;
import com.fsck.k9.preferences.Settings.V;


public class AccountSettings {
    static final Map<String, TreeMap<Integer, SettingsDescription>> SETTINGS;
    private static final Map<Integer, SettingsUpgrader> UPGRADERS;

    static {
        Map<String, TreeMap<Integer, SettingsDescription>> s = new LinkedHashMap<>();

        /*
         * When adding new settings here, be sure to increment {@link Settings.VERSION}
         * and use that for whatever you add here.
         */

        s.put("alwaysBcc", Settings.versions(
                new V(11, new StringSetting(""))
        ));
        s.put("alwaysShowCcBcc", Settings.versions(
                new V(13, new BooleanSetting(false))
        ));
        s.put("archiveFolderName", Settings.versions(
                new V(1, new StringSetting(SettingsUpgraderV53.FOLDER_NONE)),
                new V(53, new StringSetting(null))
        ));
        s.put("autoExpandFolderName", Settings.versions(
                new V(1, new StringSetting("INBOX"))
        ));
        s.put("automaticCheckIntervalMinutes", Settings.versions(
                new V(1, new IntegerResourceSetting(-1, R.array.check_frequency_values))
        ));
        s.put("chipColor", Settings.versions(
                new V(1, new ColorSetting(0xFF0000FF))
        ));
        s.put("defaultQuotedTextShown", Settings.versions(
                new V(1, new BooleanSetting(AccountPreferenceSerializer.DEFAULT_QUOTED_TEXT_SHOWN))
        ));
        s.put("deletePolicy", Settings.versions(
                new V(1, new DeletePolicySetting(DeletePolicy.NEVER))
        ));
        s.put("displayCount", Settings.versions(
                new V(1, new IntegerResourceSetting(K9.DEFAULT_VISIBLE_LIMIT,
                        R.array.display_count_values))
        ));
        s.put("draftsFolderName", Settings.versions(
                new V(1, new StringSetting(SettingsUpgraderV53.FOLDER_NONE)),
                new V(53, new StringSetting(null))
        ));
        s.put("expungePolicy", Settings.versions(
                new V(1, new StringResourceSetting(Expunge.EXPUNGE_IMMEDIATELY.name(),
                        R.array.expunge_policy_values))
        ));
        s.put("folderDisplayMode", Settings.versions(
                new V(1, new EnumSetting<>(FolderMode.class, FolderMode.NOT_SECOND_CLASS))
        ));
        s.put("folderPushMode", Settings.versions(
                new V(1, new EnumSetting<>(FolderMode.class, FolderMode.FIRST_CLASS))
        ));
        s.put("folderSyncMode", Settings.versions(
                new V(1, new EnumSetting<>(FolderMode.class, FolderMode.FIRST_CLASS))
        ));
        s.put("folderTargetMode", Settings.versions(
                new V(1, new EnumSetting<>(FolderMode.class, FolderMode.NOT_SECOND_CLASS))
        ));
        s.put("goToUnreadMessageSearch", Settings.versions(
                new V(1, new BooleanSetting(false))
        ));
        s.put("idleRefreshMinutes", Settings.versions(
                new V(1, new IntegerResourceSetting(24, R.array.idle_refresh_period_values))
        ));
        s.put("inboxFolderName", Settings.versions(
                new V(1, new StringSetting("INBOX"))
        ));
        s.put("led", Settings.versions(
                new V(1, new BooleanSetting(true))
        ));
        s.put("ledColor", Settings.versions(
                new V(1, new ColorSetting(0xFF0000FF))
        ));
        s.put("localStorageProvider", Settings.versions(
                new V(1, new StorageProviderSetting())
        ));
        s.put("markMessageAsReadOnView", Settings.versions(
                new V(7, new BooleanSetting(true))
        ));
        s.put("maxPushFolders", Settings.versions(
                new V(1, new IntegerRangeSetting(0, 100, 10))
        ));
        s.put("maximumAutoDownloadMessageSize", Settings.versions(
                new V(1, new IntegerResourceSetting(32768, R.array.autodownload_message_size_values))
        ));
        s.put("maximumPolledMessageAge", Settings.versions(
                new V(1, new IntegerResourceSetting(-1, R.array.message_age_values))
        ));
        s.put("messageFormat", Settings.versions(
                new V(1, new EnumSetting<>(MessageFormat.class, AccountPreferenceSerializer.DEFAULT_MESSAGE_FORMAT))
        ));
        s.put("messageFormatAuto", Settings.versions(
                new V(2, new BooleanSetting(AccountPreferenceSerializer.DEFAULT_MESSAGE_FORMAT_AUTO))
        ));
        s.put("messageReadReceipt", Settings.versions(
                new V(1, new BooleanSetting(AccountPreferenceSerializer.DEFAULT_MESSAGE_READ_RECEIPT))
        ));
        s.put("notifyMailCheck", Settings.versions(
                new V(1, new BooleanSetting(false))
        ));
        s.put("notifyNewMail", Settings.versions(
                new V(1, new BooleanSetting(false))
        ));
        s.put("folderNotifyNewMailMode", Settings.versions(
                new V(34, new EnumSetting<>(FolderMode.class, FolderMode.ALL))
        ));
        s.put("notifySelfNewMail", Settings.versions(
                new V(1, new BooleanSetting(true))
        ));
        s.put("pushPollOnConnect", Settings.versions(
                new V(1, new BooleanSetting(true))
        ));
        s.put("quotePrefix", Settings.versions(
                new V(1, new StringSetting(AccountPreferenceSerializer.DEFAULT_QUOTE_PREFIX))
        ));
        s.put("quoteStyle", Settings.versions(
                new V(1, new EnumSetting<>(QuoteStyle.class, AccountPreferenceSerializer.DEFAULT_QUOTE_STYLE))
        ));
        s.put("replyAfterQuote", Settings.versions(
                new V(1, new BooleanSetting(AccountPreferenceSerializer.DEFAULT_REPLY_AFTER_QUOTE))
        ));
        s.put("ring", Settings.versions(
                new V(1, new BooleanSetting(true))
        ));
        s.put("ringtone", Settings.versions(
                new V(1, new RingtoneSetting("content://settings/system/notification_sound"))
        ));
        s.put("searchableFolders", Settings.versions(
                new V(1, new EnumSetting<>(Searchable.class, Searchable.ALL))
        ));
        s.put("sentFolderName", Settings.versions(
                new V(1, new StringSetting(SettingsUpgraderV53.FOLDER_NONE)),
                new V(53, new StringSetting(null))
        ));
        s.put("sortTypeEnum", Settings.versions(
                new V(9, new EnumSetting<>(SortType.class, Account.DEFAULT_SORT_TYPE))
        ));
        s.put("sortAscending", Settings.versions(
                new V(9, new BooleanSetting(Account.DEFAULT_SORT_ASCENDING))
        ));
        s.put("showPicturesEnum", Settings.versions(
                new V(1, new EnumSetting<>(ShowPictures.class, ShowPictures.NEVER))
        ));
        s.put("signatureBeforeQuotedText", Settings.versions(
                new V(1, new BooleanSetting(false))
        ));
        s.put("spamFolderName", Settings.versions(
                new V(1, new StringSetting(SettingsUpgraderV53.FOLDER_NONE)),
                new V(53, new StringSetting(null))
        ));
        s.put("stripSignature", Settings.versions(
                new V(2, new BooleanSetting(AccountPreferenceSerializer.DEFAULT_STRIP_SIGNATURE))
        ));
        s.put("subscribedFoldersOnly", Settings.versions(
                new V(1, new BooleanSetting(false))
        ));
        s.put("syncRemoteDeletions", Settings.versions(
                new V(1, new BooleanSetting(true))
        ));
        s.put("trashFolderName", Settings.versions(
                new V(1, new StringSetting(SettingsUpgraderV53.FOLDER_NONE)),
                new V(53, new StringSetting(null))
        ));
        s.put("useCompression.MOBILE", Settings.versions(
                new V(1, new BooleanSetting(true))
        ));
        s.put("useCompression.OTHER", Settings.versions(
                new V(1, new BooleanSetting(true))
        ));
        s.put("useCompression.WIFI", Settings.versions(
                new V(1, new BooleanSetting(true))
        ));
        s.put("vibrate", Settings.versions(
                new V(1, new BooleanSetting(false))
        ));
        s.put("vibratePattern", Settings.versions(
                new V(1, new IntegerResourceSetting(0, R.array.vibrate_pattern_values))
        ));
        s.put("vibrateTimes", Settings.versions(
                new V(1, new IntegerResourceSetting(5, R.array.vibrate_times_label))
        ));
        s.put("allowRemoteSearch", Settings.versions(
                new V(18, new BooleanSetting(true))
        ));
        s.put("remoteSearchNumResults", Settings.versions(
                new V(18, new IntegerResourceSetting(AccountPreferenceSerializer.DEFAULT_REMOTE_SEARCH_NUM_RESULTS,
                        R.array.remote_search_num_results_values))
        ));
        s.put("remoteSearchFullText", Settings.versions(
                new V(18, new BooleanSetting(false))
        ));
        s.put("notifyContactsMailOnly", Settings.versions(
                new V(42, new BooleanSetting(false))
        ));
        s.put("openPgpHideSignOnly", Settings.versions(
                new V(50, new BooleanSetting(true))
        ));
        s.put("openPgpEncryptSubject", Settings.versions(
                new V(51, new BooleanSetting(true))
        ));
        s.put("openPgpEncryptAllDrafts", Settings.versions(
                new V(55, new BooleanSetting(true))
        ));
        s.put("autocryptMutualMode", Settings.versions(
                new V(50, new BooleanSetting(false))
        ));
        s.put("uploadSentMessages", Settings.versions(
                new V(52, new BooleanSetting(true))
        ));
        s.put("archiveFolderSelection", Settings.versions(
                new V(54, new EnumSetting<>(SpecialFolderSelection.class, SpecialFolderSelection.AUTOMATIC))
        ));
        s.put("draftsFolderSelection", Settings.versions(
                new V(54, new EnumSetting<>(SpecialFolderSelection.class, SpecialFolderSelection.AUTOMATIC))
        ));
        s.put("sentFolderSelection", Settings.versions(
                new V(54, new EnumSetting<>(SpecialFolderSelection.class, SpecialFolderSelection.AUTOMATIC))
        ));
        s.put("spamFolderSelection", Settings.versions(
                new V(54, new EnumSetting<>(SpecialFolderSelection.class, SpecialFolderSelection.AUTOMATIC))
        ));
        s.put("trashFolderSelection", Settings.versions(
                new V(54, new EnumSetting<>(SpecialFolderSelection.class, SpecialFolderSelection.AUTOMATIC))
        ));
        s.put("resizeImageEnabled", Settings.versions(
                new V(58, new BooleanSetting(Account.DEFAULT_RESIZE_IMAGE_ENABLED))
        ));
        s.put("resizeImageCircumference", Settings.versions(
                new V(58, new StringSetting(Integer.toString(Account.DEFAULT_RESIZE_IMAGE_CIRCUMFERENCE)))
        ));
        s.put("resizeImageQuality", Settings.versions(
                new V(58, new StringSetting(Integer.toString(Account.DEFAULT_RESIZE_IMAGE_QUALITY)))
        ));
        // note that there is no setting for openPgpProvider, because this will have to be set up together
        // with the actual provider after import anyways.

        SETTINGS = Collections.unmodifiableMap(s);

        Map<Integer, SettingsUpgrader> u = new HashMap<>();
        u.put(53, new SettingsUpgraderV53());
        u.put(54, new SettingsUpgraderV54());

        UPGRADERS = Collections.unmodifiableMap(u);
    }

    static Map<String, Object> validate(int version, Map<String, String> importedSettings, boolean useDefaultValues) {
        return Settings.validate(version, SETTINGS, importedSettings, useDefaultValues);
    }

    public static Set<String> upgrade(int version, Map<String, Object> validatedSettings) {
        return Settings.upgrade(version, UPGRADERS, SETTINGS, validatedSettings);
    }

    public static Map<String, String> convert(Map<String, Object> settings) {
        return Settings.convert(settings, SETTINGS);
    }

    static Map<String, String> getAccountSettings(Storage storage, String uuid) {
        Map<String, String> result = new HashMap<>();
        String prefix = uuid + ".";
        for (String key : SETTINGS.keySet()) {
            String value = storage.getString(prefix + key, null);
            if (value != null) {
                result.put(key, value);
            }
        }
        return result;
    }

    private static class IntegerResourceSetting extends PseudoEnumSetting<Integer> {
        private final Context context = DI.get(Context.class);
        private final Map<Integer, String> mapping;

        IntegerResourceSetting(int defaultValue, int resId) {
            super(defaultValue);

            Map<Integer, String> mapping = new HashMap<>();
            String[] values = context.getResources().getStringArray(resId);
            for (String value : values) {
                int intValue = Integer.parseInt(value);
                mapping.put(intValue, value);
            }
            this.mapping = Collections.unmodifiableMap(mapping);
        }

        @Override
        protected Map<Integer, String> getMapping() {
            return mapping;
        }

        @Override
        public Integer fromString(String value) throws InvalidSettingValueException {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new InvalidSettingValueException();
            }
        }
    }

    private static class StringResourceSetting extends PseudoEnumSetting<String> {
        private final Context context = DI.get(Context.class);
        private final Map<String, String> mapping;

        StringResourceSetting(String defaultValue, int resId) {
            super(defaultValue);

            Map<String, String> mapping = new HashMap<>();
            String[] values = context.getResources().getStringArray(resId);
            for (String value : values) {
                mapping.put(value, value);
            }
            this.mapping = Collections.unmodifiableMap(mapping);
        }

        @Override
        protected Map<String, String> getMapping() {
            return mapping;
        }

        @Override
        public String fromString(String value) throws InvalidSettingValueException {
            if (!mapping.containsKey(value)) {
                throw new InvalidSettingValueException();
            }
            return value;
        }
    }

    private static class RingtoneSetting extends SettingsDescription<String> {
        RingtoneSetting(String defaultValue) {
            super(defaultValue);
        }

        @Override
        public String fromString(String value) {
            //TODO: add validation
            return value;
        }
    }

    private static class StorageProviderSetting extends SettingsDescription<String> {
        private final Context context = DI.get(Context.class);

        StorageProviderSetting() {
            super(null);
        }

        @Override
        public String getDefaultValue() {
            return StorageManager.getInstance(context).getDefaultProviderId();
        }

        @Override
        public String fromString(String value) {
            StorageManager storageManager = StorageManager.getInstance(context);
            Map<String, String> providers = storageManager.getAvailableProviders();
            if (providers.containsKey(value)) {
                return value;
            }
            throw new RuntimeException("Validation failed");
        }
    }

    private static class DeletePolicySetting extends PseudoEnumSetting<Integer> {
        private Map<Integer, String> mapping;

        DeletePolicySetting(DeletePolicy defaultValue) {
            super(defaultValue.setting);
            Map<Integer, String> mapping = new HashMap<>();
            mapping.put(DeletePolicy.NEVER.setting, "NEVER");
            mapping.put(DeletePolicy.ON_DELETE.setting, "DELETE");
            mapping.put(DeletePolicy.MARK_AS_READ.setting, "MARK_AS_READ");
            this.mapping = Collections.unmodifiableMap(mapping);
        }

        @Override
        protected Map<Integer, String> getMapping() {
            return mapping;
        }

        @Override
        public Integer fromString(String value) throws InvalidSettingValueException {
            try {
                Integer deletePolicy = Integer.parseInt(value);
                if (mapping.containsKey(deletePolicy)) {
                    return deletePolicy;
                }
            } catch (NumberFormatException e) { /* do nothing */ }

            throw new InvalidSettingValueException();
        }
    }

    /**
     * Upgrades settings from version 52 to 53
     *
     * Replace folder entries of "-NONE-" with {@code null}.
     */
    private static class SettingsUpgraderV53 implements SettingsUpgrader {
        private static final String FOLDER_NONE = "-NONE-";

        @Override
        public Set<String> upgrade(Map<String, Object> settings) {
            upgradeFolderEntry(settings, "archiveFolderName");
            upgradeFolderEntry(settings, "autoExpandFolderName");
            upgradeFolderEntry(settings, "draftsFolderName");
            upgradeFolderEntry(settings, "sentFolderName");
            upgradeFolderEntry(settings, "spamFolderName");
            upgradeFolderEntry(settings, "trashFolderName");

            return null;
        }

        private void upgradeFolderEntry(Map<String, Object> settings, String key) {
            String archiveFolderName = (String) settings.get(key);
            if (FOLDER_NONE.equals(archiveFolderName)) {
                settings.put(key, null);
            }
        }
    }

    /**
     * Upgrades settings from version 53 to 54
     *
     * Inserts folder selection entries with a value of "MANUAL"
     */
    private static class SettingsUpgraderV54 implements SettingsUpgrader {
        private static final String FOLDER_SELECTION_MANUAL = "MANUAL";

        @Override
        public Set<String> upgrade(Map<String, Object> settings) {
            settings.put("archiveFolderSelection", FOLDER_SELECTION_MANUAL);
            settings.put("draftsFolderSelection", FOLDER_SELECTION_MANUAL);
            settings.put("sentFolderSelection", FOLDER_SELECTION_MANUAL);
            settings.put("spamFolderSelection", FOLDER_SELECTION_MANUAL);
            settings.put("trashFolderSelection", FOLDER_SELECTION_MANUAL);

            return null;
        }
    }
}
