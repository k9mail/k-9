package com.fsck.k9.mail.store.imap;


import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.security.cert.CertificateException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import com.fsck.k9.mail.Authentication;
import com.fsck.k9.mail.AuthenticationFailedException;
import com.fsck.k9.mail.CertificateValidationException;
import com.fsck.k9.mail.ConnectionSecurity;
import com.fsck.k9.mail.K9MailLib;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.NetworkType;
import com.fsck.k9.mail.filter.Base64;
import com.fsck.k9.mail.filter.PeekableInputStream;
import com.fsck.k9.mail.oauth.OAuth2TokenProvider;
import com.fsck.k9.mail.oauth.XOAuth2ChallengeParser;
import com.fsck.k9.mail.ssl.TrustedSocketFactory;
import com.jcraft.jzlib.JZlib;
import com.jcraft.jzlib.ZOutputStream;
import javax.net.ssl.SSLException;
import org.apache.commons.io.IOUtils;
import timber.log.Timber;

import static com.fsck.k9.mail.ConnectionSecurity.STARTTLS_REQUIRED;
import static com.fsck.k9.mail.K9MailLib.DEBUG_PROTOCOL_IMAP;
import static com.fsck.k9.mail.store.RemoteStore.SOCKET_CONNECT_TIMEOUT;
import static com.fsck.k9.mail.store.RemoteStore.SOCKET_READ_TIMEOUT;
import static com.fsck.k9.mail.store.imap.ImapResponseParser.equalsIgnoreCase;


/**
 * A cacheable class that stores the details for a single IMAP connection.
 */
class ImapConnection {
    private static final int BUFFER_SIZE = 1024;


    private final ConnectivityManager connectivityManager;
    private final OAuth2TokenProvider oauthTokenProvider;
    private final TrustedSocketFactory socketFactory;
    private final int socketConnectTimeout;
    private final int socketReadTimeout;

    private Socket socket;
    private PeekableInputStream inputStream;
    private OutputStream outputStream;
    private ImapResponseParser responseParser;
    private int nextCommandTag;
    private Set<String> capabilities = new HashSet<String>();
    private ImapSettings settings;
    private Exception stacktraceForClose;
    private boolean open = false;
    private boolean retryXoauth2WithNewToken = true;


    public ImapConnection(ImapSettings settings, TrustedSocketFactory socketFactory,
            ConnectivityManager connectivityManager, OAuth2TokenProvider oauthTokenProvider) {
        this.settings = settings;
        this.socketFactory = socketFactory;
        this.connectivityManager = connectivityManager;
        this.oauthTokenProvider = oauthTokenProvider;
        this.socketConnectTimeout = SOCKET_CONNECT_TIMEOUT;
        this.socketReadTimeout = SOCKET_READ_TIMEOUT;
    }

    ImapConnection(ImapSettings settings, TrustedSocketFactory socketFactory,
            ConnectivityManager connectivityManager, OAuth2TokenProvider oauthTokenProvider,
            int socketConnectTimeout, int socketReadTimeout) {
        this.settings = settings;
        this.socketFactory = socketFactory;
        this.connectivityManager = connectivityManager;
        this.oauthTokenProvider = oauthTokenProvider;
        this.socketConnectTimeout = socketConnectTimeout;
        this.socketReadTimeout = socketReadTimeout;
    }

    public void open() throws IOException, MessagingException {
        if (open) {
            return;
        } else if (stacktraceForClose != null) {
            throw new IllegalStateException("open() called after close(). " +
                    "Check wrapped exception to see where close() was called.", stacktraceForClose);
        }

        open = true;
        boolean authSuccess = false;
        nextCommandTag = 1;

        adjustDNSCacheTTL();

        try {
            socket = connect();
            configureSocket();
            setUpStreamsAndParserFromSocket();

            readInitialResponse();
            requestCapabilitiesIfNecessary();

            upgradeToTlsIfNecessary();

            List<ImapResponse> responses = authenticate();
            authSuccess = true;

            extractOrRequestCapabilities(responses);

            enableCompressionIfRequested();

            retrievePathPrefixIfNecessary();
            retrievePathDelimiterIfNecessary();

        } catch (SSLException e) {
            handleSslException(e);
        } catch (ConnectException e) {
            handleConnectException(e);
        } catch (GeneralSecurityException e) {
            throw new MessagingException("Unable to open connection to IMAP server due to security error.", e);
        } finally {
            if (!authSuccess) {
                Timber.e("Failed to login, closing connection for %s", getLogId());
                close();
            }
        }
    }

