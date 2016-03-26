package com.fsck.k9.mail.ssl;

import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.ProxySettings;

import java.io.IOException;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;

public interface TrustedSocketFactory {
    Socket createSocket(Socket socket, String host, int port, String clientCertificateAlias)
            throws NoSuchAlgorithmException, KeyManagementException, MessagingException, IOException;

    boolean isSecure(Socket socket);

    ProxySettings getProxySettings();
}
