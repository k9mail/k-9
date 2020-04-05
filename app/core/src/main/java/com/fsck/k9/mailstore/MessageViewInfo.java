package com.fsck.k9.mailstore;


import java.util.Collections;
import java.util.List;

import com.fsck.k9.mail.Body;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.Part;
import com.fsck.k9.mail.internet.MimeBodyPart;
import com.fsck.k9.mail.internet.TextBody;


public class MessageViewInfo {
    public final Message message;
    public final boolean isMessageIncomplete;
    public final Part rootPart;
    public final String subject;
    public final boolean isSubjectEncrypted;
    public final AttachmentResolver attachmentResolver;
    public final String text;
    public final String textPlainFormatted;
    public final CryptoResultAnnotation cryptoResultAnnotation;
    public final List<AttachmentViewInfo> attachments;
    public final String extraText;
    public final List<AttachmentViewInfo> extraAttachments;


    public MessageViewInfo(
            Message message, boolean isMessageIncomplete, Part rootPart,
            String subject, boolean isSubjectEncrypted,
            String text, String textPlainFormatted, List<AttachmentViewInfo> attachments,
            CryptoResultAnnotation cryptoResultAnnotation,
            AttachmentResolver attachmentResolver,
            String extraText, List<AttachmentViewInfo> extraAttachments) {
        this.message = message;
        this.isMessageIncomplete = isMessageIncomplete;
        this.rootPart = rootPart;
        this.subject = subject;
        this.isSubjectEncrypted = isSubjectEncrypted;
        this.text = text;
        this.textPlainFormatted = textPlainFormatted;
        this.cryptoResultAnnotation = cryptoResultAnnotation;
        this.attachmentResolver = attachmentResolver;
        this.attachments = attachments;
        this.extraText = extraText;
        this.extraAttachments = extraAttachments;
    }

    static MessageViewInfo createWithExtractedContent(Message message, Part rootPart, boolean isMessageIncomplete,
            String text, String textPlainFormatted, List<AttachmentViewInfo> attachments,
            AttachmentResolver attachmentResolver) {
        return new MessageViewInfo(
                message, isMessageIncomplete, rootPart, null, false, text, textPlainFormatted, attachments, null,
                attachmentResolver, null,
                Collections.<AttachmentViewInfo>emptyList());
    }

    public static MessageViewInfo createWithErrorState(Message message, boolean isMessageIncomplete) {
        try {
            Body emptyBody = new TextBody("");
            Part emptyPart = new MimeBodyPart(emptyBody, "text/plain");
            String subject = message.getSubject();
            return new MessageViewInfo(message, isMessageIncomplete, emptyPart, subject, false, null, null, null, null,
                    null, null, null);
        } catch (MessagingException e) {
            throw new AssertionError(e);
        }
    }

    public static MessageViewInfo createForMetadataOnly(Message message, boolean isMessageIncomplete) {
        return new MessageViewInfo(message, isMessageIncomplete, null, null, false, null, null, null, null, null, null, null);
    }

    MessageViewInfo withCryptoData(CryptoResultAnnotation rootPartAnnotation, String extraViewableText,
            List<AttachmentViewInfo> extraAttachmentInfos) {
        return new MessageViewInfo(
                message, isMessageIncomplete, rootPart, subject, isSubjectEncrypted, text, textPlainFormatted,
                attachments,
                rootPartAnnotation,
                attachmentResolver,
                extraViewableText, extraAttachmentInfos
        );
    }

    MessageViewInfo withSubject(String subject, boolean isSubjectEncrypted) {
        return new MessageViewInfo(
                message, isMessageIncomplete, rootPart, subject, isSubjectEncrypted, text, textPlainFormatted,
                attachments,
                cryptoResultAnnotation, attachmentResolver, extraText, extraAttachments
        );
    }
}