    private void handleSslException(SSLException e) throws CertificateValidationException, SSLException {
        if (e.getCause() instanceof CertificateException) {
            throw new CertificateValidationException(e.getMessage(), e);
        } else {
            throw e;
        }
    }

    private void handleConnectException(ConnectException e) throws ConnectException {
        String message = e.getMessage();
        String[] tokens = message.split("-");

        if (tokens.length > 1 && tokens[1] != null) {
            Timber.e(e, "Stripping host/port from ConnectionException for %s", getLogId());
            throw new ConnectException(tokens[1].trim());
        } else {
            throw e;
        }
    }

    public boolean isConnected() {
        return inputStream != null && outputStream != null && socket != null &&
                socket.isConnected() && !socket.isClosed();
    }

    private void adjustDNSCacheTTL() {
        try {
            Security.setProperty("networkaddress.cache.ttl", "0");
        } catch (Exception e) {
            Timber.w(e, "Could not set DNS ttl to 0 for %s", getLogId());
        }

        try {
            Security.setProperty("networkaddress.cache.negative.ttl", "0");
        } catch (Exception e) {
            Timber.w(e, "Could not set DNS negative ttl to 0 for %s", getLogId());
        }
    }

    private Socket connect() throws GeneralSecurityException, MessagingException, IOException {
        Exception connectException = null;

        InetAddress[] inetAddresses = InetAddress.getAllByName(settings.getHost());
        for (InetAddress address : inetAddresses) {
            try {
                return connectToAddress(address);
            } catch (IOException e) {
                Timber.w(e, "Could not connect to %s", address);
                connectException = e;
            }
        }

        throw new MessagingException("Cannot connect to host", connectException);
    }

    private Socket connectToAddress(InetAddress address) throws NoSuchAlgorithmException, KeyManagementException,
            MessagingException, IOException {

        String host = settings.getHost();
        int port = settings.getPort();
        String clientCertificateAlias = settings.getClientCertificateAlias();

        if (K9MailLib.isDebug() && DEBUG_PROTOCOL_IMAP) {
            Timber.d("Connecting to %s as %s", host, address);
        }

        SocketAddress socketAddress = new InetSocketAddress(address, port);

        Socket socket;
        if (settings.getConnectionSecurity() == ConnectionSecurity.SSL_TLS_REQUIRED) {
            socket = socketFactory.createSocket(null, host, port, clientCertificateAlias);
        } else {
            socket = new Socket();
        }

        socket.connect(socketAddress, socketConnectTimeout);

        return socket;
    }

    private void configureSocket() throws SocketException {
        socket.setSoTimeout(socketReadTimeout);
    }

    private void setUpStreamsAndParserFromSocket() throws IOException {
        setUpStreamsAndParser(socket.getInputStream(), socket.getOutputStream());
    }

    private void setUpStreamsAndParser(InputStream input, OutputStream output) {
        inputStream = new PeekableInputStream(new BufferedInputStream(input, BUFFER_SIZE));
        responseParser = new ImapResponseParser(inputStream);
        outputStream = new BufferedOutputStream(output, BUFFER_SIZE);
    }

    private void readInitialResponse() throws IOException {
        ImapResponse initialResponse = responseParser.readResponse();
        if (K9MailLib.isDebug() && DEBUG_PROTOCOL_IMAP) {
            Timber.v("%s <<< %s", getLogId(), initialResponse);
        }
        extractCapabilities(Collections.singletonList(initialResponse));
    }

