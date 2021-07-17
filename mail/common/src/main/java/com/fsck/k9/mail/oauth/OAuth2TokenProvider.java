package com.fsck.k9.mail.oauth;


import com.fsck.k9.mail.AuthenticationFailedException;
import com.fsck.k9.mail.oauth.authorizationserver.codegrantflow.OAuth2NeedUserPromptException;


public interface OAuth2TokenProvider {
    /**
     * A default timeout value to use when fetching tokens.
     */
    int OAUTH2_TIMEOUT = 30000;

    /**
     * Fetch a token. No guarantees are provided for validity.
     */
    String getToken(String email, long timeoutMillis)
            throws AuthenticationFailedException, OAuth2NeedUserPromptException;

    /**
     * Invalidate the token for this email.
     *
     * <p>
     * Note that the token should always be invalidated on credential failure. However invalidating a token every single
     * time is not recommended.
     * <p>
     * Invalidating a token and then failure with a new token should be treated as a permanent failure.
     */
    void invalidateToken(String email);
}
