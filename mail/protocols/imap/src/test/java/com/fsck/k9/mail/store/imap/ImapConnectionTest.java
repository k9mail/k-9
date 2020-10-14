package com.fsck.k9.mail.store.imap;


import java.io.IOException;
import java.net.UnknownHostException;
import java.util.List;

import android.net.ConnectivityManager;

import com.fsck.k9.mail.AuthType;
import com.fsck.k9.mail.AuthenticationFailedException;
import com.fsck.k9.mail.CertificateValidationException;
import com.fsck.k9.mail.CertificateValidationException.Reason;
import com.fsck.k9.mail.ConnectionSecurity;
import com.fsck.k9.mail.K9LibRobolectricTestRunner;
import com.fsck.k9.mail.K9MailLib;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.XOAuth2ChallengeParserTest;
import com.fsck.k9.mail.helpers.TestTrustedSocketFactory;
import com.fsck.k9.mail.oauth.OAuth2TokenProvider;
import com.fsck.k9.mail.ssl.TrustedSocketFactory;
import com.fsck.k9.mail.store.imap.mockserver.MockImapServer;
import okio.ByteString;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.shadows.ShadowLog;

import static org.hamcrest.core.StringContains.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;


@RunWith(K9LibRobolectricTestRunner.class)
public class ImapConnectionTest {
    private static final boolean DEBUGGING = false;

    private static final String USERNAME = "user";
    private static final String PASSWORD = "123456";
    private static final int SOCKET_CONNECT_TIMEOUT = 10000;
    private static final int SOCKET_READ_TIMEOUT = 10000;
    private static final String XOAUTH_TOKEN = "token";
    private static final String XOAUTH_ANOTHER_TOKEN = "token2";
    private static final String XOAUTH_STRING = ByteString.encodeUtf8(
            "user=" + USERNAME + "\001auth=Bearer " + XOAUTH_TOKEN + "\001\001").base64();
    private static final String XOAUTH_STRING_RETRY = ByteString.encodeUtf8(
            "user=" + USERNAME + "\001auth=Bearer " + XOAUTH_ANOTHER_TOKEN + "\001\001").base64();


    private TrustedSocketFactory socketFactory;
    private ConnectivityManager connectivityManager;
    private OAuth2TokenProvider oAuth2TokenProvider;
    private SimpleImapSettings settings;


    @Before
    public void setUp() throws Exception {
        connectivityManager = mock(ConnectivityManager.class);
        oAuth2TokenProvider = createOAuth2TokenProvider();
        socketFactory = TestTrustedSocketFactory.newInstance();

        settings = new SimpleImapSettings();
        settings.setUsername(USERNAME);
        settings.setPassword(PASSWORD);

        if (DEBUGGING) {
            ShadowLog.stream = System.out;
            K9MailLib.setDebug(true);
            K9MailLib.setDebugSensitive(true);
        }
    }

    @Test
    public void open_withNoCapabilitiesInInitialResponse_shouldIssuePreAuthCapabilitiesCommand() throws Exception {
        settings.setAuthType(AuthType.PLAIN);
        MockImapServer server = new MockImapServer();
        server.output("* OK example.org server");
        server.expect("1 CAPABILITY");
        server.output("* CAPABILITY IMAP4 IMAP4REV1 AUTH=PLAIN");
        server.output("1 OK CAPABILITY Completed");
        server.expect("2 AUTHENTICATE PLAIN");
        server.output("+");
        server.expect(ByteString.encodeUtf8("\000" + USERNAME + "\000" + PASSWORD).base64());
        server.output("2 OK Success");
        postAuthenticationDialogRequestingCapabilities(server);
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        imapConnection.open();

        server.verifyConnectionStillOpen();
        server.verifyInteractionCompleted();
    }

    @Test
    public void open_withCapabilitiesInInitialResponse_shouldNotIssuePreAuthCapabilitiesCommand() throws Exception {
        settings.setAuthType(AuthType.PLAIN);
        MockImapServer server = new MockImapServer();
        server.output("* OK [CAPABILITY IMAP4 IMAP4REV1 AUTH=PLAIN]");
        server.expect("1 AUTHENTICATE PLAIN");
        server.output("+");
        server.expect(ByteString.encodeUtf8("\000" + USERNAME + "\000" + PASSWORD).base64());
        server.output("1 OK Success");
        postAuthenticationDialogRequestingCapabilities(server, 2);
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        imapConnection.open();

        server.verifyConnectionStillOpen();
        server.verifyInteractionCompleted();
    }

    @Test
    public void open_authPlain() throws Exception {
        settings.setAuthType(AuthType.PLAIN);
        MockImapServer server = new MockImapServer();
        preAuthenticationDialog(server, "AUTH=PLAIN");
        server.expect("2 AUTHENTICATE PLAIN");
        server.output("+");
        server.expect(ByteString.encodeUtf8("\000" + USERNAME + "\000" + PASSWORD).base64());
        server.output("2 OK Success");
        postAuthenticationDialogRequestingCapabilities(server);
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        imapConnection.open();

        server.verifyConnectionStillOpen();
        server.verifyInteractionCompleted();
    }