    private List<ImapResponse> extractCapabilities(List<ImapResponse> responses) {
        CapabilityResponse capabilityResponse = CapabilityResponse.parse(responses);
        if (capabilityResponse != null) {
            Set<String> receivedCapabilities = capabilityResponse.getCapabilities();
            if (K9MailLib.isDebug()) {
                Timber.d("Saving %s capabilities for %s", receivedCapabilities, getLogId());
            }
            capabilities = receivedCapabilities;
        }
        return responses;
    }

    private List<ImapResponse> extractOrRequestCapabilities(List<ImapResponse> responses)
            throws IOException, MessagingException {
        CapabilityResponse capabilityResponse = CapabilityResponse.parse(responses);
        if (capabilityResponse != null) {
            Set<String> receivedCapabilities = capabilityResponse.getCapabilities();
            Timber.d("Saving %s capabilities for %s", receivedCapabilities, getLogId());
            capabilities = receivedCapabilities;
        } else {
            Timber.i("Did not get capabilities in post-auth banner, requesting CAPABILITY for %s", getLogId());
            requestCapabilities();
        }

        return responses;
    }

    private void requestCapabilitiesIfNecessary() throws IOException, MessagingException {
        if (!capabilities.isEmpty()) {
            return;
        }
        if (K9MailLib.isDebug()) {
            Timber.i("Did not get capabilities in banner, requesting CAPABILITY for %s", getLogId());
        }
        requestCapabilities();
    }

    private void requestCapabilities() throws IOException, MessagingException {
        List<ImapResponse> responses = extractCapabilities(executeSimpleCommand(Commands.CAPABILITY));
        if (responses.size() != 2) {
            throw new MessagingException("Invalid CAPABILITY response received");
        }
    }

    private void upgradeToTlsIfNecessary() throws IOException, MessagingException, GeneralSecurityException {
        if (settings.getConnectionSecurity() == STARTTLS_REQUIRED) {
            upgradeToTls();
        }
    }

    private void upgradeToTls() throws IOException, MessagingException, GeneralSecurityException {
        if (!hasCapability(Capabilities.STARTTLS)) {
            /*
             * This exception triggers a "Certificate error"
             * notification that takes the user to the incoming
             * server settings for review. This might be needed if
             * the account was configured with an obsolete
             * "STARTTLS (if available)" setting.
             */
            throw new CertificateValidationException("STARTTLS connection security not available");
        }

        startTLS();
    }

    private void startTLS() throws IOException, MessagingException, GeneralSecurityException {
        executeSimpleCommand(Commands.STARTTLS);

        String host = settings.getHost();
        int port = settings.getPort();
        String clientCertificateAlias = settings.getClientCertificateAlias();

        socket = socketFactory.createSocket(socket, host, port, clientCertificateAlias);
        configureSocket();
        setUpStreamsAndParserFromSocket();

        // Per RFC 2595 (3.1):  Once TLS has been started, reissue CAPABILITY command
        if (K9MailLib.isDebug()) {
            Timber.i("Updating capabilities after STARTTLS for %s", getLogId());
        }

        requestCapabilities();
    }

    private List<ImapResponse> authenticate() throws MessagingException, IOException {
        switch (settings.getAuthType()) {
            case XOAUTH2:
                if (oauthTokenProvider == null) {
                    throw new MessagingException("No OAuthToken Provider available.");
                } else if (hasCapability(Capabilities.AUTH_XOAUTH2) && hasCapability(Capabilities.SASL_IR)) {
                    return authXoauth2withSASLIR();
                } else {
                    throw new MessagingException("Server doesn't support SASL XOAUTH2.");
                }
            case CRAM_MD5: {
                if (hasCapability(Capabilities.AUTH_CRAM_MD5)) {
                    return authCramMD5();
                } else {
                    throw new MessagingException("Server doesn't support encrypted passwords using CRAM-MD5.");
                }
            }
            case PLAIN: {
                if (hasCapability(Capabilities.AUTH_PLAIN)) {
                    return saslAuthPlainWithLoginFallback();
                } else if (!hasCapability(Capabilities.LOGINDISABLED)) {
                    return login();
                } else {
                    throw new MessagingException("Server doesn't support unencrypted passwords using AUTH=PLAIN " +
                            "and LOGIN is disabled.");
                }
            }
            case EXTERNAL: {
                if (hasCapability(Capabilities.AUTH_EXTERNAL)) {
                    return saslAuthExternal();
                } else {
                    // Provide notification to user of a problem authenticating using client certificates
                    throw new CertificateValidationException(CertificateValidationException.Reason.MissingCapability);
                }
            }
            default: {
                throw new MessagingException("Unhandled authentication method found in the server settings (bug).");
            }
        }
    }

