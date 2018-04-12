// ------------------------------------------------------------------------------
// Copyright (c) 2014 Microsoft Corporation
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
//  of this software and associated documentation files (the "Software"), to deal
//  in the Software without restriction, including without limitation the rights
//  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
//  copies of the Software, and to permit persons to whom the Software is
//  furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
//  all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
//  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
//  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
//  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
//  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
//  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
//  THE SOFTWARE.
// ------------------------------------------------------------------------------

package com.microsoft.services.msa;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;

import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.DefaultHttpClient;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code LiveAuthClient} is a class responsible for retrieving a {@link LiveConnectSession}, which
 * provides authentication information for calls to Live APIs.
 */
public class LiveAuthClient {

    private static final String TAG = "LiveAuthClient";

    private static class AuthCompleteRunnable extends AuthListenerCaller implements Runnable {

        private final LiveStatus status;
        private final LiveConnectSession session;

        public AuthCompleteRunnable(LiveAuthListener listener,
                                    Object userState,
                                    LiveStatus status,
                                    LiveConnectSession session) {
            super(listener, userState);
            this.status = status;
            this.session = session;
        }

        @Override
        public void run() {
            listener.onAuthComplete(status, session, userState);
        }
    }

    private static class AuthErrorRunnable extends AuthListenerCaller implements Runnable {

        private final LiveAuthException exception;

        public AuthErrorRunnable(LiveAuthListener listener,
                                 Object userState,
                                 LiveAuthException exception) {
            super(listener, userState);
            this.exception = exception;
        }

        @Override
        public void run() {
            listener.onAuthError(exception, userState);
        }

    }

    private static abstract class AuthListenerCaller {
        protected final LiveAuthListener listener;
        protected final Object userState;

        public AuthListenerCaller(LiveAuthListener listener, Object userState) {
            this.listener = listener;
            this.userState = userState;
        }
    }

    /**
     * This class observes an {@link AccessTokenRequest} and calls the appropriate Listener method.
     * On a successful response, it will call the
     * {@link LiveAuthListener#onAuthComplete(LiveStatus, LiveConnectSession, Object)}.
     * On an exception or an unsuccessful response, it will call
     * {@link LiveAuthListener#onAuthError(LiveAuthException, Object)}.
     */
    private class ListenerCallerObserver extends AuthListenerCaller
                                         implements OAuthRequestObserver,
                                                    OAuthResponseVisitor {

        public ListenerCallerObserver(LiveAuthListener listener, Object userState) {
            super(listener, userState);
        }

        @Override
        public void onException(LiveAuthException exception) {
            new AuthErrorRunnable(listener, userState, exception).run();
        }

        @Override
        public void onResponse(OAuthResponse response) {
            response.accept(this);
        }

        @Override
        public void visit(OAuthErrorResponse response) {
            String error = response.getError().toString().toLowerCase(Locale.US);
            String errorDescription = response.getErrorDescription();
            String errorUri = response.getErrorUri();
            LiveAuthException exception = new LiveAuthException(error,
                                                                errorDescription,
                                                                errorUri);

            new AuthErrorRunnable(listener, userState, exception).run();
        }

        @Override
        public void visit(OAuthSuccessfulResponse response) {
            session.loadFromOAuthResponse(response);

            new AuthCompleteRunnable(listener, userState, LiveStatus.CONNECTED, session).run();
        }
    }

    /** Observer that will, depending on the response, save or clear the refresh token. */
    private class RefreshTokenWriter implements OAuthRequestObserver, OAuthResponseVisitor {

        @Override
        public void onException(LiveAuthException exception) { }

        @Override
        public void onResponse(OAuthResponse response) {
            response.accept(this);
        }

        @Override
        public void visit(OAuthErrorResponse response) {
            if (response.getError() == OAuth.ErrorType.INVALID_GRANT) {
                LiveAuthClient.this.clearRefreshTokenFromPreferences();
            }
        }

        @Override
        public void visit(OAuthSuccessfulResponse response) {
            String refreshToken = response.getRefreshToken();
            if (!TextUtils.isEmpty(refreshToken)) {
                this.saveRefreshTokenToPreferences(refreshToken);
            }
        }

