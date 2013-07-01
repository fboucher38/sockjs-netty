package com.cgbystrom.sockjs.handlers;

import java.net.SocketAddress;
import java.util.LinkedList;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.netty.logging.InternalLogger;
import org.jboss.netty.logging.InternalLoggerFactory;

import com.cgbystrom.sockjs.Session;
import com.cgbystrom.sockjs.SessionCallback;

/**
 * Responsible for handling SockJS sessions. It is a stateful receiver handler
 * and tied to each session. Only session specific logic and is unaware of
 * underlying transport. This is by design and Netty enables a clean way to do
 * this through the pipeline and handlers.
 */
public final class SimpleSessionHandler implements SessionHandler, Session {

    private static final InternalLogger LOGGER = InternalLoggerFactory.getInstance(SimpleSessionHandler.class);

    public enum State {
        CONNECTING, OPEN, CLOSING, CLOSED
    }

    private final String                    id;
    private final Runnable                  disposer;
    private final SessionCallback           sessionCallback;
    private final ScheduledExecutorService  scheduledExecutor;
    private final Integer                   timeoutDelay;
    private final Integer                   hreatbeatDelay;

    private final LinkedList<String>        queue = new LinkedList<String>();
    private final AtomicReference<Receiver> receiver;
    private final AtomicReference<State>    state;

    private Integer                         closeStatus;
    private String                          closeReason;
    private SocketAddress                   localAddress;
    private SocketAddress                   remoteAddress;
    private Future<?>                       timeoutFuture;
    private Future<?>                       heartbeatFuture;

    public SimpleSessionHandler(String id, SessionCallback sessionCallback,
                                ScheduledExecutorService scheduledExecutor, Integer timeoutDelay,
                                Integer hreatbeatDelay, Runnable disposer) {

        if (LOGGER.isDebugEnabled())
            LOGGER.debug("Session " + id + " created");

        this.id = id;
        this.sessionCallback = sessionCallback;
        this.scheduledExecutor = scheduledExecutor;
        this.timeoutDelay = timeoutDelay;
        this.hreatbeatDelay = hreatbeatDelay;
        this.disposer = disposer;
        this.state = new AtomicReference<State>(State.CONNECTING);
        this.receiver = new AtomicReference<Receiver>(null);
    }

    @Override
    public void registerReceiver(Receiver newReceiver) {
        if(newReceiver == null) {
            throw new NullPointerException("newReceiver");
        }
        if (state.get() == State.CLOSED) {
            throw new IllegalStateException("Session " + id + " already closed");
        }

        if (!receiver.compareAndSet(null, newReceiver)) {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("Session " + id + " already have a connected receiver.");

            newReceiver.doClose(2010, "Another connection still open");
            return;
        }

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Session " + id + " connected to " + newReceiver);
        }

        localAddress = newReceiver.getLocalAddress();
        remoteAddress = newReceiver.getRemoteAddress();

        tryCancelTimeout();

        if (state.get() == State.CLOSING) {
            if(tryFlush(newReceiver) && !newReceiver.isClosed()) {
                tryClose(newReceiver);
            }
            return;
        }

        scheduleHeartbeat(newReceiver);

        if (state.compareAndSet(State.CONNECTING, State.OPEN)) {
            newReceiver.doOpen();
            sessionCallback.onOpen(this);
        }