    private List<ImapResponse> authXoauth2withSASLIR() throws IOException, MessagingException {
        retryXoauth2WithNewToken = true;
        try {
            return attemptXOAuth2();
        } catch (NegativeImapResponseException e) {
            //TODO: Check response code so we don't needlessly invalidate the token.
            oauthTokenProvider.invalidateToken(settings.getUsername());

            if (!retryXoauth2WithNewToken) {
                throw handlePermanentXoauth2Failure(e);
            } else {
                return handleTemporaryXoauth2Failure(e);
            }
        }
    }

    private AuthenticationFailedException handlePermanentXoauth2Failure(NegativeImapResponseException e) {
        Timber.v(e, "Permanent failure during XOAUTH2");
        return new AuthenticationFailedException(e.getMessage(), e);
    }

    private List<ImapResponse> handleTemporaryXoauth2Failure(NegativeImapResponseException e) throws IOException, MessagingException {
        //We got a response indicating a retry might suceed after token refresh
        //We could avoid this if we had a reasonable chance of knowing
        //if a token was invalid before use (e.g. due to expiry). But we don't
        //This is the intended behaviour per AccountManager

        Timber.v(e, "Temporary failure - retrying with new token");
        try {
            return attemptXOAuth2();
        } catch (NegativeImapResponseException e2) {
            //Okay, we failed on a new token.
            //Invalidate the token anyway but assume it's permanent.
            Timber.v(e, "Authentication exception for new token, permanent error assumed");
            oauthTokenProvider.invalidateToken(settings.getUsername());
            throw handlePermanentXoauth2Failure(e2);
        }
    }

    private List<ImapResponse> attemptXOAuth2() throws MessagingException, IOException {
        String token = oauthTokenProvider.getToken(settings.getUsername(), OAuth2TokenProvider.OAUTH2_TIMEOUT);
        String authString = Authentication.computeXoauth(settings.getUsername(), token);
        String tag = sendSaslIrCommand(Commands.AUTHENTICATE_XOAUTH2, authString, true);

        return responseParser.readStatusResponse(tag, Commands.AUTHENTICATE_XOAUTH2, getLogId(),
                new UntaggedHandler() {
                    @Override
                    public void handleAsyncUntaggedResponse(ImapResponse response) throws IOException {
                        handleXOAuthUntaggedResponse(response);
                    }
                });
    }

    private void handleXOAuthUntaggedResponse(ImapResponse response) throws IOException {
        if (response.isString(0)) {
            retryXoauth2WithNewToken = XOAuth2ChallengeParser.shouldRetry(response.getString(0), settings.getHost());
        }

        if (response.isContinuationRequested()) {
            outputStream.write("\r\n".getBytes());
            outputStream.flush();
        }
    }

    private List<ImapResponse> authCramMD5() throws MessagingException, IOException {
        String command = Commands.AUTHENTICATE_CRAM_MD5;
        String tag = sendCommand(command, false);

        ImapResponse response = readContinuationResponse(tag);
        if (response.size() != 1 || !(response.get(0) instanceof String)) {
            throw new MessagingException("Invalid Cram-MD5 nonce received");
        }

        byte[] b64Nonce = response.getString(0).getBytes();
        byte[] b64CRAM = Authentication.computeCramMd5Bytes(settings.getUsername(), settings.getPassword(), b64Nonce);

        outputStream.write(b64CRAM);
        outputStream.write('\r');
        outputStream.write('\n');
        outputStream.flush();

        try {
            return responseParser.readStatusResponse(tag, command, getLogId(), null);
        } catch (NegativeImapResponseException e) {
            throw handleAuthenticationFailure(e);
        }
    }