        private boolean saveRefreshTokenToPreferences(String refreshToken) {
            if (TextUtils.isEmpty(refreshToken)) throw new AssertionError();

            SharedPreferences settings =
                    applicationContext.getSharedPreferences(PreferencesConstants.FILE_NAME,
                                                            Context.MODE_PRIVATE);
            Editor editor = settings.edit();
            editor.putString(PreferencesConstants.REFRESH_TOKEN_KEY, refreshToken);

            return editor.commit();
        }
    }

    /**
     * An {@link OAuthResponseVisitor} that checks the {@link OAuthResponse} and if it is a
     * successful response, it loads the response into the given session.
     */
    private static class SessionRefresher implements OAuthResponseVisitor {

        private final LiveConnectSession session;
        private boolean visitedSuccessfulResponse;

        public SessionRefresher(LiveConnectSession session) {
            if (session == null) throw new AssertionError();

            this.session = session;
            this.visitedSuccessfulResponse = false;
        }

        @Override
        public void visit(OAuthErrorResponse response) {
            this.visitedSuccessfulResponse = false;
        }

        @Override
        public void visit(OAuthSuccessfulResponse response) {
            this.session.loadFromOAuthResponse(response);
            this.visitedSuccessfulResponse = true;
        }

        public boolean visitedSuccessfulResponse() {
            return this.visitedSuccessfulResponse;
        }
    }

    /**
     * A LiveAuthListener that does nothing on each of the call backs.
     * This is used so when a null listener is passed in, this can be used, instead of null,
     * to avoid if (listener == null) checks.
     */
    private static final LiveAuthListener NULL_LISTENER = new LiveAuthListener() {
        @Override
        public void onAuthComplete(LiveStatus status, LiveConnectSession session, Object sender) { }
        @Override
        public void onAuthError(LiveAuthException exception, Object sender) { }
    };

    private final Context applicationContext;
    private final String clientId;
    private boolean hasPendingLoginRequest;

    /**
     * Responsible for all network (i.e., HTTP) calls.
     * Tests will want to change this to mock the network and HTTP responses.
     * @see #setHttpClient(HttpClient)
     */
    private HttpClient httpClient;

    /** saved from initialize and used in the login call if login's scopes are null. */
    private Set<String> baseScopes;

    /** The OAuth configuration */
    private final OAuthConfig mOAuthConfig;

    /** One-to-one relationship between LiveAuthClient and LiveConnectSession. */
    private final LiveConnectSession session;

    {
        this.httpClient = new DefaultHttpClient();
        this.hasPendingLoginRequest = false;
        this.session = new LiveConnectSession(this);
    }

    private AuthorizationRequest.CancellationTrigger cancellationTrigger;

    /**
     * Constructs a new {@code LiveAuthClient} instance and initializes its member variables.
     *
     * @param context Context of the Application used to save any refresh_token.
     * @param clientId The client_id of the Live Connect Application to login to.
     * @param scopes to initialize the {@link LiveConnectSession} with.
     *        See <a href="http://msdn.microsoft.com/en-us/library/hh243646.aspx">MSDN Live Connect
     *        Reference's Scopes and permissions</a> for a list of scopes and explanations.
     */
    public LiveAuthClient(final Context context,
                          final String clientId,
                          final Iterable<String> scopes,
                          final OAuthConfig oAuthConfig) {
        LiveConnectUtils.assertNotNull(context, "context");
        LiveConnectUtils.assertNotNullOrEmpty(clientId, "clientId");

        this.applicationContext = context.getApplicationContext();
        this.clientId = clientId;


        if (oAuthConfig == null) {
            this.mOAuthConfig = MicrosoftOAuthConfig.getInstance();
        } else {
            this.mOAuthConfig = oAuthConfig;
        }

        // copy scopes for login
        Iterable<String> tempScopes = scopes;
        if (tempScopes == null) {
            tempScopes = Arrays.asList(new String[0]);
        }

        this.baseScopes = new HashSet<>();
        for (final String scope : tempScopes) {
            this.baseScopes.add(scope);
        }

        this.baseScopes = Collections.unmodifiableSet(this.baseScopes);

        final String refreshToken = this.getRefreshTokenFromPreferences();
        if (!TextUtils.isEmpty(refreshToken)) {
            final String scopeAsString = TextUtils.join(OAuth.SCOPE_DELIMITER, this.baseScopes);
            RefreshAccessTokenRequest request = new RefreshAccessTokenRequest(this.httpClient,
                                                                                 this.clientId,
                                                                                 refreshToken,
                                                                                 scopeAsString,
                                                                                 this.mOAuthConfig);
            TokenRequestAsync requestAsync = new TokenRequestAsync(request);
            requestAsync.addObserver(new RefreshTokenWriter());
            requestAsync.execute();
        }
    }

