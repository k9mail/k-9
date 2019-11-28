package com.fsck.k9.helper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import com.fsck.k9.Account;
import com.fsck.k9.message.Attachment;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

import timber.log.Timber;

public class ImageResizer {

    private final Context context;

    public ImageResizer(Context context) {
        this.context = context;
    }

    /**
     * Loop over the attachments and resize the attached image if resizing is enabled.
     * @param attachments the attachment list.
     */
    public void createAttachmentListWithResizedImages(List<? extends Attachment> attachments) {
        for (Attachment attachment : attachments) {
            if (attachment.getResizeImagesEnabled() && isImage(context, attachment.getUri())) {
                resizeImageAttachment(attachment);
            }
        }
    }


    /**
     * Resize the given attachment.
     * @param attachment the attachment.
     */
    private void resizeImageAttachment(Attachment attachment) {
        if (attachment.getFileName() == null)
            return;

        int circumference = attachment.getResizeImageCircumference();
        int quality = attachment.getResizeImageQuality();

        // read image dimension
        Bitmap bitmap = BitmapFactory.decodeFile(attachment.getFileName());

        // calculate new dimension
        int newWidth;
        int newHeight;
        float factor = (bitmap.getWidth() + bitmap.getHeight() + 0f) / circumference;
        if (factor <= 1.0f) {
            newWidth = bitmap.getWidth();
            newHeight = bitmap.getHeight();
        } else {
            newWidth = (int) (bitmap.getWidth() / factor);
            newHeight = (int) (bitmap.getHeight() / factor);

            while (newWidth + newHeight < circumference) {
                if ((0f + bitmap.getWidth()) / newWidth >= (0f + bitmap.getHeight()) / newHeight)
                    ++newWidth;
                else
                    ++newHeight;
            }
        }

        // resize the image
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
        FileOutputStream out = null;
        File attachmentFile = new File(attachment.getFileName());
        File tempFile = new File(attachmentFile.getParentFile(), attachmentFile.getName() + "." + System.currentTimeMillis());
        try {
            // and apply the JPEG setting.
            out = new FileOutputStream(tempFile);
            resized.compress(Bitmap.CompressFormat.JPEG, quality, out);

            // successful written -> update the attachment
            attachment.setFileName(tempFile.getAbsolutePath());
            attachment.setSize(tempFile.length());

            // erase the old attachment file
            tempFile = attachmentFile;
        } catch (IOException ioe) {
            Timber.i("image resizing failed for " + attachment.getUri() + " circumference=" + circumference + ", quality=" + quality);
        } finally {
            IOUtils.closeQuietly(out);
            FileUtils.deleteQuietly(tempFile);
        }
    }

    /* simple helper methods. */

    /**
     * Return true if the uri belongs to an image attachment.
     * @param context the application context.
     * @param uri the attachment's uri.
     * @return true if the uri belongs to an image attachment.
     */
    public static boolean isImage(Context context, Uri uri) {
        if (uri == null)
            return false;
        String mimeType = context.getContentResolver().getType(uri);
        return mimeType != null && mimeType.toLowerCase(Locale.ROOT).startsWith("image/");
    }


    /**
     * Convert the given String to an integer and ensure that the value is not to small.
     * Invalid values are replaced wit the default value Account.DEFAULT_RESIZE_IMAGE_CIRCUMFERENCE.
     * @param resizeCircumferenceS the circumference as String.
     * @return the circumference int value.
     */
    public static int convertResizeImageCircumference(String resizeCircumferenceS) {
        int resizeCircumference = Account.DEFAULT_RESIZE_IMAGE_CIRCUMFERENCE;
        try {
            resizeCircumference = Integer.parseInt(resizeCircumferenceS);
            if (resizeCircumference < 520)
                resizeCircumference = 520;
        } catch (NumberFormatException ex){
            // ignore
        }
        return resizeCircumference;
    }

    /**
     * Convert the given String to an integer and ensure that the value is within the bounds.
     * Invalid values are replaced wit the default value Account.DEFAULT_RESIZE_IMAGE_QUALITY.
     * @param resizeQualityS the quality as String.
     * @return the quality int value.
     */
    public static int convertResizeImageQuality(String resizeQualityS) {
        int resizeQuality = Account.DEFAULT_RESIZE_IMAGE_QUALITY;
        try {
            resizeQuality = Integer.parseInt(resizeQualityS);
            if (resizeQuality < 10)
                resizeQuality = 10;
            else if (resizeQuality > 100)
                resizeQuality = 100;
        } catch (NumberFormatException ex){
            // ignore
        }
        return resizeQuality;
    }

}
