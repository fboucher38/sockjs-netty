/**
 * 
 */
package com.cgbystrom.sockjs;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import com.cgbystrom.sockjs.handlers.SessionHandler;

public final class ServiceBuilder {

    private final static ScheduledExecutorService DEFAULT_SCHEDULED_EXECUTOR = Executors.newSingleThreadScheduledExecutor();

    private String                                url;
    private SessionCallbackFactory                factory;
    private boolean                               isWebSocketEnabled = true;
    private int                                   maxResponseSize = 128 * 1024;
    private boolean                               jsessionidEnabled = false;
    private ScheduledExecutorService              scheduledExecutor = DEFAULT_SCHEDULED_EXECUTOR;
    private Integer                               timeoutDelay = 5000;
    private Integer                               hreatbeatDelay = 25000;

    public void setUrl(String url) {
        this.url = url;
    }

    public void setFactory(SessionCallbackFactory factory) {
        this.factory = factory;
    }

    public void setWebSocketEnabled(boolean isWebSocketEnabled) {
        this.isWebSocketEnabled = isWebSocketEnabled;
    }

    public void setMaxResponseSize(int maxResponseSize) {
        this.maxResponseSize = maxResponseSize;
    }

    public void setJsessionidEnabled(boolean jsessionidEnabled) {
        this.jsessionidEnabled = jsessionidEnabled;
    }

    public void setScheduledExecutor(ScheduledExecutorService scheduledExecutor) {
        this.scheduledExecutor = scheduledExecutor;
    }

    public void setTimeoutDelay(int timeoutDelay) {
        this.timeoutDelay = timeoutDelay;
    }

    public void setHreatbeatDelay(int hreatbeatDelay) {
        this.hreatbeatDelay = hreatbeatDelay;
    }

    public Service build() {
        if(url == null) {
            throw new NullPointerException("url");
        }
        if(factory == null) {
            throw new NullPointerException("factory");
        }
        if(scheduledExecutor == null) {
            throw new NullPointerException("scheduledExecutor");
        }

        return new ServiceImpl(url, factory, isWebSocketEnabled, maxResponseSize, jsessionidEnabled, scheduledExecutor, timeoutDelay, hreatbeatDelay);
    }

    private static class ServiceImpl implements Service {

        private final String                                url;
        private final ConcurrentMap<String, SessionHandler> sessions;
        private final SessionCallbackFactory                factory;
        private final boolean                               isWebSocketEnabled;
        private final int                                   maxResponseSize;
        private final boolean                               jsessionidEnabled;
        private final ScheduledExecutorService              scheduledExecutor;
        private final Integer                               timeoutDelay;
        private final Integer                               hreatbeatDelay;

        public ServiceImpl(String url, SessionCallbackFactory factory, boolean isWebSocketEnabled, int maxResponseSize,
                           boolean jsessionid, ScheduledExecutorService scheduledExecutor, Integer timeoutDelay, Integer hreatbeatDelay) {
            this.url = url;
            this.factory = factory;
            this.isWebSocketEnabled = isWebSocketEnabled;
            this.maxResponseSize = maxResponseSize;
            this.jsessionidEnabled = jsessionid;
            this.scheduledExecutor = scheduledExecutor;
            this.timeoutDelay = timeoutDelay;
            this.hreatbeatDelay = hreatbeatDelay;
            this.sessions = new ConcurrentHashMap<String, SessionHandler>();
        }

        @Override
        public String getUrl() {
            return url;
        }

        @Override
        public SessionCallbackFactory getFactory() {
            return factory;
        }

        @Override
        public boolean isWebSocketEnabled() {
            return isWebSocketEnabled;
        }

        @Override
        public int getMaxResponseSize() {
            return maxResponseSize;
        }

        @Override
        public boolean isJsessionid() {
            return jsessionidEnabled;
        }

        @Override
        public SessionHandler getOrCreateSession(String sessionId) throws Exception {
            SessionHandler session;

            session = sessions.get(sessionId);
            if (session == null) {
                SessionHandler newSession;

                newSession = newSession(sessionId);
                session = sessions.putIfAbsent(sessionId, newSession);
                if (session == null) {
                    session = newSession;
                }
            }

            return session;
        }

        @Override
        public SessionHandler getSession(String sessionId) throws Exception {
            SessionHandler session;

            session = sessions.get(sessionId);
            if (session == null) {
                throw new Exception(sessionId);
            }

            return session;
        }

        /**
         * @param aSessionId
         * @return the created session
         * @throws Exception
         */
        @Override
        public SessionHandler forceCreateSession(String sessionId) throws Exception {
            SessionHandler newSession;
            newSession = newSession(sessionId);

            SessionHandler oldSession;
            oldSession = sessions.replace(sessionId, newSession);

            if (oldSession != null) {
                throw new IllegalStateException("this is a bug");
            }

            return newSession;
        }

        /**
         * @param aSessionHandler
         * @return the removed session or null if not found
         */
        @Override
        public SessionHandler removeSession(SessionHandler aSessionHandler) {
            return sessions.remove(aSessionHandler);
        }

        /**
         * @param sessionId
         * @return
         * @throws Exception
         */
        private SessionHandler newSession(final String sessionId) throws Exception {
            Runnable disposer;
            disposer = new Runnable() {
                @Override
                public void run() {
                    sessions.remove(sessionId);
                }
            };
            return new SessionHandler(sessionId, factory.createSessionCallback(sessionId), scheduledExecutor,
                    timeoutDelay, hreatbeatDelay, disposer);
        }

        @Override
        public String toString() {
            return "Service [url=" + url + ", isWebSocketEnabled=" + isWebSocketEnabled + ", responseLimit="
                    + maxResponseSize + ", jsessionidEnabled=" + jsessionidEnabled + ", timeoutDelay=" + timeoutDelay
                    + ", hreatbeatDelay=" + hreatbeatDelay + "]";
        }

    }

}
