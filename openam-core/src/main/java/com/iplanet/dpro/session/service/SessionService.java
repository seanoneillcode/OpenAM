/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2005 Sun Microsystems Inc. All Rights Reserved
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * https://opensso.dev.java.net/public/CDDLv1.0.html or
 * opensso/legal/CDDLv1.0.txt
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at opensso/legal/CDDLv1.0.txt.
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 *
 * $Id: SessionService.java,v 1.37 2010/02/03 03:52:54 bina Exp $
 *
 * Portions Copyrighted 2010-2016 ForgeRock AS.
 */
package com.iplanet.dpro.session.service;

import static org.forgerock.openam.audit.AuditConstants.EventName.*;
import static org.forgerock.openam.session.SessionConstants.*;
import static org.forgerock.openam.utils.Time.*;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.collections.Predicate;
import org.forgerock.guice.core.InjectorHolder;
import org.forgerock.openam.cts.CTSPersistentStore;
import org.forgerock.openam.cts.adapters.SessionAdapter;
import org.forgerock.openam.cts.api.tokens.Token;
import org.forgerock.openam.cts.api.tokens.TokenIdFactory;
import org.forgerock.openam.cts.exceptions.CoreTokenException;
import org.forgerock.openam.session.SessionCache;
import org.forgerock.openam.session.SessionConstants;
import org.forgerock.openam.session.SessionCookies;
import org.forgerock.openam.session.SessionPollerPool;
import org.forgerock.openam.session.SessionServiceURLService;
import org.forgerock.openam.session.service.SessionTimeoutHandler;
import org.forgerock.openam.sso.providers.stateless.StatelessSession;
import org.forgerock.openam.sso.providers.stateless.StatelessSessionManager;
import org.forgerock.openam.utils.CollectionUtils;
import org.forgerock.openam.utils.IOUtils;

import com.iplanet.am.util.SystemProperties;
import com.iplanet.dpro.session.Session;
import com.iplanet.dpro.session.SessionEvent;
import com.iplanet.dpro.session.SessionException;
import com.iplanet.dpro.session.SessionID;
import com.iplanet.dpro.session.TokenRestriction;
import com.iplanet.dpro.session.TokenRestrictionFactory;
import com.iplanet.dpro.session.service.cluster.ClusterMonitor;
import com.iplanet.dpro.session.service.cluster.MultiServerClusterMonitor;
import com.iplanet.dpro.session.service.cluster.SingleServerClusterMonitor;
import com.iplanet.dpro.session.share.SessionBundle;
import com.iplanet.dpro.session.share.SessionInfo;
import com.iplanet.dpro.session.utils.SessionInfoFactory;
import com.iplanet.services.naming.ServerEntryNotFoundException;
import com.iplanet.services.naming.WebtopNaming;
import com.iplanet.services.naming.WebtopNamingQuery;
import com.iplanet.sso.SSOException;
import com.iplanet.sso.SSOToken;
import com.iplanet.sso.SSOTokenManager;
import com.sun.identity.common.DNUtils;
import com.sun.identity.common.SearchResults;
import com.sun.identity.delegation.DelegationEvaluator;
import com.sun.identity.delegation.DelegationEvaluatorImpl;
import com.sun.identity.delegation.DelegationException;
import com.sun.identity.delegation.DelegationPermission;
import com.sun.identity.idm.AMIdentity;
import com.sun.identity.idm.IdRepoException;
import com.sun.identity.idm.IdType;
import com.sun.identity.idm.IdUtils;
import com.sun.identity.session.util.RestrictedTokenContext;
import com.sun.identity.shared.Constants;
import com.sun.identity.shared.debug.Debug;
import com.sun.identity.shared.stats.Stats;

/**
 * This class represents a Session Service.
 */
@Singleton
public class SessionService {

    /**
     * Service name for NotificationSets.
     */
    public static final String SESSION_SERVICE = "session";

    /**
      * Constants for delegated permissions.
      */
    private static final String PERMISSION_READ = "READ";
    private static final String PERMISSION_MODIFY = "MODIFY";
    private static final String PERMISSION_DELEGATE = "DELEGATE";

    private final Debug sessionDebug;
    private final Stats stats;
    private final SessionServiceConfig serviceConfig;
    private final SessionServerConfig serverConfig;
    private final SSOTokenManager ssoTokenManager;
    private final DsameAdminTokenProvider dsameAdminTokenProvider;
    private final MonitoringOperations monitoringOperations;
    private final SessionLogging sessionLogging;
    private final SessionAuditor sessionAuditor;
    private final InternalSessionFactory internalSessionFactory;
    private final HttpConnectionFactory httpConnectionFactory;
    private final SessionNotificationSender sessionNotificationSender;
    private final SessionMaxStats maxSessionStats; // TODO: Inject from Guice
    private final ExecutorService executorService = Executors.newCachedThreadPool(); // TODO: Inject from Guice
    private final Set remoteSessionSet;
    /**
     * AM Session Repository for Session Persistence.
     */
    private volatile CTSPersistentStore coreTokenService = null; // TODO: Inject from Guice

    /**
     * The following InternalSession is for the Authentication Service to use
     * It is only accessed by AuthD.initAuthSessions
     */
    private InternalSession authSession = null;
    private final TokenIdFactory tokenIdFactory;
    private final SessionAdapter tokenAdapter;
    private final SessionInfoFactory sessionInfoFactory;
    private final InternalSessionCache cache;

    private final SessionServiceURLService sessionServiceURLService;
    private final SessionCache sessionCache;
    private final SessionCookies sessionCookies;
    private final SessionPollerPool sessionPollerPool;
    private final StatelessSessionManager statelessSessionManager;

    /**
     * Reference to the ClusterMonitor instance. When server configuration changes which requires
     * a different instance (e.g. SFO changing state) then this AtomicReference will ensure
     * thread safety around the access to the ClusterMonitor instance.
     */
    private AtomicReference<ClusterMonitor> clusterMonitor = new AtomicReference<>();


