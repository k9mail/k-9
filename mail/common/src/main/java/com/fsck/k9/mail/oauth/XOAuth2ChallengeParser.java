package com.fsck.k9.mail.oauth;


import com.fsck.k9.mail.K9MailLib;
import com.fsck.k9.mail.filter.Base64;
import org.json.JSONException;
import org.json.JSONObject;
import timber.log.Timber;


/**
 * Parses Google's Error/Challenge responses See: https://developers.google.com/gmail/xoauth2_protocol#error_response
 */
public class XOAuth2ChallengeParser {
    public static final String BAD_RESPONSE = "400";


    public static boolean shouldRetry(String response, String host) {
        String decodedResponse = Base64.decode(response);

        if (K9MailLib.isDebug()) {
            Timber.v("Challenge response: %s", decodedResponse);
        }

        try {
            JSONObject json = new JSONObject(decodedResponse);
            String status = json.getString("status");
            if (!BAD_RESPONSE.equals(status)) {
                return false;
            }
        } catch (JSONException jsonException) {
            Timber.e("Error decoding JSON response from: %s. Response was: %s", host, decodedResponse);
        }

        return true;
    }
}
