package com.fsck.k9.mailstore;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;

import android.support.annotation.NonNull;

import com.fsck.k9.mail.internet.SizeAware;


/**
 * An attachment whose contents are contained in a file.
 */
public class TempFileBody extends BinaryAttachmentBody implements SizeAware {
    private final File file;

    public TempFileBody(String filename) {
        file = new File(filename);
    }

    @Override
    @NonNull
    public InputStream getInputStream() {
        try {
            return new FileInputStream(file);
        } catch (FileNotFoundException e) {
            return new ByteArrayInputStream(LocalStore.EMPTY_BYTE_ARRAY);
        }
    }

    @Override
    public long getSize() {
        return file.length();
    }
}