    public LiveAuthClient(final Context context, final String clientId) {
        this(context, clientId, null);
    }

    public LiveAuthClient(final Context context,
                          final String clientId,
                          final Iterable<String> scopes) {
        this(context,clientId, scopes, null);
    }

    /** @return the client_id of the Live Connect application. */
    public String getClientId() {
        return this.clientId;
    }

    public void login(Activity activity, LiveAuthListener listener) {
        this.login(activity, null, null, listener);
    }

    public void login(Activity activity, Iterable<String> scopes, LiveAuthListener listener) {
        this.login(activity, scopes, null, null, listener);
    }

    public void login(Activity activity, Iterable<String> scopes, Object userState, LiveAuthListener listener) {
        this.login(activity, scopes, userState, null, listener);
    }

    /**
     * Logs in an user with the given scopes and additional saved state.
     *
     * login displays a {@link Dialog} that will prompt the
     * user for a username and password, and ask for consent to use the given scopes.
     * A {@link LiveConnectSession} will be returned by calling
     * {@link LiveAuthListener#onAuthComplete(LiveStatus, LiveConnectSession, Object)}.
     * Otherwise, the {@link LiveAuthListener#onAuthError(LiveAuthException, Object)} will be
     * called. These methods will be called on the main/UI thread.
     *
     * @param activity {@link Activity} instance to display the Login dialog on
     * @param scopes to initialize the {@link LiveConnectSession} with.
     *        See <a href="http://msdn.microsoft.com/en-us/library/hh243646.aspx">MSDN Live Connect
     *        Reference's Scopes and permissions</a> for a list of scopes and explanations.
     *        Scopes specified here override scopes specified in the constructor.
     * @param userState arbitrary object that is used to determine the caller of the method.
     * @param loginHint the hint for the sign in experience to show the username pre-filled out
     * @param listener called on either completion or error during the login process.
     * @throws IllegalStateException if there is a pending login request.
     */
    public void login(Activity activity,
                      Iterable<String> scopes,
                      Object userState,
                      String loginHint,
                      LiveAuthListener listener
                      ) {
        LiveConnectUtils.assertNotNull(activity, "activity");

        if (listener == null) {
            listener = NULL_LISTENER;
        }

        if (this.hasPendingLoginRequest) {
            throw new IllegalStateException(ErrorMessages.LOGIN_IN_PROGRESS);
        }

        // if no scopes were passed in, use the scopes from initialize or if those are empty,
        // create an empty list
        if (scopes == null) {
            if (this.baseScopes == null) {
                scopes = Arrays.asList(new String[0]);
            } else {
                scopes = this.baseScopes;
            }
        }

        // if the session is valid and contains all the scopes, do not display the login ui.
        if (loginSilent(scopes, userState, listener)) {
            Log.i(TAG, "Interactive login not required.");
            return;
        }

        // silent login failed, initiating interactive login
        String scope = TextUtils.join(OAuth.SCOPE_DELIMITER, scopes);

        AuthorizationRequest request = new AuthorizationRequest(activity,
                                                                this.httpClient,
                                                                this.clientId,
                                                                scope,
                                                                loginHint,
                                                                mOAuthConfig);

        request.addObserver(new ListenerCallerObserver(listener, userState));
        request.addObserver(new RefreshTokenWriter());
        request.addObserver(new OAuthRequestObserver() {
            @Override
            public void onException(LiveAuthException exception) {
                LiveAuthClient.this.hasPendingLoginRequest = false;
            }

            @Override
            public void onResponse(OAuthResponse response) {
                LiveAuthClient.this.hasPendingLoginRequest = false;
            }
        });

        this.hasPendingLoginRequest = true;
        this.cancellationTrigger = request.getCancellationTrigger();

        request.execute();
    }

