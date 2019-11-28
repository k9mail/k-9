
package com.fsck.k9.helper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.content.Context;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.TextView;

import org.apache.james.mime4j.util.MimeUtil;
import timber.log.Timber;

public class Utility {

    // \u00A0 (non-breaking space) happens to be used by French MUA

    // Note: no longer using the ^ beginning character combined with (...)+
    // repetition matching as we might want to strip ML tags. Ex:
    // Re: [foo] Re: RE : [foo] blah blah blah
    private static final Pattern RESPONSE_PATTERN = Pattern.compile(
                "((Re|Fw|Fwd|Aw|R\\u00E9f\\.)(\\[\\d+\\])?[\\u00A0 ]?: *)+", Pattern.CASE_INSENSITIVE);

    /**
     * Mailing-list tag pattern to match strings like "[foobar] "
     */
    private static final Pattern TAG_PATTERN = Pattern.compile("\\[[-_a-z0-9]+\\] ",
            Pattern.CASE_INSENSITIVE);

    private static Handler sMainThreadHandler;

    public static boolean arrayContains(Object[] a, Object o) {
        for (Object element : a) {
            if (element.equals(o)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isAnyMimeType(String o, String... a) {
        for (String element : a) {
            if (MimeUtil.isSameMimeType(element, o)) {
                return true;
            }
        }
        return false;
    }

    public static boolean arrayContainsAny(Object[] a, Object... o) {
        for (Object element : a) {
            if (arrayContains(o, element)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Combines the given array of Objects into a single String using
     * each Object's toString() method and the separator character
     * between each part.
     *
     * @param parts
     * @param separator
     * @return new String
     */
    public static String combine(Object[] parts, char separator) {
        if (parts == null) {
            return null;
        }
        return TextUtils.join(String.valueOf(separator), parts);
    }

    /**
     * Combines the given Objects into a single String using
     * each Object's toString() method and the separator character
     * between each part.
     *
     * @param parts
     * @param separator
     * @return new String
     */
    public static String combine(Iterable<?> parts, char separator) {
        if (parts == null) {
            return null;
        }
        return TextUtils.join(String.valueOf(separator), parts);
    }

    public static boolean requiredFieldValid(TextView view) {
        return view.getText() != null && view.getText().length() > 0;
    }


    public static boolean requiredFieldValid(Editable s) {
        return s != null && s.length() > 0;
    }

    public static boolean domainFieldValid(EditText view) {
        if (view.getText() != null) {
            String s = view.getText().toString();
            if (s.matches("^([a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?\\.)*[a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?$") &&
                s.length() <= 253) {
                return true;
            }
            if (s.matches("^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$")) {
                return true;
            }
        }
        return false;
    }

    /*
     * TODO disabled this method globally. It is used in all the settings screens but I just
     * noticed that an unrelated icon was dimmed. Android must share drawables internally.
     */
    public static void setCompoundDrawablesAlpha(TextView view, int alpha) {
//        Drawable[] drawables = view.getCompoundDrawables();
//        for (Drawable drawable : drawables) {
//            if (drawable != null) {
//                drawable.setAlpha(alpha);
//            }
//        }
    }

    /**
     * Extract the 'original' subject value, by ignoring leading
     * response/forward marker and '[XX]' formatted tags (as many mailing-list
     * softwares do).
     *
     * <p>
     * Result is also trimmed.
     * </p>
     *
     * @param subject
     *            Never <code>null</code>.
     * @return Never <code>null</code>.
     */
    public static String stripSubject(final String subject) {
        int lastPrefix = 0;

        final Matcher tagMatcher = TAG_PATTERN.matcher(subject);
        String tag = null;
        // whether tag stripping logic should be active
        boolean tagPresent = false;
        // whether the last action stripped a tag
        boolean tagStripped = false;
        if (tagMatcher.find(0)) {
            tagPresent = true;
            if (tagMatcher.start() == 0) {
                // found at beginning of subject, considering it an actual tag
                tag = tagMatcher.group();

                // now need to find response marker after that tag
                lastPrefix = tagMatcher.end();
                tagStripped = true;
            }
        }

        final Matcher matcher = RESPONSE_PATTERN.matcher(subject);

        // while:
        // - lastPrefix is within the bounds
        // - response marker found at lastPrefix position
        // (to make sure we don't catch response markers that are part of
        // the actual subject)

        while (lastPrefix < subject.length() - 1
                && matcher.find(lastPrefix)
                && matcher.start() == lastPrefix
                && (!tagPresent || tag == null || subject.regionMatches(matcher.end(), tag, 0,
                        tag.length()))) {
            lastPrefix = matcher.end();

            if (tagPresent) {
                tagStripped = false;
                if (tag == null) {
                    // attempt to find tag
                    if (tagMatcher.start() == lastPrefix) {
                        tag = tagMatcher.group();
                        lastPrefix += tag.length();
                        tagStripped = true;
                    }
                } else if (lastPrefix < subject.length() - 1 && subject.startsWith(tag, lastPrefix)) {
                    // Re: [foo] Re: [foo] blah blah blah
                    //               ^     ^
                    //               ^     ^
                    //               ^    new position
                    //               ^
                    //              initial position
                    lastPrefix += tag.length();
                    tagStripped = true;
                }
            }
        }
        // Null pointer check is to make the static analysis component of Eclipse happy.
        if (tagStripped && (tag != null)) {
            // restore the last tag
            lastPrefix -= tag.length();
        }
        if (lastPrefix > -1 && lastPrefix < subject.length() - 1) {
            return subject.substring(lastPrefix).trim();
        } else {
            return subject.trim();
        }
    }

    public static String stripNewLines(String multiLineString) {
        return multiLineString.replaceAll("[\\r\\n]", "");
    }


    private static final String IMG_SRC_REGEX = "(?is:<img[^>]+src\\s*=\\s*['\"]?([a-z]+)\\:)";
    private static final Pattern IMG_PATTERN = Pattern.compile(IMG_SRC_REGEX);
    /**
     * Figure out if this part has images.
     * TODO: should only return true if we're an html part
     * @param message Content to evaluate
     * @return True if it has external images; false otherwise.
     */
    public static boolean hasExternalImages(final String message) {
        Matcher imgMatches = IMG_PATTERN.matcher(message);
        while (imgMatches.find()) {
            String uriScheme = imgMatches.group(1);
            if (uriScheme.equals("http") || uriScheme.equals("https")) {
                Timber.d("External images found");
                return true;
            }
        }

        Timber.d("No external images.");
        return false;
    }

    /**
     * Unconditionally close a Cursor.  Equivalent to {@link Cursor#close()},
     * if cursor is non-null.  This is typically used in finally blocks.
     *
     * @param cursor cursor to close
     */
    public static void closeQuietly(final Cursor cursor) {
        if (cursor != null) {
            cursor.close();
        }
    }

    /**
     * Check to see if we have network connectivity.
     */
    public static boolean hasConnectivity(final Context context) {
        final ConnectivityManager connectivityManager =
            (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            return false;
        }
        final NetworkInfo netInfo = connectivityManager.getActiveNetworkInfo();
        if (netInfo != null && netInfo.getState() == NetworkInfo.State.CONNECTED) {
            return true;
        } else {
            return false;
        }
    }

    private static final Pattern MESSAGE_ID = Pattern.compile("<" +
            "(?:" +
                "[a-zA-Z0-9!#$%&'*+\\-/=?^_`{|}~]+" +
                "(?:\\.[a-zA-Z0-9!#$%&'*+\\-/=?^_`{|}~]+)*" +
                "|" +
                "\"(?:[^\\\\\"]|\\\\.)*\"" +
            ")" +
            "@" +
            "(?:" +
                "[a-zA-Z0-9!#$%&'*+\\-/=?^_`{|}~]+" +
                "(?:\\.[a-zA-Z0-9!#$%&'*+\\-/=?^_`{|}~]+)*" +
                "|" +
                "\\[(?:[^\\\\\\]]|\\\\.)*\\]" +
            ")" +
            ">");

    public static List<String> extractMessageIds(final String text) {
        List<String> messageIds = new ArrayList<>();
        Matcher matcher = MESSAGE_ID.matcher(text);

        int start = 0;
        while (matcher.find(start)) {
            String messageId = text.substring(matcher.start(), matcher.end());
            messageIds.add(messageId);
            start = matcher.end();
        }

        return messageIds;
    }

    public static String extractMessageId(final String text) {
        Matcher matcher = MESSAGE_ID.matcher(text);

        if (matcher.find()) {
            return text.substring(matcher.start(), matcher.end());
        }

        return null;
    }

    /**
     * @return a {@link Handler} tied to the main thread.
     */
    public static Handler getMainThreadHandler() {
        if (sMainThreadHandler == null) {
            // No need to synchronize -- it's okay to create an extra Handler, which will be used
            // only once and then thrown away.
            sMainThreadHandler = new Handler(Looper.getMainLooper());
        }
        return sMainThreadHandler;
    }
}