    @Test
    public void open_afterCloseWasCalled_shouldThrow() throws Exception {
        settings.setAuthType(AuthType.PLAIN);
        MockImapServer server = new MockImapServer();
        preAuthenticationDialog(server);
        server.expect("2 LOGIN \"" + USERNAME + "\" \"" + PASSWORD + "\"");
        server.output("2 OK LOGIN completed");
        postAuthenticationDialogRequestingCapabilities(server);
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);
        imapConnection.open();
        imapConnection.close();

        try {
            imapConnection.open();
            fail("Expected exception");
        } catch (IllegalStateException e) {
            assertEquals("open() called after close(). Check wrapped exception to see where close() was called.",
                    e.getMessage());
        }
    }

    @Test
    public void open_authPlainWithLoginDisabled_shouldThrow() throws Exception {
        settings.setAuthType(AuthType.PLAIN);
        MockImapServer server = new MockImapServer();
        preAuthenticationDialog(server, "LOGINDISABLED");
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        try {
            imapConnection.open();
            fail("Expected exception");
        } catch (MessagingException e) {
            assertEquals("Server doesn't support unencrypted passwords using AUTH=PLAIN and LOGIN is disabled.",
                    e.getMessage());
        }

        server.verifyConnectionClosed();
        server.verifyInteractionCompleted();
    }

    @Test
    public void open_authPlainWithAuthenticationFailure_shouldFallbackToLogin() throws Exception {
        settings.setAuthType(AuthType.PLAIN);
        MockImapServer server = new MockImapServer();
        preAuthenticationDialog(server, "AUTH=PLAIN");
        server.expect("2 AUTHENTICATE PLAIN");
        server.output("+");
        server.expect(ByteString.encodeUtf8("\000" + USERNAME + "\000" + PASSWORD).base64());
        server.output("2 NO Login Failure");
        server.expect("3 LOGIN \"" + USERNAME + "\" \"" + PASSWORD + "\"");
        server.output("3 OK LOGIN completed");
        postAuthenticationDialogRequestingCapabilities(server, 4);
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        imapConnection.open();

        server.verifyConnectionStillOpen();
        server.verifyInteractionCompleted();
    }

    @Test
    public void open_authPlainAndLoginFallbackWithAuthenticationFailure_shouldThrow() throws Exception {
        settings.setAuthType(AuthType.PLAIN);
        MockImapServer server = new MockImapServer();
        preAuthenticationDialog(server, "AUTH=PLAIN");
        server.expect("2 AUTHENTICATE PLAIN");
        server.output("+");
        server.expect(ByteString.encodeUtf8("\000" + USERNAME + "\000" + PASSWORD).base64());
        server.output("2 NO Login Failure");
        server.expect("3 LOGIN \"" + USERNAME + "\" \"" + PASSWORD + "\"");
        server.output("3 NO Go away");
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        try {
            imapConnection.open();
            fail("Expected exception");
        } catch (AuthenticationFailedException e) {
            //FIXME: improve exception message
            assertThat(e.getMessage(), containsString("Go away"));
        }

        server.verifyConnectionClosed();
        server.verifyInteractionCompleted();
    }

    @Test
    public void open_authPlainFailureAndDisconnect_shouldThrow() throws Exception {
        settings.setAuthType(AuthType.PLAIN);
        MockImapServer server = new MockImapServer();
        preAuthenticationDialog(server, "AUTH=PLAIN");
        server.expect("2 AUTHENTICATE PLAIN");
        server.output("+");
        server.expect(ByteString.encodeUtf8("\000" + USERNAME + "\000" + PASSWORD).base64());
        server.output("2 NO [UNAVAILABLE] Maximum number of connections from user+IP exceeded");
        server.closeConnection();
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        try {
            imapConnection.open();
            fail("Expected exception");
        } catch (NegativeImapResponseException e) {
            assertThat(e.getMessage(), containsString("Maximum number of connections from user+IP exceeded"));
        }

        assertFalse(imapConnection.isConnected());
        server.verifyConnectionClosed();
        server.verifyInteractionCompleted();
    }

    @Test
    public void open_authPlainWithByeResponseAndConnectionClose_shouldThrowAuthenticationFailedException()
            throws Exception {
        settings.setAuthType(AuthType.PLAIN);
        MockImapServer server = new MockImapServer();
        preAuthenticationDialog(server, "AUTH=PLAIN");
        server.expect("2 AUTHENTICATE PLAIN");
        server.output("+");
        server.expect(ByteString.encodeUtf8("\000" + USERNAME + "\000" + PASSWORD).base64());
        server.output("* BYE Go away");
        server.output("2 NO Login Failure");
        server.closeConnection();
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        try {
            imapConnection.open();
            fail("Expected exception");
        } catch (AuthenticationFailedException e) {
            //FIXME: improve exception message
            assertThat(e.getMessage(), containsString("Login Failure"));
        }

        server.verifyConnectionClosed();
        server.verifyInteractionCompleted();
    }

    @Test
    public void open_authPlainWithoutAuthPlainCapability_shouldUseLoginMethod() throws Exception {
        settings.setAuthType(AuthType.PLAIN);
        MockImapServer server = new MockImapServer();
        preAuthenticationDialog(server);
        server.expect("2 LOGIN \"" + USERNAME + "\" \"" + PASSWORD + "\"");
        server.output("2 OK LOGIN completed");
        postAuthenticationDialogRequestingCapabilities(server);
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        imapConnection.open();

        server.verifyConnectionStillOpen();
        server.verifyInteractionCompleted();
    }

    @Test
    public void open_authCramMd5() throws Exception {
        settings.setAuthType(AuthType.CRAM_MD5);
        MockImapServer server = new MockImapServer();
        preAuthenticationDialog(server, "AUTH=CRAM-MD5");
        server.expect("2 AUTHENTICATE CRAM-MD5");
        server.output("+ " + ByteString.encodeUtf8("<0000.000000000@example.org>").base64());
        server.expect("dXNlciA2ZjdiOTcyYjk5YTI4NDk4OTRhN2YyMmE3MGRhZDg0OQ==");
        server.output("2 OK Success");
        postAuthenticationDialogRequestingCapabilities(server);
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        imapConnection.open();

        server.verifyConnectionStillOpen();
        server.verifyInteractionCompleted();
    }

    @Test
    public void open_authCramMd5WithAuthenticationFailure_shouldThrow() throws Exception {
        settings.setAuthType(AuthType.CRAM_MD5);
        MockImapServer server = new MockImapServer();
        preAuthenticationDialog(server, "AUTH=CRAM-MD5");
        server.expect("2 AUTHENTICATE CRAM-MD5");
        server.output("+ " + ByteString.encodeUtf8("<0000.000000000@example.org>").base64());
        server.expect("dXNlciA2ZjdiOTcyYjk5YTI4NDk4OTRhN2YyMmE3MGRhZDg0OQ==");
        server.output("2 NO Who are you?");
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        try {
            imapConnection.open();
            fail("Expected exception");
        } catch (AuthenticationFailedException e) {
            //FIXME: improve exception message
            assertThat(e.getMessage(), containsString("Who are you?"));
        }

        server.verifyConnectionClosed();
        server.verifyInteractionCompleted();
    }

    @Test
    public void open_authCramMd5WithoutAuthCramMd5Capability_shouldThrow() throws Exception {
        settings.setAuthType(AuthType.CRAM_MD5);
        MockImapServer server = new MockImapServer();
        preAuthenticationDialog(server, "AUTH=PLAIN");
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        try {
            imapConnection.open();
            fail("Expected exception");
        } catch (MessagingException e) {
            assertEquals("Server doesn't support encrypted passwords using CRAM-MD5.", e.getMessage());
        }

        server.verifyConnectionClosed();
        server.verifyInteractionCompleted();
    }

    @Test
    public void open_authXoauthWithSaslIr() throws Exception {
        settings.setAuthType(AuthType.XOAUTH2);
        MockImapServer server = new MockImapServer();
        preAuthenticationDialog(server, "SASL-IR AUTH=XOAUTH AUTH=XOAUTH2");
        server.expect("2 AUTHENTICATE XOAUTH2 " + XOAUTH_STRING);
        server.output("2 OK Success");
        postAuthenticationDialogRequestingCapabilities(server);
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        imapConnection.open();

        server.verifyConnectionStillOpen();
        server.verifyInteractionCompleted();
    }

    @Test
    public void open_authXoauthWithSaslIrThrowsExeptionOn401Response() throws Exception {
        settings.setAuthType(AuthType.XOAUTH2);
        MockImapServer server = new MockImapServer();
        preAuthenticationDialog(server, "SASL-IR AUTH=XOAUTH AUTH=XOAUTH2");
        server.expect("2 AUTHENTICATE XOAUTH2 " + XOAUTH_STRING);
        server.output("+ " + XOAuth2ChallengeParserTest.STATUS_401_RESPONSE);
        server.expect("");
        server.output("2 NO SASL authentication failed");
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        try {
            imapConnection.open();
            fail();
        } catch (AuthenticationFailedException e) {
            assertEquals("Command: AUTHENTICATE XOAUTH2; response: #2# [NO, SASL authentication failed]",
                    e.getMessage());
        }
    }

    @Test
    public void open_authXoauthWithSaslIrInvalidatesAndRetriesNewTokenOn400Response() throws Exception {
        settings.setAuthType(AuthType.XOAUTH2);
        MockImapServer server = new MockImapServer();
        preAuthenticationDialog(server, "SASL-IR AUTH=XOAUTH AUTH=XOAUTH2");
        server.expect("2 AUTHENTICATE XOAUTH2 " + XOAUTH_STRING);
        server.output("+ " + XOAuth2ChallengeParserTest.STATUS_400_RESPONSE);
        server.expect("");
        server.output("2 NO SASL authentication failed");
        server.expect("3 AUTHENTICATE XOAUTH2 " + XOAUTH_STRING_RETRY);
        server.output("3 OK Success");
        postAuthenticationDialogRequestingCapabilities(server, 4);
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        imapConnection.open();

        server.verifyConnectionStillOpen();
        server.verifyInteractionCompleted();
    }

    @Test
    public void open_authXoauthWithSaslIrInvalidatesAndRetriesNewTokenOnInvalidJsonResponse() throws Exception {
        settings.setAuthType(AuthType.XOAUTH2);
        MockImapServer server = new MockImapServer();
        preAuthenticationDialog(server, "SASL-IR AUTH=XOAUTH AUTH=XOAUTH2");
        server.expect("2 AUTHENTICATE XOAUTH2 " + XOAUTH_STRING);
        server.output("+ " + XOAuth2ChallengeParserTest.INVALID_RESPONSE);
        server.expect("");
        server.output("2 NO SASL authentication failed");
        server.expect("3 AUTHENTICATE XOAUTH2 " + XOAUTH_STRING_RETRY);
        server.output("3 OK Success");
        requestCapabilities(server, 4);
        simplePostAuthenticationDialog(server, 5);
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        imapConnection.open();

        server.verifyConnectionStillOpen();
        server.verifyInteractionCompleted();
    }

    @Test
    public void open_authXoauthWithSaslIrInvalidatesAndRetriesNewTokenOnMissingStatusJsonResponse() throws Exception {
        settings.setAuthType(AuthType.XOAUTH2);
        MockImapServer server = new MockImapServer();
        preAuthenticationDialog(server, "SASL-IR AUTH=XOAUTH AUTH=XOAUTH2");
        server.expect("2 AUTHENTICATE XOAUTH2 " + XOAUTH_STRING);
        server.output("+ " + XOAuth2ChallengeParserTest.MISSING_STATUS_RESPONSE);
        server.expect("");
        server.output("2 NO SASL authentication failed");
        server.expect("3 AUTHENTICATE XOAUTH2 " + XOAUTH_STRING_RETRY);
        server.output("3 OK Success");
        requestCapabilities(server, 4);
        simplePostAuthenticationDialog(server, 5);
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        imapConnection.open();

        server.verifyConnectionStillOpen();
        server.verifyInteractionCompleted();
    }

    @Test
    public void open_authXoauthWithSaslIrWithOldTokenThrowsExceptionIfRetryFails() throws Exception {
        settings.setAuthType(AuthType.XOAUTH2);
        MockImapServer server = new MockImapServer();
        preAuthenticationDialog(server, "SASL-IR AUTH=XOAUTH AUTH=XOAUTH2");
        server.expect("2 AUTHENTICATE XOAUTH2 " + XOAUTH_STRING);
        server.output("+ r3j3krj3irj3oir3ojo");
        server.expect("");
        server.output("2 NO SASL authentication failed");
        server.expect("3 AUTHENTICATE XOAUTH2 " + XOAUTH_STRING_RETRY);
        server.output("+ 433ba3a3a");
        server.expect("");
        server.output("3 NO SASL authentication failed");
        postAuthenticationDialogRequestingCapabilities(server);
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        try {
            imapConnection.open();
            fail();
        } catch (AuthenticationFailedException e) {
            assertEquals("Command: AUTHENTICATE XOAUTH2; response: #3# [NO, SASL authentication failed]",
                    e.getMessage());
        }
    }

    @Test
    public void open_authXoauthWithSaslIrParsesCapabilities() throws Exception {
        settings.setAuthType(AuthType.XOAUTH2);
        MockImapServer server = new MockImapServer();
        preAuthenticationDialog(server, "SASL-IR AUTH=XOAUTH AUTH=XOAUTH2");
        server.expect("2 AUTHENTICATE XOAUTH2 " + XOAUTH_STRING);
        server.output("2 OK [CAPABILITY IMAP4REV1 IDLE XM-GM-EXT-1]");
        simplePostAuthenticationDialog(server, 3);
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        imapConnection.open();

        server.verifyConnectionStillOpen();
        server.verifyInteractionCompleted();
        assertTrue(imapConnection.hasCapability("XM-GM-EXT-1"));
    }

    @Test
    public void open_authExternal() throws Exception {
        settings.setAuthType(AuthType.EXTERNAL);
        MockImapServer server = new MockImapServer();
        preAuthenticationDialog(server, "AUTH=EXTERNAL");
        server.expect("2 AUTHENTICATE EXTERNAL " + ByteString.encodeUtf8(USERNAME).base64());
        server.output("2 OK Success");
        postAuthenticationDialogRequestingCapabilities(server);
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        imapConnection.open();

        server.verifyConnectionStillOpen();
        server.verifyInteractionCompleted();
    }

    @Test
    public void open_authExternalWithAuthenticationFailure_shouldThrow() throws Exception {
        settings.setAuthType(AuthType.EXTERNAL);
        MockImapServer server = new MockImapServer();
        preAuthenticationDialog(server, "AUTH=EXTERNAL");
        server.expect("2 AUTHENTICATE EXTERNAL " + ByteString.encodeUtf8(USERNAME).base64());
        server.output("2 NO Bad certificate");
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        try {
            imapConnection.open();
            fail("Expected exception");
        } catch (CertificateValidationException e) {
            //FIXME: improve exception message
            assertThat(e.getMessage(), containsString("Bad certificate"));
        }

        server.verifyConnectionClosed();
        server.verifyInteractionCompleted();
    }

    @Test
    public void open_authExternalWithoutAuthExternalCapability_shouldThrow() throws Exception {
        settings.setAuthType(AuthType.EXTERNAL);
        MockImapServer server = new MockImapServer();
        preAuthenticationDialog(server, "AUTH=PLAIN");
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        try {
            imapConnection.open();
            fail("Expected exception");
        } catch (CertificateValidationException e) {
            assertEquals(Reason.MissingCapability, e.getReason());
        }

        server.verifyConnectionClosed();
        server.verifyInteractionCompleted();
    }

    @Test
    public void open_withNoPostAuthCapabilityResponse_shouldIssueCapabilityCommand() throws Exception {
        settings.setAuthType(AuthType.PLAIN);
        MockImapServer server = new MockImapServer();
        preAuthenticationDialog(server, "AUTH=PLAIN");
        server.expect("2 AUTHENTICATE PLAIN");
        server.output("+");
        server.expect(ByteString.encodeUtf8("\000" + USERNAME + "\000" + PASSWORD).base64());
        server.output("2 OK Success");
        server.expect("3 CAPABILITY");
        server.output("* CAPABILITY IDLE");
        server.output("3 OK CAPABILITY Completed");
        simplePostAuthenticationDialog(server, 4);
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        imapConnection.open();

        server.verifyConnectionStillOpen();
        server.verifyInteractionCompleted();
        assertTrue(imapConnection.isIdleCapable());
    }

    @Test
    public void open_withUntaggedPostAuthCapabilityResponse_shouldNotIssueCapabilityCommand() throws Exception {
        settings.setAuthType(AuthType.PLAIN);
        MockImapServer server = new MockImapServer();
        preAuthenticationDialog(server, "AUTH=PLAIN");
        server.expect("2 AUTHENTICATE PLAIN");
        server.output("+");
        server.expect(ByteString.encodeUtf8("\000" + USERNAME + "\000" + PASSWORD).base64());
        server.output("* CAPABILITY IMAP4rev1 UNSELECT IDLE QUOTA ID XLIST CHILDREN X-GM-EXT-1 UIDPLUS " +
                "ENABLE MOVE CONDSTORE ESEARCH UTF8=ACCEPT LIST-EXTENDED LIST-STATUS LITERAL- SPECIAL-USE " +
                "APPENDLIMIT=35651584");
        server.output("2 OK");
        simplePostAuthenticationDialog(server, 3);
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        imapConnection.open();

        server.verifyConnectionStillOpen();
        server.verifyInteractionCompleted();
        assertTrue(imapConnection.isIdleCapable());
    }

    @Test
    public void open_withPostAuthCapabilityResponse_shouldNotIssueCapabilityCommand() throws Exception {
        settings.setAuthType(AuthType.PLAIN);
        MockImapServer server = new MockImapServer();
        preAuthenticationDialog(server, "AUTH=PLAIN");
        server.expect("2 AUTHENTICATE PLAIN");
        server.output("+");
        server.expect(ByteString.encodeUtf8("\000" + USERNAME + "\000" + PASSWORD).base64());
        server.output("2 OK [CAPABILITY IDLE]");
        simplePostAuthenticationDialog(server, 3);
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        imapConnection.open();

        server.verifyConnectionStillOpen();
        server.verifyInteractionCompleted();
        assertTrue(imapConnection.isIdleCapable());
    }

    @Test
    public void open_withNamespaceCapability_shouldIssueNamespaceCommand() throws Exception {
        MockImapServer server = new MockImapServer();
        simplePreAuthAndLoginDialog(server, "NAMESPACE");
        server.expect("3 NAMESPACE");
        server.output("* NAMESPACE ((\"\" \"/\")) NIL NIL");
        server.output("3 OK command completed");
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        imapConnection.open();

        server.verifyConnectionStillOpen();
        server.verifyInteractionCompleted();
    }

    @Test
    public void open_withConnectionError_shouldThrow() throws Exception {
        settings.setHost("127.1.2.3");
        settings.setPort(143);
        ImapConnection imapConnection = createImapConnection(
                settings, socketFactory, connectivityManager, oAuth2TokenProvider);

        try {
            imapConnection.open();
            fail("Expected exception");
        } catch (MessagingException e) {
            assertEquals("Cannot connect to host", e.getMessage());
            assertTrue(e.getCause() instanceof IOException);
        }
    }

    @Test
    public void open_withInvalidHostname_shouldThrow() throws Exception {
        settings.setHost("host name");
        settings.setPort(143);
        ImapConnection imapConnection = createImapConnection(
                settings, socketFactory, connectivityManager, oAuth2TokenProvider);

        try {
            imapConnection.open();
            fail("Expected exception");
        } catch (UnknownHostException ignored) {
        }

        assertFalse(imapConnection.isConnected());
    }

    @Test
    public void open_withStartTlsCapability_shouldIssueStartTlsCommand() throws Exception {
        settings.setAuthType(AuthType.PLAIN);
        settings.setConnectionSecurity(ConnectionSecurity.STARTTLS_REQUIRED);
        MockImapServer server = new MockImapServer();
        preAuthenticationDialog(server, "STARTTLS LOGINDISABLED");
        server.expect("2 STARTTLS");
        server.output("2 OK [CAPABILITY IMAP4REV1 NAMESPACE]");
        server.startTls();
        server.expect("3 CAPABILITY");
        server.output("* CAPABILITY IMAP4 IMAP4REV1");
        server.output("3 OK");
        server.expect("4 LOGIN \"" + USERNAME + "\" \"" + PASSWORD + "\"");
        server.output("4 OK [CAPABILITY NAMESPACE] LOGIN completed");
        server.expect("5 NAMESPACE");
        server.output("* NAMESPACE ((\"\" \"/\")) NIL NIL");
        server.output("5 OK command completed");
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        imapConnection.open();

        server.verifyConnectionStillOpen();
        server.verifyInteractionCompleted();
    }

    @Test
    public void open_withStartTlsButWithoutStartTlsCapability_shouldThrow() throws Exception {
        settings.setConnectionSecurity(ConnectionSecurity.STARTTLS_REQUIRED);
        MockImapServer server = new MockImapServer();
        preAuthenticationDialog(server);
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        try {
            imapConnection.open();
            fail("Expected exception");
        } catch (CertificateValidationException e) {
            //FIXME: CertificateValidationException seems wrong
            assertEquals("STARTTLS connection security not available", e.getMessage());
        }

        server.verifyConnectionClosed();
        server.verifyInteractionCompleted();
    }

    @Test
    public void open_withUntaggedCapabilityAfterStartTls_shouldNotThrow() throws Exception {
        settings.setAuthType(AuthType.PLAIN);
        settings.setConnectionSecurity(ConnectionSecurity.STARTTLS_REQUIRED);
        MockImapServer server = new MockImapServer();
        preAuthenticationDialog(server, "STARTTLS LOGINDISABLED");
        server.expect("2 STARTTLS");
        server.output("2 OK Begin TLS negotiation now");
        server.startTls();
        server.output("* CAPABILITY IMAP4REV1 IMAP4");
        server.expect("3 CAPABILITY");
        server.output("* CAPABILITY IMAP4 IMAP4REV1");
        server.output("3 OK");
        server.expect("4 LOGIN \"" + USERNAME + "\" \"" + PASSWORD + "\"");
        server.output("4 OK [CAPABILITY IMAP4REV1] LOGIN completed");
        simplePostAuthenticationDialog(server, 5);
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        imapConnection.open();

        server.verifyConnectionStillOpen();
        server.verifyInteractionCompleted();
    }

    @Test
    public void open_withNegativeResponseToStartTlsCommand_shouldThrow() throws Exception {
        settings.setAuthType(AuthType.PLAIN);
        settings.setConnectionSecurity(ConnectionSecurity.STARTTLS_REQUIRED);
        MockImapServer server = new MockImapServer();
        preAuthenticationDialog(server, "STARTTLS");
        server.expect("2 STARTTLS");
        server.output("2 NO");
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        try {
            imapConnection.open();
            fail("Expected exception");
        } catch (NegativeImapResponseException e) {
            assertEquals(e.getMessage(), "Command: STARTTLS; response: #2# [NO]");
        }

        server.verifyConnectionClosed();
        server.verifyInteractionCompleted();
    }

    @Test
    public void open_withCompressDeflateCapability_shouldEnableCompression() throws Exception {
        settings.setUseCompression(true);
        MockImapServer server = new MockImapServer();
        simplePreAuthAndLoginDialog(server, "COMPRESS=DEFLATE");
        server.expect("3 COMPRESS DEFLATE");
        server.output("3 OK");
        server.enableCompression();
        simplePostAuthenticationDialog(server, 4);
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        imapConnection.open();

        server.verifyConnectionStillOpen();
        server.verifyInteractionCompleted();
    }

    @Test
    public void open_withNegativeResponseToCompressionCommand_shouldContinue() throws Exception {
        settings.setAuthType(AuthType.PLAIN);
        settings.setUseCompression(true);
        MockImapServer server = new MockImapServer();
        simplePreAuthAndLoginDialog(server, "COMPRESS=DEFLATE");
        server.expect("3 COMPRESS DEFLATE");
        server.output("3 NO");
        simplePostAuthenticationDialog(server, 4);
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        imapConnection.open();

        server.verifyConnectionStillOpen();
        server.verifyInteractionCompleted();
    }

    @Test
    public void open_withIoExceptionDuringCompressionCommand_shouldThrow() throws Exception {
        settings.setAuthType(AuthType.PLAIN);
        settings.setUseCompression(true);
        MockImapServer server = new MockImapServer();
        simplePreAuthAndLoginDialog(server, "COMPRESS=DEFLATE");
        server.expect("3 COMPRESS DEFLATE");
        server.closeConnection();
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        try {
            imapConnection.open();
            fail("Exception expected");
        } catch (IOException ignored) {
        }

        server.verifyConnectionClosed();
        server.verifyInteractionCompleted();
    }

    @Test
    public void open_withIoExceptionDuringListCommand_shouldThrow() throws Exception {
        settings.setAuthType(AuthType.PLAIN);
        MockImapServer server = new MockImapServer();
        simplePreAuthAndLoginDialog(server, "");
        server.expect("3 LIST \"\" \"\"");
        server.output("* Now what?");
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        try {
            imapConnection.open();
            fail("Exception expected");
        } catch (IOException ignored) {
        }

        server.verifyConnectionClosed();
        server.verifyInteractionCompleted();
    }

    @Test
    public void open_withNegativeResponseToListCommand() throws Exception {
        settings.setAuthType(AuthType.PLAIN);
        MockImapServer server = new MockImapServer();
        simplePreAuthAndLoginDialog(server, "");
        server.expect("3 LIST \"\" \"\"");
        server.output("3 NO");
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        imapConnection.open();

        server.verifyConnectionStillOpen();
        server.verifyInteractionCompleted();
    }

    @Test
    public void isConnected_withoutPreviousOpen_shouldReturnFalse() throws Exception {
        ImapConnection imapConnection = createImapConnection(
                settings, socketFactory, connectivityManager, oAuth2TokenProvider);

        boolean result = imapConnection.isConnected();

        assertFalse(result);
    }

    @Test
    public void isConnected_afterOpen_shouldReturnTrue() throws Exception {
        MockImapServer server = new MockImapServer();
        ImapConnection imapConnection = simpleOpen(server);

        boolean result = imapConnection.isConnected();

        assertTrue(result);
        server.verifyConnectionStillOpen();

        server.shutdown();
    }

    @Test
    public void isConnected_afterOpenAndClose_shouldReturnFalse() throws Exception {
        MockImapServer server = new MockImapServer();
        ImapConnection imapConnection = simpleOpen(server);
        imapConnection.close();

        boolean result = imapConnection.isConnected();

        assertFalse(result);
        server.verifyConnectionClosed();

        server.shutdown();
    }

    @Test
    public void close_withoutOpen_shouldNotThrow() throws Exception {
        ImapConnection imapConnection = createImapConnection(
                settings, socketFactory, connectivityManager, oAuth2TokenProvider);

        imapConnection.close();
    }

    @Test
    public void close_afterOpen_shouldCloseConnection() throws Exception {
        MockImapServer server = new MockImapServer();
        ImapConnection imapConnection = simpleOpen(server);

        imapConnection.close();

        server.verifyConnectionClosed();

        server.shutdown();
    }

    @Test
    public void isIdleCapable_withoutIdleCapability() throws Exception {
        MockImapServer server = new MockImapServer();
        ImapConnection imapConnection = simpleOpen(server);

        boolean result = imapConnection.isIdleCapable();

        assertFalse(result);

        server.shutdown();
    }

    @Test
    public void isIdleCapable_withIdleCapability() throws Exception {
        MockImapServer server = new MockImapServer();
        ImapConnection imapConnection = simpleOpenWithCapabilities(server, "IDLE");

        boolean result = imapConnection.isIdleCapable();

        assertTrue(result);

        server.shutdown();
    }

    @Test
    public void sendContinuation() throws Exception {
        settings.setAuthType(AuthType.PLAIN);
        MockImapServer server = new MockImapServer();
        simpleOpenDialog(server, "IDLE");
        server.expect("4 IDLE");
        server.output("+ idling");
        server.expect("DONE");
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);
        imapConnection.open();
        imapConnection.sendCommand("IDLE", false);
        imapConnection.readResponse();

        imapConnection.sendContinuation("DONE");

        server.waitForInteractionToComplete();
        server.verifyConnectionStillOpen();
        server.verifyInteractionCompleted();
    }

    @Test
    public void executeSingleCommand_withOkResponse_shouldReturnResult() throws Exception {
        MockImapServer server = new MockImapServer();
        simpleOpenDialog(server, "");
        server.expect("4 CREATE Folder");
        server.output("4 OK Folder created");
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        List<ImapResponse> result = imapConnection.executeSimpleCommand("CREATE Folder");

        assertEquals(result.size(), 1);
        server.verifyConnectionStillOpen();
        server.verifyInteractionCompleted();
    }

    @Test
    public void executeSingleCommand_withNoResponse_shouldThrowNegativeImapResponseException() throws Exception {
        MockImapServer server = new MockImapServer();
        simpleOpenDialog(server, "");
        server.expect("4 CREATE Folder");
        server.output("4 NO Folder exists");
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        try {
            imapConnection.executeSimpleCommand("CREATE Folder");

            fail("Expected exception");
        } catch (NegativeImapResponseException e) {
            assertEquals("Folder exists", e.getLastResponse().getString(1));
        }
        server.verifyConnectionStillOpen();
        server.verifyInteractionCompleted();
    }

    @Test
    public void hasCapability_withNotYetOpenedConnection_shouldConnectAndFetchCapabilities() throws Exception {
        MockImapServer server = new MockImapServer();
        simpleOpenDialog(server, "X-SOMETHING");
        ImapConnection imapConnection = startServerAndCreateImapConnection(server);

        boolean capabilityPresent = imapConnection.hasCapability("X-SOMETHING");

        assertTrue(capabilityPresent);
        server.verifyConnectionStillOpen();
        server.verifyInteractionCompleted();
    }

    private ImapConnection createImapConnection(ImapSettings settings, TrustedSocketFactory socketFactory,
            ConnectivityManager connectivityManager, OAuth2TokenProvider oAuth2TokenProvider) {
        return new ImapConnection(settings, socketFactory, connectivityManager, oAuth2TokenProvider,
                SOCKET_CONNECT_TIMEOUT, SOCKET_READ_TIMEOUT);
    }

    private ImapConnection startServerAndCreateImapConnection(MockImapServer server) throws IOException {
        server.start();
        settings.setHost(server.getHost());
        settings.setPort(server.getPort());
        return createImapConnection(settings, socketFactory, connectivityManager, oAuth2TokenProvider);
    }

    private ImapConnection simpleOpen(MockImapServer server) throws Exception {
        return simpleOpenWithCapabilities(server, "");
    }

    private ImapConnection simpleOpenWithCapabilities(MockImapServer server, String postAuthCapabilities)
            throws Exception {
        simpleOpenDialog(server, postAuthCapabilities);

        ImapConnection imapConnection = startServerAndCreateImapConnection(server);
        imapConnection.open();

        return imapConnection;
    }

    private void preAuthenticationDialog(MockImapServer server) {
        preAuthenticationDialog(server, "");
    }

    private void preAuthenticationDialog(MockImapServer server, String capabilities) {
        server.output("* OK IMAP4rev1 Service Ready");
        server.expect("1 CAPABILITY");
        server.output("* CAPABILITY IMAP4 IMAP4REV1 " + capabilities);
        server.output("1 OK CAPABILITY");
    }

    private void postAuthenticationDialogRequestingCapabilities(MockImapServer server) {
        postAuthenticationDialogRequestingCapabilities(server, 3);
    }

    private void postAuthenticationDialogRequestingCapabilities(MockImapServer server, int tag) {
        requestCapabilities(server, tag);
        simplePostAuthenticationDialog(server, tag + 1);
    }

    private void requestCapabilities(MockImapServer server, int tag) {
        server.expect(tag + " CAPABILITY");
        server.output("* CAPABILITY IMAP4 IMAP4REV1 ");
        server.output(tag + " OK CAPABILITY");
    }

    private void simplePostAuthenticationDialog(MockImapServer server, int tag) {
        server.expect(tag + " LIST \"\" \"\"");
        server.output("* LIST () \"/\" foo/bar");
        server.output(tag + " OK");
    }

    private void simpleOpenDialog(MockImapServer server, String postAuthCapabilities) {
        simplePreAuthAndLoginDialog(server, postAuthCapabilities);
        simplePostAuthenticationDialog(server, 3);
    }

    private void simplePreAuthAndLoginDialog(MockImapServer server, String postAuthCapabilities) {
        settings.setAuthType(AuthType.PLAIN);

        preAuthenticationDialog(server);

        server.expect("2 LOGIN \"" + USERNAME + "\" \"" + PASSWORD + "\"");
        server.output("2 OK [CAPABILITY " + postAuthCapabilities + "] LOGIN completed");
    }

    private OAuth2TokenProvider createOAuth2TokenProvider() {
        return new OAuth2TokenProvider() {
            private int invalidationCount = 0;

            @Override
            public String getToken(String username, long timeoutMillis) throws AuthenticationFailedException {
                assertEquals(USERNAME, username);
                assertEquals(OAUTH2_TIMEOUT, timeoutMillis);

                switch (invalidationCount) {
                    case 0: {
                        return XOAUTH_TOKEN;
                    }
                    case 1: {
                        return XOAUTH_ANOTHER_TOKEN;
                    }
                    default: {
                        throw new AuthenticationFailedException("Ran out of auth tokens. invalidateToken() called too often?");
                    }
                }
            }

            @Override
            public void invalidateToken(String username) {
                assertEquals(USERNAME, username);
                invalidationCount++;
            }
        };
    }
}