    /**
     * Private Singleton Session Service.
     */
    @Inject
    private SessionService(
            final @Named(SessionConstants.SESSION_DEBUG) Debug sessionDebug,
            final @Named(SessionConstants.STATS_MASTER_TABLE) Stats stats,
            final SSOTokenManager ssoTokenManager,
            final DsameAdminTokenProvider dsameAdminTokenProvider,
            final SessionServerConfig serverConfig,
            final SessionServiceConfig serviceConfig,
            final MonitoringOperations monitoringOperations,
            final TokenIdFactory tokenIdFactory,
            final SessionAdapter tokenAdapter,
            final SessionInfoFactory sessionInfoFactory,
            final SessionLogging sessionLogging,
            final SessionAuditor sessionAuditor,
            final HttpConnectionFactory httpConnectionFactory,
            final InternalSessionCache internalSessionCache,
            final InternalSessionFactory internalSessionFactory,
            final SessionNotificationSender sessionNotificationSender,
            final SessionServiceURLService sessionServiceURLService,
            final SessionCache sessionCache,
            final SessionCookies sessionCookies,
            final SessionPollerPool sessionPollerPool,
            final StatelessSessionManager statelessSessionManager) {

        this.sessionDebug = sessionDebug;
        this.stats = stats;
        this.ssoTokenManager = ssoTokenManager;
        this.dsameAdminTokenProvider = dsameAdminTokenProvider;
        this.serverConfig = serverConfig;
        this.serviceConfig = serviceConfig;
        this.monitoringOperations = monitoringOperations;
        this.tokenIdFactory = tokenIdFactory;
        this.tokenAdapter = tokenAdapter;
        this.sessionInfoFactory = sessionInfoFactory;
        this.sessionLogging = sessionLogging;
        this.sessionAuditor = sessionAuditor;
        this.httpConnectionFactory = httpConnectionFactory;
        this.cache = internalSessionCache;
        this.internalSessionFactory = internalSessionFactory;
        this.statelessSessionManager = statelessSessionManager;
        this.remoteSessionSet = Collections.synchronizedSet(new HashSet());
        this.sessionNotificationSender = sessionNotificationSender;
        this.sessionServiceURLService = sessionServiceURLService;
        this.sessionCache = sessionCache;
        this.sessionCookies = sessionCookies;
        this.sessionPollerPool = sessionPollerPool;

        try {

            if (stats.isEnabled()) {
                maxSessionStats = new SessionMaxStats(cache, monitoringOperations, sessionNotificationSender, stats);
                stats.addStatsListener(maxSessionStats);
            } else {
                maxSessionStats = null;
            }

            if (coreTokenService == null) {
                coreTokenService = getRepository();
                sessionDebug.message("amTokenRepository Implementation: " +
                        ((coreTokenService == null) ? "None" : coreTokenService.getClass().getSimpleName()));
            }

        } catch (Exception ex) {

            sessionDebug.error("SessionService initialization failed.", ex);
            throw new IllegalStateException("SessionService initialization failed.", ex);

        }
    }

    /**
     * The ClusterMonitor state depends on whether the system is configured for
     * SFO or not. As such, this method is aware of the change in SFO state
     * and triggers a re-initialisation of the ClusterMonitor as required.
     *
     * Note, this method also acts as the lazy initialiser for the ClusterMonitor.
     *
     * Thread Safety: Uses atomic reference to ensure only one thread can modify
     * the reference at any one time.
     *
     * @return A non null instance of the current ClusterMonitor.
     * @throws SessionException If there was an error initialising the ClusterMonitor.
     */
    private ClusterMonitor getClusterMonitor() throws SessionException {
        if (!isClusterMonitorValid()) {
            try {
                ClusterMonitor previous = clusterMonitor.getAndSet(resolveClusterMonitor());
                if (previous != null) {
                    sessionDebug.message("Previous ClusterMonitor shutdown: {}", previous.getClass().getSimpleName());
                    previous.shutdown();
                }
                sessionDebug.message("ClusterMonitor initialised: {}", clusterMonitor.get().getClass().getSimpleName());
            } catch (Exception e) {
                sessionDebug.error("Failed to initialise ClusterMonitor", e);
            }
        }


        ClusterMonitor monitor = clusterMonitor.get();
        if (monitor == null) {
            throw new SessionException("Failed to initialise ClusterMonitor");
        }
        return monitor;
    }

    /**
     * @return True if the ClusterMonitor is valid for the current SessionServiceConfiguration.
     */
    private boolean isClusterMonitorValid() {
        // Handles lazy init case
        if (clusterMonitor.get() == null) {
            return false;
        }

        Class<? extends ClusterMonitor> monitorClass = clusterMonitor.get().getClass();
        if (isPartOfCluster()) {
            return monitorClass.isAssignableFrom(MultiServerClusterMonitor.class);
        } else {
            return monitorClass.isAssignableFrom(SingleServerClusterMonitor.class);
        }
    }


    /**
     * Resolves the appropriate instance of ClusterMonitor to initialise.
     *
     * @return A non null ClusterMonitor based on service configuration.
     * @throws Exception If there was an unexpected error in initialising the MultiClusterMonitor.
     */
    private ClusterMonitor resolveClusterMonitor() throws Exception {
        if (isPartOfCluster()) {
        return new MultiServerClusterMonitor(this, sessionDebug, serviceConfig, serverConfig);
        } else {
            return new SingleServerClusterMonitor();
        }
    }

    private boolean isPartOfCluster() {
        WebtopNamingQuery query = new WebtopNamingQuery();
        try {
            String serverId =  query.getAMServerID();
            String siteId = query.getSiteID(serverId);
            return siteId != null;
        } catch (ServerEntryNotFoundException e) {
            return false;
        }

    }

    /**
     * Returns the Internal Session used by the Auth Services.
     *
     * @param domain      Authentication Domain
     */
    // TODO: Pull out into  new AuthSessionFactory class? This method is only called by AuthD.initAuthSessions
    //       and AuthD then keeps a local copy of the returned Session. The authSession reference may
    //       be getting stored as a field on this object to avoid it being garbage collected? Since
    //       we're not using authSession anywhere else in this class and it isn't referenced by the returned
    //       Session, it probably wouldn't matter if it was garbage collected.
    public Session getAuthenticationSession(String domain) {
        try {
            if (authSession == null) {
                // Create a special InternalSession for Authentication Service
                authSession = getServiceSession(domain);
            }
            return authSession != null ? sessionCache.getSession(authSession.getID()) : null;
        } catch (Exception e) {
            sessionDebug.error("Error creating service session", e);
            return null;
        }
    }