    private List<ImapResponse> saslAuthPlainWithLoginFallback() throws IOException, MessagingException {
        try {
            return saslAuthPlain();
        } catch (AuthenticationFailedException e) {
            if (!isConnected()) {
                throw e;
            }
            
            return login();
        }
    }

    private List<ImapResponse> saslAuthPlain() throws IOException, MessagingException {
        String command = Commands.AUTHENTICATE_PLAIN;
        String tag = sendCommand(command, false);

        readContinuationResponse(tag);

        String credentials = "\000" + settings.getUsername() + "\000" + settings.getPassword();
        byte[] encodedCredentials = Base64.encodeBase64(credentials.getBytes());

        outputStream.write(encodedCredentials);
        outputStream.write('\r');
        outputStream.write('\n');
        outputStream.flush();

        try {
            return responseParser.readStatusResponse(tag, command, getLogId(), null);
        } catch (NegativeImapResponseException e) {
            throw handleAuthenticationFailure(e);
        }
    }

    private List<ImapResponse> login() throws IOException, MessagingException {
        /*
         * Use quoted strings which permit spaces and quotes. (Using IMAP
         * string literals would be better, but some servers are broken
         * and don't parse them correctly.)
         */

        // escape double-quotes and backslash characters with a backslash
        Pattern p = Pattern.compile("[\\\\\"]");
        String replacement = "\\\\$0";
        String username = p.matcher(settings.getUsername()).replaceAll(replacement);
        String password = p.matcher(settings.getPassword()).replaceAll(replacement);

        try {
            String command = String.format(Commands.LOGIN + " \"%s\" \"%s\"", username, password);
            return executeSimpleCommand(command, true);
        } catch (NegativeImapResponseException e) {
            throw handleAuthenticationFailure(e);
        }
    }

    private List<ImapResponse> saslAuthExternal() throws IOException, MessagingException {
        try {
            String command = Commands.AUTHENTICATE_EXTERNAL + " " + Base64.encode(settings.getUsername());
            return executeSimpleCommand(command, false);
        } catch (NegativeImapResponseException e) {
            /*
             * Provide notification to the user of a problem authenticating
             * using client certificates. We don't use an
             * AuthenticationFailedException because that would trigger a
             * "Username or password incorrect" notification in
             * AccountSetupCheckSettings.
             */
            throw new CertificateValidationException(e.getMessage());
        }
    }

    private MessagingException handleAuthenticationFailure(NegativeImapResponseException e) {
        ImapResponse lastResponse = e.getLastResponse();
        String responseCode = ResponseCodeExtractor.getResponseCode(lastResponse);

        // If there's no response code we simply assume it was an authentication failure.
        if (responseCode == null || responseCode.equals(ResponseCodeExtractor.AUTHENTICATION_FAILED)) {
            if (e.wasByeResponseReceived()) {
                close();
            }

            return new AuthenticationFailedException(e.getMessage());
        } else {
            close();
            return e;
        }
    }

    private void enableCompressionIfRequested() throws IOException, MessagingException {
        if (hasCapability(Capabilities.COMPRESS_DEFLATE) && shouldEnableCompression()) {
            enableCompression();
        }
    }

    private boolean shouldEnableCompression() {
        boolean useCompression = true;

        NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
        if (networkInfo != null) {
            int type = networkInfo.getType();
            if (K9MailLib.isDebug()) {
                Timber.d("On network type %s", type);
            }

            NetworkType networkType = NetworkType.fromConnectivityManagerType(type);
            useCompression = settings.useCompression(networkType);
        }

        if (K9MailLib.isDebug()) {
            Timber.d("useCompression: %b", useCompression);
        }

        return useCompression;
    }

