package com.cgbystrom.sockjs;

import java.net.SocketAddress;
import java.util.LinkedList;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.logging.InternalLogger;
import org.jboss.netty.logging.InternalLoggerFactory;

import com.cgbystrom.sockjs.frames.Frame;
import com.cgbystrom.sockjs.frames.Frame.CloseFrame;
import com.cgbystrom.sockjs.frames.Frame.MessageFrame;

/**
 * Responsible for handling SockJS sessions. It is a stateful channel handler
 * and tied to each session. Only session specific logic and is unaware of
 * underlying transport. This is by design and Netty enables a clean way to do
 * this through the pipeline and handlers.
 */
public final class SessionHandler extends SimpleChannelHandler implements Session {

    private static final InternalLogger LOGGER = InternalLoggerFactory.getInstance(SessionHandler.class);

    public enum State {
        CONNECTING, OPEN, CLOSING, CLOSED
    }

    private final String                    id;
    private final Service                   service;
    private final FrameQueue                frameQueue = new FrameQueue();
    private final SessionCallback           sessionCallback;
    private final ScheduledExecutorService  scheduledExecutor;
    private final Integer                   timeoutDelay;
    private final Integer                   hreatbeatDelay;

    private final AtomicReference<Channel>  channel;
    private final AtomicReference<State>    state;

    private CloseFrame                      closeFrame;
    private SocketAddress                   localAddress;
    private SocketAddress                   remoteAddress;
    private Future<?>                       timeoutFuture;
    private Future<?>                       heartbeatFuture;

    protected SessionHandler(Service service, String id, SessionCallback sessionCallback,
                             ScheduledExecutorService scheduledExecutor, Integer timeoutDelay,
                             Integer hreatbeatDelay) {

        if (LOGGER.isDebugEnabled())
            LOGGER.debug("Session " + id + " created");

        this.id = id;
        this.service = service;
        this.sessionCallback = sessionCallback;
        this.scheduledExecutor = scheduledExecutor;
        this.timeoutDelay = timeoutDelay;
        this.hreatbeatDelay = hreatbeatDelay;
        this.state = new AtomicReference<State>(State.CONNECTING);
        this.channel = new AtomicReference<Channel>(null);
    }

    @Override
    public void channelConnected(ChannelHandlerContext context, ChannelStateEvent event) throws Exception {
        Channel newChannel;
        newChannel = event.getChannel();

        if (LOGGER.isDebugEnabled())
            LOGGER.debug("Session " + id + " connected " + newChannel);

        if (state.get() == State.CLOSED) {
            throw new IllegalStateException("session already closed");
        }

        if (channel.get() != null) {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("Session " + id + " already have a channel connected.");

            newChannel.write(new Frame[] { Frame.closeFrame(2010, "Another connection still open") })
            .addListener(ChannelFutureListener.CLOSE);
            return;
        }

        tryCancelTimeout();

        if (state.compareAndSet(State.CLOSING, State.CLOSED)) {
            newChannel.write(new Frame[] { Frame.messageFrame(frameQueue.getAndClear()), closeFrame })
            .addListener(ChannelFutureListener.CLOSE);
            service.removeSession(SessionHandler.this);
            sessionCallback.onClose(SessionHandler.this);
            return;
        }

        if (channel.compareAndSet(null, newChannel)) {
            localAddress = newChannel.getLocalAddress();
            remoteAddress = newChannel.getRemoteAddress();
            scheduleHeartbeat();
        } else {
            new IllegalStateException("null channel expected");
        }

        if (state.compareAndSet(State.CONNECTING, State.OPEN)) {
            newChannel.write(new Frame[] { Frame.openFrame() });
            sessionCallback.onOpen(this);
        }

        if (newChannel != null && newChannel.isOpen()) {
            newChannel.write(new Frame[] { Frame.messageFrame(frameQueue.getAndClear()) });
        }
    }

    @Override
    public void channelClosed(ChannelHandlerContext context, ChannelStateEvent event) throws Exception {
        Channel closedChannel;
        closedChannel = event.getChannel();

        if (channel.compareAndSet(closedChannel, null)) {
            tryCancelHeartbeat();
            if (state.get() == State.CONNECTING || state.get() == State.OPEN) {
                scheduleTimeout();
            }
        }
    }