    public Boolean loginSilent(LiveAuthListener listener) {
        return this.loginSilent(null, null, listener);
    }

    public Boolean loginSilent(Iterable<String> scopes, LiveAuthListener listener) {
        return this.loginSilent(scopes, null, listener);
    }

    public Boolean loginSilent(Object userState, LiveAuthListener listener) {
        return this.loginSilent(null, userState, listener);
    }

    /**
     * Attempts to log in a user using multiple non-interactive approaches.
     *
     * A {@link LiveConnectSession} will be returned by calling
     * {@link LiveAuthListener#onAuthComplete(LiveStatus, LiveConnectSession, Object)}.
     * Otherwise, the {@link LiveAuthListener#onAuthError(LiveAuthException, Object)} will be
     * called. These methods will be called on the main/UI thread.
     *
     * @param scopes list of scopes which will override scopes from constructor
     * @param userState state object that is pass to listener on completion.
     * @param listener called on either completion or error during the login process.
     * @return false == silent login failed, interactive login required.
     *         true == silent login is continuing on the background thread the listener will be
     *                 called back when it has completed.
     */
    public Boolean loginSilent(final Iterable<String> scopes,
                               final Object userState,
                               final LiveAuthListener listener) {

        if (this.hasPendingLoginRequest) {
            throw new IllegalStateException(ErrorMessages.LOGIN_IN_PROGRESS);
        }

        final Iterable<String> activeScopes;
        if (scopes == null) {
            if (this.baseScopes == null) {
                activeScopes = Arrays.asList(new String[0]);
            } else {
                activeScopes = this.baseScopes;
            }
        } else {
            activeScopes = scopes;
        }

        if (TextUtils.isEmpty(this.session.getRefreshToken())) {
            this.session.setRefreshToken(getRefreshTokenFromPreferences());
        }

        // if the session is valid and contains all the scopes, do not display the login ui.
        final boolean needNewAccessToken = this.session.isExpired() || !this.session.contains(activeScopes);
        final boolean attemptingToLoginSilently = TextUtils.isEmpty(this.session.getRefreshToken());

        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(final Void... voids) {
                if (!needNewAccessToken) {
                    Log.i(TAG, "Access token still valid, so using it.");
                    listener.onAuthComplete(LiveStatus.CONNECTED, LiveAuthClient.this.session, userState);
                } else if (tryRefresh(activeScopes)) {
                    Log.i(TAG, "Used refresh token to refresh access and refresh tokens.");
                    listener.onAuthComplete(LiveStatus.CONNECTED, LiveAuthClient.this.session, userState);
                } else {
                    Log.i(TAG, "All tokens expired, you need to call login() to initiate interactive logon");
                    listener.onAuthComplete(LiveStatus.NOT_CONNECTED,
                                               LiveAuthClient.this.getSession(), userState);
                }
                return null;
            }
        }.execute();