        tryFlush(newReceiver);
    }

    @Override
    public void unregisterReceiver(Receiver removedReceiver) {
        if(removedReceiver == null) {
            throw new NullPointerException("removedReceiver");
        }

        if(receiver.compareAndSet(removedReceiver, null)) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Session " + id + " disconnected from " + removedReceiver);
            }

            tryCancelHeartbeat();

            if (state.get() == State.OPEN) {
                scheduleTimeout();
            }

        } else {
            throw new IllegalStateException("unknown receiver");
        }
    }

    @Override
    public void messageReceived(String message) {
        if (state.get() != State.OPEN) {
            throw new IllegalStateException("not opened");
        }

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Session " + id + " received message: " + message);
        }

        sessionCallback.onMessage(this, message);
    }

    @Override
    public void exceptionCaught(Throwable exception) {
        if (LOGGER.isDebugEnabled())
            LOGGER.debug("Session " + id + " exceptionCaught:", exception);

        boolean closed = state.compareAndSet(State.OPEN, State.CLOSED) || state.compareAndSet(State.CLOSING, State.CLOSED);
        boolean terminated = closed || state.compareAndSet(State.CONNECTING, State.CLOSED);

        if (terminated) {
            disposer.run();
            tryCancelTimeout();
            sessionCallback.onError(this, exception);
        }

        if(closed) {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("Session " + id + " closed.");

            sessionCallback.onClose(this);
        }
    }

    @Override
    public void send(String message) {
        if (state.get() != State.OPEN) {
            throw new IllegalStateException("not opened");
        }

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Session " + id + " sending message: " + message);
        }

        synchronized (queue) {
            queue.add(message);
        }

        Receiver currentChannel;
        currentChannel = receiver.get();

        if(currentChannel != null) {
            tryFlush(currentChannel);
        }
    }

    @Override
    public void close() {
        close(1000, "Normal closure");
    }

    @Override
    public void close(int status, String reason) {
        if (state.compareAndSet(State.OPEN, State.CLOSING)) {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("Session " + id + " server initiated close, closing...");

            closeStatus = status;
            closeReason = reason;

            Receiver currentReceiver;
            currentReceiver = receiver.get();

            if(currentReceiver != null)  {
                if(tryFlush(currentReceiver) && !currentReceiver.isClosed()) {
                    tryClose(currentReceiver);
                }
            }
        }
    }

    @Override
    public SocketAddress getLocalAddress() {
        return localAddress;
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    private boolean tryFlush(Receiver receiver) {
        boolean flushed;

        synchronized (queue) {
            String[] flushableMessages;
            flushableMessages = queue.toArray(new String[queue.size()]);

            if(flushableMessages.length > 0) {
                tryCancelHeartbeat();

                flushed = receiver.doWrite(flushableMessages);
                if(flushed) {
                    queue.clear();
                }
                if(flushed && !receiver.isClosed()) {
                    scheduleHeartbeat(receiver);
                }

            } else {
                flushed = true;
            }
        }

        return flushed;
    }

    private boolean tryClose(Receiver receiver) {
        if(state.get() != State.CLOSING) {
            throw new IllegalStateException("not closing");
        }

        boolean closed;

        if(receiver.doClose(closeStatus, closeReason) && state.compareAndSet(State.CLOSING, State.CLOSED)) {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("Session " + id + " closed.");

            disposer.run();
            sessionCallback.onClose(this);
            closed = true;
        } else {
            closed = false;
        }

        return closed;
    }

    private void scheduleTimeout() {
        if (timeoutFuture != null) {
            tryCancelTimeout();
        }

        timeoutFuture = scheduledExecutor.schedule(new Runnable() {

            @Override
            public void run() {
                if (state.compareAndSet(State.OPEN, State.CLOSED) || state.compareAndSet(State.CLOSING, State.CLOSED)) {
                    if (LOGGER.isDebugEnabled())
                        LOGGER.debug("Session " + id + " timed-out: closed.");

                    disposer.run();
                    sessionCallback.onClose(SimpleSessionHandler.this);
                } else {
                    throw new IllegalStateException("already closed");
                }
            }

        }, timeoutDelay, TimeUnit.MILLISECONDS);
    }

    private void tryCancelTimeout() {
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
            timeoutFuture = null;
        }
    }

    private void scheduleHeartbeat(final Receiver channel) {
        if (heartbeatFuture != null) {
            throw new IllegalStateException("heartbeat is already scheduled");
        }

        heartbeatFuture = scheduledExecutor.scheduleWithFixedDelay(new Runnable() {

            @Override
            public void run() {
                if (!channel.doHeartbeat()) {
                    throw new IllegalStateException("cannot write heartbeat");
                }
            }

        }, hreatbeatDelay, hreatbeatDelay, TimeUnit.MILLISECONDS);
    }

    private void tryCancelHeartbeat() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
    }

}