    /**
     * Returns the Internal Session which can be used by services
     *
     * @param domain      Authentication Domain
     */
    // TODO: Also pull this method out into new AuthSessionFactory class
    private InternalSession getServiceSession(String domain) {
        try {
            // Create a special InternalSession which can be used as
            // service token
            // note that this session does not need failover protection
            // as its scope is only this same instance
            // more over creating an HTTP session by making a self-request
            // results in dead-lock if called from within synchronized
            // section in getSessionService()
            InternalSession session = internalSessionFactory.newInternalSession(domain, false);
            session.setType(APPLICATION_SESSION);
            session.setClientID(dsameAdminTokenProvider.getDsameAdminDN());
            session.setClientDomain(domain);
            session.setNonExpiring();
            session.setState(VALID);
            incrementActiveSessions();
            return session;
        } catch (Exception e) {
            sessionDebug.error("Error creating service session", e);
            return null;
        }
    }

    /**
     * Returns the restricted token
     *
     * @param masterSid   master session id
     * @param restriction TokenRestriction Object
     * @return restricted token id
     * @throws SessionException
     */

    public String getRestrictedTokenId(String masterSid, TokenRestriction restriction) throws SessionException {
        SessionID sid = new SessionID(masterSid);

        // first try
        String hostServerID = getCurrentHostServer(sid);

        if (!serverConfig.isLocalServer(hostServerID)) {
            if (!checkServerUp(hostServerID)) {
                hostServerID = getCurrentHostServer(sid);
            }
            if (!serverConfig.isLocalServer(hostServerID)) {
                String token = getRestrictedTokenIdRemotely(
                        sessionServiceURLService.getSessionServiceURL(hostServerID), sid, restriction);
                if (token == null) {
                    // TODO consider one retry attempt
                    throw new SessionException(SessionBundle.getString("invalidSessionID") + masterSid);
                } else {
                    return token;
                }
            }
        }
        return doGetRestrictedTokenId(sid, restriction);
    }

    /**
     * This method is expected to only be called for local sessions
     */
    String doGetRestrictedTokenId(SessionID masterSid, TokenRestriction restriction) throws SessionException {
        if (statelessSessionManager.containsJwt(masterSid)) {
            // Stateless sessions do not (yet) support restricted tokens
            throw new UnsupportedOperationException(StatelessSession.RESTRICTED_TOKENS_UNSUPPORTED);
        }

        // locate master session
        InternalSession session = cache.getBySessionID(masterSid);
        if (session == null) {
            session = recoverSession(masterSid);
            if (session == null) {
                throw new SessionException(SessionBundle.getString("invalidSessionID") + masterSid);
            }
        }
        sessionInfoFactory.validateSession(session, masterSid);
        // attempt to reuse the token if restriction is the same
        SessionID restrictedSid = session.getRestrictedTokenForRestriction(restriction);
        if (restrictedSid == null) {
            restrictedSid = session.getID().generateRelatedSessionID(serverConfig);
            SessionID previousValue = session.addRestrictedToken(restrictedSid, restriction);
            if (previousValue == null) {
                cache.put(session);
            } else {
                restrictedSid = previousValue;
            }
        }
        return restrictedSid.toString();
    }

    public InternalSession newInternalSession(String domain, boolean stateless) {
        return internalSessionFactory.newInternalSession(domain, stateless);
    }

    /**
     * Removes the Internal Session from the Internal Session table.
     *
     * @param sid Session ID
     */
    InternalSession removeInternalSession(SessionID sid) {
        if (sid == null)
            return null;
        InternalSession session = cache.remove(sid);

        if (session != null) {
            remoteSessionSet.remove(sid);
            session.cancel();
            // Session Constraint
            if (session.getState() == VALID) {
                decrementActiveSessions();
            }

            if (session.isStored()) {
                session.delete();
            }

            session.delete();
        }

        return session;
    }

    void deleteFromRepository(InternalSession session) {
        try {
            String tokenId = tokenIdFactory.toSessionTokenId(session.getID());
            getRepository().delete(tokenId);
        } catch (Exception e) {
            sessionDebug.error("SessionService : failed deleting session ",
                    e);
        }
    }

    /**
     * This method checks if Internal session is already present locally
     *
     * @param sid
     * @return a boolean
     */
    public boolean isSessionPresent(SessionID sid) {
        boolean isPresent = cache.getBySessionID(sid) != null
                || cache.getByRestrictedID(sid) != null
                || cache.getByHandle(sid.toString()) != null;

        return isPresent;
    }

