package com.fsck.k9.mail.store.imap;

public class ImapConfig {
    private static ExtensionStatus extensionStatus = new DefaultImapExtensionStatus();

    static boolean shouldUseCondstore() {
        return extensionStatus.useCondstore();
    }

    static boolean shouldUseQresync() {
        return extensionStatus.useQresync();
    }

    public interface ExtensionStatus {
        boolean useCondstore();

        boolean useQresync();
    }

    public static void setExtensionStatus(ExtensionStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("status cannot be null");
        }
        extensionStatus = status;
    }

    private static class DefaultImapExtensionStatus implements ExtensionStatus {
        private boolean useCondstore = true;
        private boolean useQresync = true;

        @Override
        public boolean useCondstore() {
            return useCondstore;
        }

        @Override
        public boolean useQresync() {
            return useQresync;
        }
    }
}