    private void enableCompression() throws IOException, MessagingException {
        try {
            executeSimpleCommand(Commands.COMPRESS_DEFLATE);
        } catch (NegativeImapResponseException e) {
            Timber.d(e, "Unable to negotiate compression: ");
            return;
        }

        try {
            InflaterInputStream input = new InflaterInputStream(socket.getInputStream(), new Inflater(true));
            ZOutputStream output = new ZOutputStream(socket.getOutputStream(), JZlib.Z_BEST_SPEED, true);
            output.setFlushMode(JZlib.Z_PARTIAL_FLUSH);

            setUpStreamsAndParser(input, output);

            if (K9MailLib.isDebug()) {
                Timber.i("Compression enabled for %s", getLogId());
            }
        } catch (IOException e) {
            close();
            Timber.e(e, "Error enabling compression");
        }
    }

    private void retrievePathPrefixIfNecessary() throws IOException, MessagingException {
        if (settings.getPathPrefix() != null) {
            return;
        }

        if (hasCapability(Capabilities.NAMESPACE)) {
            if (K9MailLib.isDebug()) {
                Timber.i("pathPrefix is unset and server has NAMESPACE capability");
            }
            handleNamespace();
        } else {
            if (K9MailLib.isDebug()) {
                Timber.i("pathPrefix is unset but server does not have NAMESPACE capability");
            }
            settings.setPathPrefix("");
        }
    }

    private void handleNamespace() throws IOException, MessagingException {
        List<ImapResponse> responses = executeSimpleCommand(Commands.NAMESPACE);

        NamespaceResponse namespaceResponse = NamespaceResponse.parse(responses);
        if (namespaceResponse != null) {
            String prefix = namespaceResponse.getPrefix();
            String hierarchyDelimiter = namespaceResponse.getHierarchyDelimiter();

            settings.setPathPrefix(prefix);
            settings.setPathDelimiter(hierarchyDelimiter);
            settings.setCombinedPrefix(null);

            if (K9MailLib.isDebug()) {
                Timber.d("Got path '%s' and separator '%s'", prefix, hierarchyDelimiter);
            }
        }
    }

    private void retrievePathDelimiterIfNecessary() throws IOException, MessagingException {
        if (settings.getPathDelimiter() == null) {
            retrievePathDelimiter();
        }
    }

    private void retrievePathDelimiter() throws IOException, MessagingException {
        List<ImapResponse> listResponses;
        try {
            listResponses = executeSimpleCommand(Commands.LIST + " \"\" \"\"");
        } catch (NegativeImapResponseException e) {
            Timber.d(e, "Error getting path delimiter using LIST command");
            return;
        }

        for (ImapResponse response : listResponses) {
            if (isListResponse(response)) {
                String hierarchyDelimiter = response.getString(2);
                settings.setPathDelimiter(hierarchyDelimiter);
                settings.setCombinedPrefix(null);

                if (K9MailLib.isDebug()) {
                    Timber.d("Got path delimiter '%s' for %s", settings.getPathDelimiter(), getLogId());
                }

                break;
            }
        }
    }

    private boolean isListResponse(ImapResponse response) {
        boolean responseTooShort = response.size() < 4;
        if (responseTooShort) {
            return false;
        }

        boolean isListResponse = equalsIgnoreCase(response.get(0), Responses.LIST);
        boolean hierarchyDelimiterValid = response.get(2) instanceof String;

        return isListResponse && hierarchyDelimiterValid;
    }

    protected boolean hasCapability(String capability) {
        return capabilities.contains(capability.toUpperCase(Locale.US));
    }

    protected boolean isIdleCapable() {
        if (K9MailLib.isDebug()) {
            Timber.v("Connection %s has %d capabilities", getLogId(), capabilities.size());
        }

        return capabilities.contains(Capabilities.IDLE);
    }

    boolean isMultiappendCapable() {
        return capabilities.contains(Capabilities.MULTIAPPEND);
    }

    public void close() {
        open = false;
        stacktraceForClose = new Exception();

        IOUtils.closeQuietly(inputStream);
        IOUtils.closeQuietly(outputStream);
        IOUtils.closeQuietly(socket);

        inputStream = null;
        outputStream = null;
        socket = null;
    }

    public OutputStream getOutputStream() {
        return outputStream;
    }