    /**
     * Checks whether current session should be considered local (so that local
     * invocations of SessionService methods are to be used) and if local and
     * Session Failover is enabled will recover the Session if the Session is
     * not found locally.
     *
     * @return a boolean
     */
    public boolean checkSessionLocal(SessionID sid) throws SessionException {
        if (statelessSessionManager.containsJwt(sid)) {
            // Stateless sessions are not stored in memory and so are not local to any server.
            return false;
        } else if (isSessionPresent(sid)) {
            return true;
        } else {
            String hostServerID = getCurrentHostServer(sid);
            if (serverConfig.isLocalServer(hostServerID)) {
                if (recoverSession(sid) == null) {
                    throw new SessionException(SessionBundle.getString("sessionNotObtained"));
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the Internal Session corresponding to a Session ID.
     *
     * @param sid Session Id
     */
    public InternalSession getInternalSession(SessionID sid) {

        if (sid == null) {
            return null;
        }
        // check if sid is actually a handle return null (in order to prevent from assuming recovery case)
        if (sid.isSessionHandle()) {
            return null;
        }
        return cache.getBySessionID(sid);
    }

    /**
     * Quick access to the total size of the remoteSessionSet.
     *
     * @return the size of the sessionTable
     */
    public int getRemoteSessionCount() {
        return remoteSessionSet.size();
    }

    /**
     * Quick access to the total size of the sessionTable (internal sessions), including
     * both invalid and valid tokens in the count, as well as 'hidden' sessions used by OpenAM itself.
     *
     * @return the size of the sessionTable
     */
    public int getInternalSessionCount() {
        return cache.size();
    }

    /**
     * Returns the Internal Session corresponding to a session handle.
     *
     * @param shandle Session Id
     * @return Internal Session corresponding to a session handle
     */
    public InternalSession getInternalSessionByHandle(String shandle) {
        return cache.getByHandle(shandle);
    }

    /**
     * As opposed to locateSession() this one accepts normal or restricted token
     * This is expected to be only called once the session is detected as local
     *
     * @param token
     * @return
     */
    private InternalSession resolveToken(SessionID token) throws SessionException {
        InternalSession sess = cache.getBySessionID(token);
        if (sess == null) {
            sess = resolveRestrictedToken(token, true);
        }
        if (sess == null) {
            throw new SessionException(SessionBundle.getString("invalidSessionID") + token.toString());
        }
        return sess;
    }

    private InternalSession resolveRestrictedToken(SessionID token,
                                                   boolean checkRestriction) throws SessionException {
        InternalSession session = cache.getByRestrictedID(token);
        if (session == null) {
            return null;
        }
        if (checkRestriction) {
            try {
                TokenRestriction restriction = session.getRestrictionForToken(token);
                if (restriction != null && !restriction.isSatisfied(RestrictedTokenContext.getCurrent())) {
                    throw new SessionException(SessionBundle.rbName, "restrictionViolation", null);
                }
            } catch (SessionException se) {
                throw se;
            } catch (Exception e) {
                throw new SessionException(e);
            }
        }
        return session;
    }

    /**
     * Get all valid Internal Sessions.
     */
    private List<InternalSession> getValidInternalSessions() {

        synchronized (cache) {
            List<InternalSession> sessions = new ArrayList<InternalSession>(cache.getAllSessions());
            org.apache.commons.collections.CollectionUtils.filter(sessions, new Predicate() {
                @Override
                public boolean evaluate(Object o) {
                    InternalSession s = (InternalSession) o; // Apache Commons is old.
                    if (s.getState() != VALID) {
                        return false;
                    }
                    if (s.isAppSession() && !serviceConfig.isReturnAppSessionEnabled()) {
                        return false;
                    }
                    return true;
                }
            });
            return sessions;
        }
    }

    /**
     * Get all valid Internal Sessions matched with pattern.
     */
    private SearchResults<InternalSession> getValidInternalSessions(String pattern)
            throws SessionException {
        Set<InternalSession> sessions = new HashSet<>();
        int errorCode = SearchResults.SUCCESS;

        if (pattern == null) {
            pattern = "*";
        }

        try {
            long startTime = currentTimeMillis();

            pattern = pattern.toLowerCase();
            List<InternalSession> allValidSessions = getValidInternalSessions();
            boolean matchAll = pattern.equals("*");

            for (InternalSession sess : allValidSessions) {
                if (!matchAll) {
                    // For application sessions, the client ID
                    // will not be in the DN format but just uid.
                    String clientID = (!sess.isAppSession()) ?
                            DNUtils.DNtoName(sess.getClientID()) :
                            sess.getClientID();

                    if (clientID == null) {
                        continue;
                    } else {
                        clientID = clientID.toLowerCase();
                    }

                    if (!matchFilter(clientID, pattern)) {
                        continue;
                    }
                }

                if (sessions.size() == serviceConfig.getMaxSessionListSize()) {
                    errorCode = SearchResults.SIZE_LIMIT_EXCEEDED;
                    break;
                }
                sessions.add(sess);

                if ((currentTimeMillis() - startTime) >=
                        serviceConfig.getSessionRetrievalTimeout()) {
                    errorCode = SearchResults.TIME_LIMIT_EXCEEDED;
                    break;
                }
            }
        } catch (Exception e) {
            sessionDebug.error("SessionService : "
                    + "Unable to get Session Information ", e);
            throw new SessionException(e);
        }
        return new SearchResults<>(sessions.size(), sessions, errorCode);
    }

    /**
     * Returns true if the given pattern is contained in the string.
     *
     * @param string  to examine
     * @param pattern to match
     * @return true if string matches <code>filter</code>
     */
    private boolean matchFilter(String string, String pattern) {
        if (pattern.equals("*") || pattern.equals(string)) {
            return true;
        }

        int length = pattern.length();
        int wildCardIndex = pattern.indexOf("*");

        if (wildCardIndex >= 0) {
            String patternSubStr = pattern.substring(0, wildCardIndex);

            if (!string.startsWith(patternSubStr, 0)) {
                return false;
            }

            int beginIndex = patternSubStr.length() + 1;
            int stringIndex = 0;

            if (wildCardIndex > 0) {
                stringIndex = beginIndex;
            }

            String sub = pattern.substring(beginIndex, length);

            while ((wildCardIndex = pattern.indexOf("*", beginIndex)) != -1) {
                patternSubStr = pattern.substring(beginIndex, wildCardIndex);

                if (string.indexOf(patternSubStr, stringIndex) == -1) {
                    return false;
                }

                beginIndex = wildCardIndex + 1;
                stringIndex = stringIndex + patternSubStr.length() + 1;
                sub = pattern.substring(beginIndex, length);
            }

            if (string.endsWith(sub)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Destroy a Internal Session, whose session id has been specified.
     *
     * @param sid
     */
    public void destroyInternalSession(SessionID sid) {
        InternalSession sess = removeInternalSession(sid);
        if (sess != null && sess.getState() != INVALID) {
            signalRemove(sess, SessionEvent.DESTROY);
            sessionAuditor.auditActivity(sess.toSessionInfo(), AM_SESSION_DESTROYED);
        }
        sessionCache.removeSID(sid);
    }

    /**
     * Logout a Internal Session, whose session id has been specified.
     *
     * @param sid
     */
    public void logoutInternalSession(SessionID sid) {
        InternalSession sess = removeInternalSession(sid);
        if (sess != null) {
            sess.delete();
        }
        if (sess != null && sess.getState() != INVALID) {
            signalRemove(sess, SessionEvent.LOGOUT);
            sessionAuditor.auditActivity(sess.toSessionInfo(), AM_SESSION_LOGGED_OUT);
        }
    }

    /**
     * Simplifies the signalling that a Session has been removed.
     * @param session Non null InternalSession.
     * @param event An integrate from the SessionEvent class.
     */
    private void signalRemove(InternalSession session, int event) {
        sessionLogging.logEvent(session.toSessionInfo(), event);
        session.setState(DESTROYED);
        sendEvent(session, event);
    }

    /**
     * Decrements number of active sessions
     */
    public void decrementActiveSessions() {
        monitoringOperations.decrementActiveSessions();
    }

    /**
     * Increments number of active sessions
     */
    public void incrementActiveSessions() {
        monitoringOperations.incrementActiveSessions();
    }

    // The following methods are corresponding to the session requests
    // defined in the Session DTD. Those methods are being called
    // in SessionRequestHandler class

    /**
     * Returns the Session information.
     *
     * @param sid
     * @param reset
     * @throws SessionException
     */
    public SessionInfo getSessionInfo(SessionID sid, boolean reset)
            throws SessionException {

        if (statelessSessionManager.containsJwt(sid)) {
            return statelessSessionManager.getSessionInfo(sid);
        }
        // Session is not stateless, continue through the code...

        InternalSession sess = resolveToken(sid);
        if (reset) {
            sess.setLatestAccessTime();
        }
        return sessionInfoFactory.getSessionInfo(sess, sid);
    }

    /**
     * Gets all valid Internal Sessions, depending on the value of the user's
     * preferences.
     *
     * @param s
     * @throws SessionException
     */
    public SearchResults<SessionInfo> getValidSessions(Session s, String pattern) throws SessionException {
        if (s.getState(false) != VALID) {
            throw new SessionException(SessionBundle
                    .getString("invalidSessionState")
                    + s.getID().toString());
        }

        try {
            AMIdentity user = getUser(s);
            Set orgList = user.getAttribute("iplanet-am-session-get-valid-sessions");
            if (orgList == null) {
                orgList = Collections.EMPTY_SET;
            }

            SearchResults<InternalSession> sessions = getValidInternalSessions(pattern);
            Set<SessionInfo> infos = new HashSet<>(sessions.getSearchResults().size());

            // top level admin gets all sessions
            boolean isTopLevelAdmin = hasTopLevelAdminRole(s);

            for (InternalSession sess : sessions.getSearchResults()) {
                if (isTopLevelAdmin || orgList.contains(sess.getClientDomain())) {
                    SessionInfo info = sess.toSessionInfo();
                    // replace session id with session handle to prevent impersonation
                    info.setSessionID(sess.getSessionHandle());
                    infos.add(info);
                }
            }
            return new SearchResults<>(sessions.getTotalResultCount(), infos, sessions.getErrorCode());
        } catch (Exception e) {
            throw new SessionException(e);
        }
    }

    /**
     * Destroy a Internal Session, depending on the value of the user's
     * preferences.
     *
     * @param requester
     * @param sid
     * @throws SessionException
     */
    public void destroySession(Session requester, SessionID sid) throws SessionException {
        if (sid == null) {
            return;
        }

        InternalSession sess = getInternalSession(sid);

        if (sess == null) {
            // let us check if the argument is a session handle
            sess = getInternalSessionByHandle(sid.toString());
        }

        if (sess != null) {
            sid = sess.getID();
            checkPermissionToDestroySession(requester, sid);
            destroyInternalSession(sid);
        }
    }

    /**
     * Checks if the requester has the necessary permission to destroy the provided session. The user has the necessary
     * privileges if one of these conditions is fulfilled:
     * <ul>
     *  <li>The requester attempts to destroy its own session.</li>
     *  <li>The requester has top level admin role (having read/write access to any service configuration in the top
     *  level realm).</li>
     *  <li>The session's client domain is listed in the requester's profile under the
     *  <code>iplanet-am-session-destroy-sessions service</code> service attribute.</li>
     * </ul>
     *
     * @param requester The requester's session.
     * @param sid The session to destroy.
     * @throws SessionException If none of the conditions above is fulfilled, i.e. when the requester does not have the
     * necessary permissions to destroy the session.
     */
    public void checkPermissionToDestroySession(Session requester, SessionID sid) throws SessionException {
        if (requester.getState(false) != VALID) {
            throw new SessionException(SessionBundle.getString("invalidSessionState") + sid.toString());
        }
        try {
            // a session can destroy itself or super admin can destroy anyone including another super admin
            if (requester.getID().equals(sid) || hasTopLevelAdminRole(requester)) {
                return;
            }

            AMIdentity user = getUser(requester);
            Set<String> orgList = user.getAttribute("iplanet-am-session-destroy-sessions");
            if (!orgList.contains(requester.getClientDomain())) {
                throw new SessionException(SessionBundle.rbName, "noPrivilege", null);
            }
        } catch (Exception e) {
            throw new SessionException(e);
        }
    }

    /**
     * Logout the user.
     *
     * @param sid
     * @throws SessionException
     */
    public void logout(SessionID sid) throws SessionException {
        if (sid == null || sid.isSessionHandle()) {
            throw new SessionException(SessionBundle.getString("invalidSessionID") + sid);
        }
        //if the provided sid was a restricted token, resolveToken will always validate the restriction, so there is no
        //need to check restrictions here.
        InternalSession session = resolveToken(sid);
        logoutInternalSession(session.getID());
    }

    /**
     * Adds listener to a Internal Sessions.
     *
     * @param sid Session ID
     * @param url
     * @throws SessionException Session is null OR the Session is invalid
     */
    public void addSessionListener(SessionID sid, String url) throws SessionException {
        // We do not support session listeners for stateless sessions.
        if (statelessSessionManager.containsJwt(sid)) {
            return;
        }

        InternalSession session = resolveToken(sid);
        if (session.getState() == INVALID) {
            throw new SessionException(SessionBundle.getString("invalidSessionState") + sid.toString());
        }
        if (!sid.equals(session.getID()) && session.getRestrictionForToken(sid) == null) {
            throw new IllegalArgumentException("Session id mismatch");
        }
        session.addSessionEventURL(url, sid);
    }

    public int getNotificationQueueSize() {
        return sessionNotificationSender.getNotificationQueueSize();
    }

    public void sendEvent(InternalSession internalSession, int eventType) {
        sessionNotificationSender.sendEvent(internalSession, eventType);
    }

    /**
     * Sets internal property to the Internal Session.
     *
     * @param sid
     * @param name
     * @param value
     * @throws SessionException
     */
    public void setProperty(SessionID sid, String name, String value)
            throws SessionException {
        resolveToken(sid).putProperty(name, value);
    }

    /**
     * Given a restricted token, returns the SSOTokenID of the master token
     * can only be used if the requester is an app token
     *
     * @param s            Must be an app token
     * @param restrictedID The SSOTokenID of the restricted token
     * @return The SSOTokenID string of the master token
     * @throws SSOException If the master token cannot be dereferenced
     */
    public String deferenceRestrictedID(Session s, String restrictedID)
            throws SessionException {
        SessionID rid = new SessionID(restrictedID);

        //first try
        String hostServerID = getCurrentHostServer(rid);

        if (!serverConfig.isLocalServer(hostServerID)) {
            if (!checkServerUp(hostServerID)) {
                hostServerID = getCurrentHostServer(rid);
            }

            if (!serverConfig.isLocalServer(hostServerID)) {
                String masterID = deferenceRestrictedIDRemotely(
                        s, sessionServiceURLService.getSessionServiceURL(hostServerID), rid);

                if (masterID == null) {
                    //TODO consider one retry attempt
                    throw new SessionException("unable to get master id remotely " + rid);
                } else {
                    return masterID;
                }
            }
        }

        return resolveRestrictedToken(rid, false).getID().toString();
    }

    // sjf bug 6797573
    public String deferenceRestrictedIDRemotely(Session s, URL hostServerID, SessionID sessionID) {
        DataInputStream in = null;
        DataOutputStream out = null;

        try {
            String query = "?" + GetHttpSession.OP + "=" + GetHttpSession.DEREFERENCE_RESTRICTED_TOKEN_ID;

            URL url = serverConfig.createServerURL(hostServerID, "GetHttpSession" + query);

            HttpURLConnection conn = httpConnectionFactory.createSessionAwareConnection(url, s.getID(), null);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/octet-stream");

            ByteArrayOutputStream bs = new ByteArrayOutputStream();
            DataOutputStream ds = new DataOutputStream(bs);

            ds.writeUTF(sessionID.toString());
            ds.flush();
            ds.close();

            byte[] getRemotePropertyString = bs.toByteArray();

            conn.setRequestProperty("Content-Length",
                    Integer.toString(getRemotePropertyString.length));

            out = new DataOutputStream(conn.getOutputStream());

            out.write(getRemotePropertyString);
            out.close();
            out = null;

            in = new DataInputStream(conn.getInputStream());

            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }

            return in.readUTF();

        } catch (Exception ex) {
            sessionDebug.error("Failed to dereference the master token remotely", ex);
        } finally {
            IOUtils.closeIfNotNull(in);
            IOUtils.closeIfNotNull(out);
        }

        return null;
    }

    /**
     * Sets external property in the Internal Session as long as it is not
     * protected
     *
     * @param clientToken - Token of the client setting external property.
     * @param sid
     * @param name
     * @param value
     * @throws SessionException
     */
    public void setExternalProperty(SSOToken clientToken, SessionID sid,
                                    String name, String value)
            throws SessionException {
        resolveToken(sid).putExternalProperty(clientToken, name, value);
    }

    /**
     * Utility helper method to obtain session repository reference
     *
     * @return reference to session repository
     */
    // TODO: Use coreTokenService field directly since it should be set in constructor?
    protected CTSPersistentStore getRepository() {
        if (coreTokenService == null) {
            coreTokenService = InjectorHolder.getInstance(CTSPersistentStore.class);
        }
        return coreTokenService;
    }

    /**
     * This is a key method for "internal request routing" mode It determines
     * the server id which is currently hosting session identified by sid. In
     * "internal request routing" mode, this method also has a side effect of
     * releasing a session which no longer "belongs locally" (e.g., due to
     * primary server instance restart)
     *
     * @param sid session id
     * @return server id for the server instance determined to be the current
     *         host
     * @throws SessionException
     */
    public String getCurrentHostServer(SessionID sid) throws SessionException {

        String serverId = getClusterMonitor().getCurrentHostServer(sid);

        // if we have a local session replica, discard it as hosting server instance is not supposed to be local
        if (!serverConfig.isLocalServer(serverId)) {
            // actively clean up duplicates
            handleReleaseSession(sid);
        }
        return serverId;
    }

    /**
     * Actively check if server identified by serverID is up
     *
     * @param serverID server id
     * @return true if server is up, false otherwise
     */
    public boolean checkServerUp(String serverID) {
        try {
            return getClusterMonitor().checkServerUp(serverID);
        } catch (SessionException e) {
            sessionDebug.error("Failed to check Server Up for {0}", serverID, e);
            return false;
        }
    }

    /**
     * Indicates that the Site is up.
     *
     * @param siteId A possibly null Site Id.
     * @return True if the Site is up, False if it failed to respond to a query.
     */
    public boolean isSiteUp(String siteId) {
        try {
            return getClusterMonitor().isSiteUp(siteId);
        } catch (SessionException e) {
            sessionDebug.error("Failed to check isSiteUp for {0}", siteId, e);
            return false;
        }
    }

    /**
     * This method will execute all the globally set session timeout handlers
     * with the corresponding timeout event simultaniously.
     *
     * @param sessionId  The timed out sessions ID
     * @param changeType Type of the timeout event: IDLE_TIMEOUT (1) or MAX_TIMEOUT (2)
     */
    void execSessionTimeoutHandlers(final SessionID sessionId, final int changeType) {
        // Take snapshot of reference to ensure atomicity.
        final Set<String> handlers = serviceConfig.getTimeoutHandlers();

        if (!handlers.isEmpty()) {
            try {
                final SSOToken token = ssoTokenManager.createSSOToken(sessionId.toString());
                final List<Future<?>> futures = new ArrayList<Future<?>>();
                final CountDownLatch latch = new CountDownLatch(handlers.size());

                for (final String clazz : handlers) {
                    Runnable timeoutTask = new Runnable() {

                        public void run() {
                            try {
                                SessionTimeoutHandler handler =
                                        Class.forName(clazz).asSubclass(
                                                SessionTimeoutHandler.class).newInstance();
                                switch (changeType) {
                                    case SessionEvent.IDLE_TIMEOUT:
                                        handler.onIdleTimeout(token);
                                        break;
                                    case SessionEvent.MAX_TIMEOUT:
                                        handler.onMaxTimeout(token);
                                        break;
                                }
                            } catch (Exception ex) {
                                if (Thread.interrupted()
                                        || ex instanceof InterruptedException
                                        || ex instanceof InterruptedIOException) {
                                    sessionDebug.warning("Timeout Handler was interrupted");
                                } else {
                                    sessionDebug.error("Error while executing the following session timeout handler: " + clazz, ex);
                                }
                            } finally {
                                latch.countDown();
                            }
                        }
                    };
                    futures.add(executorService.submit(timeoutTask)); // This should not throw any exceptions.
                }

                // Wait 1000ms for all handlers to complete.
                try {
                    latch.await(1000, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    // This should never happen: we can't handle it here, so propagate it.
                    Thread.currentThread().interrupt();
                }

                for (Future<?> future : futures) {
                    if (!future.isDone()) {
                        // It doesn't matter really if the future completes between isDone and cancel.
                        future.cancel(true); // Interrupt.
                    }
                }
            } catch (SSOException ssoe) {
                sessionDebug.warning("Unable to construct SSOToken for executing timeout handlers", ssoe);
            }
        }
    }

    /**
     * Returns the User of the Session
     *
     * @param session Session
     * @throws SessionException
     * @throws SSOException
     */
    private AMIdentity getUser(Session session) throws SessionException, SSOException {
        SSOToken ssoSession = ssoTokenManager.createSSOToken(session.getID().toString());
        AMIdentity user = null;
        try {
            user = IdUtils.getIdentity(ssoSession);
        } catch (IdRepoException e) {
            sessionDebug.error("SessionService: failed to get the user's identity object", e);
        }
        return user;
    }

    /**
     * Returns true if the user has top level admin role
     *
     * @param session Session.
     * @throws SessionException
     * @throws SSOException
     */
    private boolean hasTopLevelAdminRole(Session session) throws SessionException, SSOException {
        SSOToken ssoSession = ssoTokenManager.createSSOToken(session.getID().toString());
        return hasTopLevelAdminRole(ssoSession, session.getClientID());
    }

    /**
     * Returns true if the user has top level admin role
     *
     * @param tokenUsedForSearch Single Sign on token used to do the search.
     * @param clientID           Client ID of the login user.
     * @throws SessionException
     * @throws SSOException
     */
    private boolean hasTopLevelAdminRole(SSOToken tokenUsedForSearch, String clientID)
            throws SessionException, SSOException {

        boolean topLevelAdmin = false;
        Set actions = CollectionUtils.asSet(PERMISSION_READ, PERMISSION_MODIFY, PERMISSION_DELEGATE);
        try {
            DelegationPermission perm = new DelegationPermission(
                    "/", "*", "*", "*", "*", actions, Collections.EMPTY_MAP);
            DelegationEvaluator evaluator = new DelegationEvaluatorImpl();
            topLevelAdmin = evaluator.isAllowed(tokenUsedForSearch, perm, Collections.EMPTY_MAP);
        } catch (DelegationException de) {
            sessionDebug.error("SessionService.hasTopLevelAdminRole: failed to check the delegation permission.", de);
        }
        return topLevelAdmin;
    }

    /**
     * Returns true if the user is super user
     *
     * @param uuid the uuid of the login user
     */
    public boolean isSuperUser(String uuid) {
        boolean isSuperUser = false;
        try {
            // Get the AMIdentity Object for super user 
            AMIdentity adminUserId = null;
            String adminUser = SystemProperties.get(Constants.AUTHENTICATION_SUPER_USER);
            if (adminUser != null) {
                adminUserId = new AMIdentity(dsameAdminTokenProvider.getAdminToken(), adminUser, IdType.USER, "/", null);
            }
            //Get the AMIdentity Object for login user
            AMIdentity user = IdUtils.getIdentity(dsameAdminTokenProvider.getAdminToken(), uuid);
            //Check for the equality
            isSuperUser = adminUserId.equals(user);

        } catch (SSOException ssoe) {
            sessionDebug.error("SessionService.isSuperUser: Cannot get the admin token for this operation.");

        } catch (IdRepoException idme) {
            sessionDebug.error("SessionService.isSuperUser: Cannot get the user identity.");
        }

        if (sessionDebug.messageEnabled()) {
            sessionDebug.message("SessionService.isSuperUser: " + isSuperUser);
        }

        return isSuperUser;
    }

    /**
     * Removes InternalSession from the session table so that another server
     * instance can be an owner This is the server side of distributed
     * invocation initiated by calling releaseSession()
     *
     * @param sid session id of the session migrated
     */
    int handleReleaseSession(SessionID sid) {
        // switch to non-local mode for cached client side session image
        if (sessionCache.hasSession(sid)) {
            sessionCache.readSession(sid).setSessionIsLocal(false);
        }

        InternalSession is = cache.remove(sid);
        if (is != null) {
            is.cancel();
        } else {
            if (sessionDebug.messageEnabled()) {
                sessionDebug.message("releaseSession: session not found " + sid);
            }
        }
        return HttpURLConnection.HTTP_OK;
    }

    /**
     * This will recover the specified session from the repository, and add it to the cache.
     * Returns null if no session was recovered.
     * @param sid Session ID
     */
    InternalSession recoverSession(SessionID sid) {

        InternalSession sess = null;

        try {
            String tokenId = tokenIdFactory.toSessionTokenId(sid);
            Token token = getRepository().read(tokenId);
            if (token == null) {
                return null;
            }
            /**
             * As a side effect of deserialising an InternalSession, we must trigger
             * the InternalSession to reschedule its timing task to ensure it
             * maintains the session expiry function.
             */
            sess = tokenAdapter.fromToken(token);
            sess.setSessionServiceDependencies(
                    this, serviceConfig, sessionLogging, sessionAuditor, sessionDebug);
            sess.scheduleExpiry();
            updateSessionMaps(sess);

        } catch (CoreTokenException e) {
            sessionDebug.error("Failed to retrieve new session", e);
        }
        return sess;
    }

    /**
     * Utility used to updated various cross-reference mapping data structures
     * associated with sessions up-to-date when sessions are being recovered
     * after server instance failure
     *
     * @param sess session object
     */
    private void updateSessionMaps(InternalSession sess) {
        if (sess == null)
            return;

        if (destroySessionIfNecessary(sess))
            return;

        sess.putProperty(sessionCookies.getLBCookieName(), serverConfig.getLBCookieValue());
        SessionID sid = sess.getID();
        String primaryID = sid.getExtension().getPrimaryID();
        if (!serverConfig.isLocalServer(primaryID)) {
            remoteSessionSet.add(sid);
        }
        cache.put(sess);
    }

    /**
     * function to remove remote sessions when primary server is up
     */
    public void cleanUpRemoteSessions() {
        synchronized (remoteSessionSet) {
            for (Iterator iter = remoteSessionSet.iterator(); iter.hasNext(); ) {
                SessionID sid = (SessionID) iter.next();
                // getCurrentHostServer automatically releases local
                // session replica if it does not belong locally
                String hostServer = null;
                try {
                    hostServer = getCurrentHostServer(sid);
                } catch (Exception ex) {
                }
                // if session does not belong locally remove it
                if (!serverConfig.isLocalServer(hostServer)) {
                    iter.remove();
                }
            }
        }
    }

    /**
     * Utility method to check if session has to be destroyed and to remove it
     * if so.
     *
     * @param sess session object
     * @return true if session should (and has !) been destroyed
     */
    boolean destroySessionIfNecessary(InternalSession sess) {
        boolean wasDestroyed = false;
        try {
            wasDestroyed = sess.destroyIfNecessary();
        } catch (Exception ex) {
            sessionDebug.error("Exception in session destroyIfNecessary() : ", ex);
            wasDestroyed = true;
        }

        if (wasDestroyed) {
            try {
                removeInternalSession(sess.getID());
            } catch (Exception ex) {
                sessionDebug.error("Exception while removing session : ", ex);
            }
        }
        return wasDestroyed;
    }

    /**
     * This method is used to create restricted token
     *
     * @param owner       server instance URL
     * @param masterSid   SessionID
     * @param restriction restriction
     */
    private String getRestrictedTokenIdRemotely(URL owner, SessionID masterSid, TokenRestriction restriction) {

        DataInputStream in = null;
        DataOutputStream out = null;

        try {
            String query = "?" + GetHttpSession.OP + "=" + GetHttpSession.GET_RESTRICTED_TOKEN_OP;

            URL url = serverConfig.createServerURL(owner, "GetHttpSession" + query);

            HttpURLConnection conn = httpConnectionFactory.createSessionAwareConnection(url, masterSid, null);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/octet-stream");

            ByteArrayOutputStream bs = new ByteArrayOutputStream();
            DataOutputStream ds = new DataOutputStream(bs);

            ds.writeUTF(TokenRestrictionFactory.marshal(restriction));
            ds.flush();
            ds.close();

            byte[] marshalledRestriction = bs.toByteArray();

            conn.setRequestProperty("Content-Length", Integer.toString(marshalledRestriction.length));

            out = new DataOutputStream(conn.getOutputStream());

            out.write(marshalledRestriction);
            out.close();
            out = null;

            in = new DataInputStream(conn.getInputStream());

            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }

            return in.readUTF();

        } catch (Exception ex) {
            sessionDebug
                    .error("Failed to create restricted token remotely", ex);
        } finally {
            IOUtils.closeIfNotNull(in);
            IOUtils.closeIfNotNull(out);
        }

        return null;
    }

    /**
     * This method is the "server side" of the getRestrictedTokenIdRemotely()
     *
     * @param masterSid   SessionID
     * @param restriction restriction
     */
    String handleGetRestrictedTokenIdRemotely(SessionID masterSid,
                                              TokenRestriction restriction) {
        try {
            return doGetRestrictedTokenId(masterSid, restriction);
        } catch (Exception ex) {
            sessionDebug.error("Failed to create restricted token remotely", ex);
        }
        return null;
    }

    /**
     * Writes the current state of the token down to the repository.
     *
     * @param session Session Object
     */
    void save(InternalSession session) {
        // do not save sessions which never expire
        if (!session.willExpire()) {
            return;
        }
        try {
            getRepository().update(tokenAdapter.toToken(session));
        } catch (Exception e) {
            sessionDebug.error("SessionService.save: " + "exception encountered", e);
        }
    }

    public boolean isSiteEnabled() {
        return serverConfig.isSiteEnabled();
    }

    public boolean isReducedCrossTalkEnabled() {
        return serviceConfig.isReducedCrossTalkEnabled();
    }

    public boolean isLocalSite(SessionID id) {
        return serverConfig.isLocalSite(id);
    }

    public boolean isLocalSessionService(URL svcurl) {
        return serverConfig.isLocalSessionService(svcurl);
    }

    public long getReducedCrosstalkPurgeDelay() {
        return serviceConfig.getReducedCrosstalkPurgeDelay();
    }

    public boolean hasExceededMaxSessions() {
        return monitoringOperations.getActiveSessions() >= serviceConfig.getMaxSessions();
    }
    public static String getAMServerID() {
        String serverid;

        try {
            serverid = WebtopNaming.getAMServerID();
        } catch (Exception le) {
            serverid = null;
        }

        return serverid;
    }
}
