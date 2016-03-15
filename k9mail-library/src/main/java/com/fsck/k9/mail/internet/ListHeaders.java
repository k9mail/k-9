package com.fsck.k9.mail.internet;

import android.net.MailTo;
import android.util.Log;

import com.fsck.k9.mail.Address;
import com.fsck.k9.mail.K9MailLib;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.MessagingException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intended to cover:
 *
 * RFC 2369
 * The Use of URLs as Meta-Syntax for Core Mail List Commands
 * and their Transport through Message Header Fields
 * https://www.ietf.org/rfc/rfc2369.txt
 *
 * This is the following fields:
 *
 * List-Help
 * List-Subscribe
 * List-Unsubscribe
 * List-Post
 * List-Owner
 * List-Archive
 *
 * Currently only provides a utility method for List-Post
 **/
public class ListHeaders {
    public static final String LIST_POST_HEADER = "List-Post";
    private static final Pattern mailtoContainerPattern = Pattern.compile("<(.+)>");

    public static Address[] getListPostAddresses(MimeMessage message) {
        String[] headerValues = new String[0];
        try {
            headerValues = message.getHeader(LIST_POST_HEADER);
        } catch (MessagingException e) {
            Log.e(K9MailLib.LOG_TAG, "Unable to parse list-post header", e);
        }
        if (headerValues.length < 1) {
            return new Address[0];
        }
        List<Address> listPostAddresses = new ArrayList<>();
        for (String headerValue : headerValues) {
            if (headerValue == null || headerValues[0].isEmpty()) {
                continue;
            }
            Matcher m = mailtoContainerPattern.matcher(headerValue);
            if (!m.find()) {
                continue;
            }
            String mailToUri = m.group(1);
            String emailAddress = MailTo.parse(mailToUri).getTo();
            listPostAddresses.add(new Address(emailAddress));
        }
        return listPostAddresses.toArray(new Address[listPostAddresses.size()]);
    }
}