    @Override
    public void messageReceived(ChannelHandlerContext context, MessageEvent event) throws Exception {
        if (!(event.getMessage() instanceof MessageFrame)) {
            throw new IllegalArgumentException("Unexpected message type: " + event.getMessage().getClass());
        }

        if (state.get() == State.OPEN) {
            MessageFrame messageFrame;
            messageFrame = (MessageFrame) event.getMessage();
            for(String message : messageFrame.getMessages()) {
                if (LOGGER.isDebugEnabled())
                    LOGGER.debug("Session " + id + " received message: " + message);
                sessionCallback.onMessage(this, message);
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, ExceptionEvent event) throws Exception {
        if (LOGGER.isDebugEnabled())
            LOGGER.debug("Session " + id + " exceptionCaught", event.getCause());

        if (context.getChannel().isOpen()) {
            context.getChannel().close();
        }

        boolean wasNotOpened;
        wasNotOpened = state.compareAndSet(State.CLOSING, State.CLOSED) || state.compareAndSet(State.CONNECTING, State.CLOSED);

        boolean wasOpen;
        wasOpen = state.compareAndSet(State.OPEN, State.CLOSED);

        if (wasOpen || wasNotOpened) {
            tryCancelTimeout();
            service.removeSession(this);
            if(wasOpen) {
                sessionCallback.onClose(this);
            }
            sessionCallback.onError(this, event.getCause());
        }
    }

    @Override
    public void send(String message) {
        if (LOGGER.isDebugEnabled())
            LOGGER.debug("Session " + id + " sending message: " + message);

        frameQueue.put(message);

        Channel flushingChannel;
        flushingChannel = channel.get();
        if (flushingChannel != null && flushingChannel.isOpen()) {
            flushingChannel.write(frameQueue.getAndClear());
        }
    }

    @Override
    public void close() {
        close(1000, "Normal closure");
    }

    @Override
    public void close(int code, String message) {
        if (state.compareAndSet(State.CONNECTING, State.CLOSING) || state.compareAndSet(State.OPEN, State.CLOSING)) {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("Session " + id + " server initiated close, closing...");

            closeFrame = Frame.closeFrame(code, message);

            Channel closingChannel;
            closingChannel = channel.get();

            if (closingChannel != null && closingChannel.isOpen() && state.compareAndSet(State.CLOSING, State.CLOSED)) {

                closingChannel.write(new Frame[] { Frame.messageFrame(frameQueue.getAndClear()), closeFrame })
                .addListener(ChannelFutureListener.CLOSE);
                service.removeSession(this);

                try {
                    sessionCallback.onClose(this);
                } catch(Exception ex) {
                    sessionCallback.onError(this, ex);
                }
            } else {
                scheduleTimeout();
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

    private void scheduleHeartbeat() {
        if (heartbeatFuture != null) {
            throw new IllegalStateException("heartbeat is already scheduled");
        }

        final Channel currentChannel;
        currentChannel = channel.get();

        heartbeatFuture = scheduledExecutor.scheduleWithFixedDelay(new Runnable() {

            @Override
            public void run() {
                if (state.get() != State.OPEN) {
                    throw new IllegalStateException("session must be open");
                }
                if (channel.get() != currentChannel) {
                    throw new IllegalStateException("unexpected channel");
                }
                currentChannel.write(new Frame[] { Frame.heartbeatFrame() });
            }

        }, hreatbeatDelay, hreatbeatDelay, TimeUnit.MILLISECONDS);

    }

    private void tryCancelHeartbeat() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
    }

    private void scheduleTimeout() {
        if (timeoutFuture != null) {
            tryCancelTimeout();
        }

        timeoutFuture = scheduledExecutor.schedule(new Runnable() {

            @Override
            public void run() {
                if (state.compareAndSet(State.CONNECTING, State.CLOSED) || state.compareAndSet(State.OPEN, State.CLOSED) || state.compareAndSet(State.CLOSING, State.CLOSED)) {
                    service.removeSession(SessionHandler.this);
                    try {
                        sessionCallback.onClose(SessionHandler.this);
                    } catch (Exception exception) {
                        sessionCallback.onError(SessionHandler.this, exception);
                    }
                } else {
                    throw new IllegalStateException("session is already closed");
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

    public static class NotFoundException extends Exception {
        private static final long serialVersionUID = 1L;

        public NotFoundException(String baseUrl, String sessionId) {
            super("Session '" + sessionId + "' not found in sessionCallback '" + baseUrl + "'");
        }
    }

    private static class FrameQueue {

        private final LinkedList<String> queue = new LinkedList<String>();

        public synchronized void put(String frame) {
            queue.add(frame);
        }

        public synchronized String[] getAndClear() {
            String[] flushableMessages;
            flushableMessages = queue.toArray(new String[queue.size()]);
            if (flushableMessages.length > 0) {
                queue.clear();
            }
            return flushableMessages;
        }

    }

}