        return !attemptingToLoginSilently;
    }

    /**
     * Logs out the given user.
     *
     * Also, this method clears the previously created {@link LiveConnectSession}.
     * {@link LiveAuthListener#onAuthComplete(LiveStatus, LiveConnectSession, Object)} will be
     * called on completion. Otherwise,
     * {@link LiveAuthListener#onAuthError(LiveAuthException, Object)} will be called.
     *
     * @param listener called on either completion or error during the logout process.
     */
    public void logout(LiveAuthListener listener) {
        this.logout(null, listener);
    }

    /**
     * Logs out the given user.
     *
     * Also, this method clears the previously created {@link LiveConnectSession}.
     * {@link LiveAuthListener#onAuthComplete(LiveStatus, LiveConnectSession, Object)} will be
     * called on completion. Otherwise,
     * {@link LiveAuthListener#onAuthError(LiveAuthException, Object)} will be called.
     *
     * @param userState arbitrary object that is used to determine the caller of the method.
     * @param listener called on either completion or error during the logout process.
     */
    public void logout(Object userState, LiveAuthListener listener) {
        if (listener == null) {
            listener = NULL_LISTENER;
        }

        session.setAccessToken(null);
        session.setAuthenticationToken(null);
        session.setRefreshToken(null);
        session.setScopes(null);
        session.setTokenType(null);

        clearRefreshTokenFromPreferences();

        CookieSyncManager cookieSyncManager =
                CookieSyncManager.createInstance(this.applicationContext);
        CookieManager manager = CookieManager.getInstance();
        
        // clear cookies to force prompt on login
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            manager.removeAllCookies(null);
        else
            manager.removeAllCookie();

        cookieSyncManager.sync();
        listener.onAuthComplete(LiveStatus.UNKNOWN, null, userState);
    }

    /**
     * Cancel a login attempt in progress.
     */
    public void cancelLogin() {
        if (cancellationTrigger != null && this.hasPendingLoginRequest) {
            cancellationTrigger.cancel();
        }
    }

    /** @return The {@link HttpClient} instance used by this {@code LiveAuthClient}. */
    HttpClient getHttpClient() {
        return this.httpClient;
    }

    /** @return The {@link LiveConnectSession} instance that this {@code LiveAuthClient} created. */
    public LiveConnectSession getSession() {
        return session;
    }

    /**
     * Refreshes the previously created session.
     *
     * @return true if the session was successfully refreshed.
     */
    Boolean tryRefresh(Iterable<String> scopes) {

        String scope = TextUtils.join(OAuth.SCOPE_DELIMITER, scopes);
        String refreshToken = this.session.getRefreshToken();

        if (TextUtils.isEmpty(refreshToken)) {
            Log.i(TAG, "No refresh token available, sorry!");
            return false;
        }

        Log.i(TAG, "Refresh token found, attempting to refresh access and refresh tokens.");
        RefreshAccessTokenRequest request =
                new RefreshAccessTokenRequest(this.httpClient, this.clientId, refreshToken, scope, mOAuthConfig);

        OAuthResponse response;
        try {
            response = request.execute();
        } catch (LiveAuthException e) {
            return false;
        }

        SessionRefresher refresher = new SessionRefresher(this.session);
        response.accept(refresher);
        response.accept(new RefreshTokenWriter());

        return refresher.visitedSuccessfulResponse();
    }

    /**
     * Sets the {@link HttpClient} that is used for HTTP requests by this {@code LiveAuthClient}.
     * Tests will want to change this to mock the network/HTTP responses.
     * @param client The new HttpClient to be set.
     */
    void setHttpClient(HttpClient client) {
        if (client == null) throw new AssertionError();
        this.httpClient = client;
    }

    /**
     * Clears the refresh token from this {@code LiveAuthClient}'s
     * {@link Activity#getPreferences(int)}.
     *
     * @return true if the refresh token was successfully cleared.
     */
    private boolean clearRefreshTokenFromPreferences() {
        SharedPreferences settings = getSharedPreferences();
        Editor editor = settings.edit();
        editor.remove(PreferencesConstants.REFRESH_TOKEN_KEY);

        return editor.commit();
    }

    private SharedPreferences getSharedPreferences() {
        return applicationContext.getSharedPreferences(PreferencesConstants.FILE_NAME,
                                                       Context.MODE_PRIVATE);
    }

    private List<String> getCookieKeysFromPreferences() {
        SharedPreferences settings = getSharedPreferences();
        String cookieKeys = settings.getString(PreferencesConstants.COOKIES_KEY, "");

        return Arrays.asList(TextUtils.split(cookieKeys, PreferencesConstants.COOKIE_DELIMITER));
    }

    /**
     * Retrieves the refresh token from this {@code LiveAuthClient}'s
     * {@link Activity#getPreferences(int)}.
     *
     * @return the refresh token from persistent storage.
     */
    private String getRefreshTokenFromPreferences() {
        SharedPreferences settings = getSharedPreferences();
        return settings.getString(PreferencesConstants.REFRESH_TOKEN_KEY, null);
    }
}