    protected String getLogId() {
        return "conn" + hashCode();
    }

    public List<ImapResponse> executeSimpleCommand(String command) throws IOException, MessagingException {
        return executeSimpleCommand(command, false);
    }

    public List<ImapResponse> executeSimpleCommand(String command, boolean sensitive) throws IOException,
            MessagingException {
        String commandToLog = command;

        if (sensitive && !K9MailLib.isDebugSensitive()) {
            commandToLog = "*sensitive*";
        }

        String tag = sendCommand(command, sensitive);

        try {
            return responseParser.readStatusResponse(tag, commandToLog, getLogId(), null);
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    public List<ImapResponse> readStatusResponse(String tag, String commandToLog, UntaggedHandler untaggedHandler)
            throws IOException, NegativeImapResponseException {
        return responseParser.readStatusResponse(tag, commandToLog, getLogId(), untaggedHandler);
    }

    public String sendSaslIrCommand(String command, String initialClientResponse, boolean sensitive)
            throws IOException, MessagingException {
        try {
            open();

            String tag = Integer.toString(nextCommandTag++);
            String commandToSend = tag + " " + command + " " + initialClientResponse + "\r\n";
            outputStream.write(commandToSend.getBytes());
            outputStream.flush();

            if (K9MailLib.isDebug() && DEBUG_PROTOCOL_IMAP) {
                if (sensitive && !K9MailLib.isDebugSensitive()) {
                    Timber.v("%s>>> [Command Hidden, Enable Sensitive Debug Logging To Show]", getLogId());
                } else {
                    Timber.v("%s>>> %s %s %s", getLogId(), tag, command, initialClientResponse);
                }
            }

            return tag;
        } catch (IOException | MessagingException e) {
            close();
            throw e;
        }
    }

    public String sendCommand(String command, boolean sensitive) throws MessagingException, IOException {
        try {
            open();

            String tag = Integer.toString(nextCommandTag++);
            String commandToSend = tag + " " + command + "\r\n";
            outputStream.write(commandToSend.getBytes());
            outputStream.flush();

            if (K9MailLib.isDebug() && DEBUG_PROTOCOL_IMAP) {
                if (sensitive && !K9MailLib.isDebugSensitive()) {
                    Timber.v("%s>>> [Command Hidden, Enable Sensitive Debug Logging To Show]", getLogId());
                } else {
                    Timber.v("%s>>> %s %s", getLogId(), tag, command);
                }
            }

            return tag;
        } catch (IOException | MessagingException e) {
            close();
            throw e;
        }
    }

    public void sendContinuation(String continuation) throws IOException {
        outputStream.write(continuation.getBytes());
        outputStream.write('\r');
        outputStream.write('\n');
        outputStream.flush();

        if (K9MailLib.isDebug() && DEBUG_PROTOCOL_IMAP) {
            Timber.v("%s>>> %s", getLogId(), continuation);
        }
    }

    public ImapResponse readResponse() throws IOException, MessagingException {
        return readResponse(null);
    }

    public ImapResponse readResponse(ImapResponseCallback callback) throws IOException {
        try {
            ImapResponse response = responseParser.readResponse(callback);

            if (K9MailLib.isDebug() && DEBUG_PROTOCOL_IMAP) {
                Timber.v("%s<<<%s", getLogId(), response);
            }

            return response;
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    protected void setReadTimeout(int millis) throws SocketException {
        Socket sock = socket;
        if (sock != null) {
            sock.setSoTimeout(millis);
        }
    }

    private ImapResponse readContinuationResponse(String tag) throws IOException, MessagingException {
        ImapResponse response;
        do {
            response = readResponse();

            String responseTag = response.getTag();
            if (responseTag != null) {
                if (responseTag.equalsIgnoreCase(tag)) {
                    throw new MessagingException("Command continuation aborted: " + response);
                } else {
                    Timber.w("After sending tag %s, got tag response from previous command %s for %s",
                            tag, response, getLogId());
                }
            }
        } while (!response.isContinuationRequested());

        return response;
    }
}
