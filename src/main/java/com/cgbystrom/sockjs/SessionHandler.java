package com.cgbystrom.sockjs;

import java.net.SocketAddress;
import java.util.LinkedList;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.logging.InternalLogger;
import org.jboss.netty.logging.InternalLoggerFactory;
import org.jboss.netty.util.CharsetUtil;

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
    private final LinkedList<SockJsMessage> messageQueue = new LinkedList<SockJsMessage>();
    private final SessionCallback           sessionCallback;
    private final ScheduledExecutorService  scheduledExecutor;
    private final Integer                   timeoutDelay;
    private final Integer                   hreatbeatDelay;
    private final boolean                   heartbeatRequired;

    private Channel                         channel;
    private SocketAddress                   localAddress;
    private SocketAddress                   remoteAddress;
    private State                           state        = State.CONNECTING;
    private Frame.CloseFrame                closeReason;
    private Future<?>                       timeoutFuture;
    private Future<?>                       heartbeatFuture;

    protected SessionHandler(Service service, String id, SessionCallback sessionCallback,
            ScheduledExecutorService scheduledExecutor, Integer timeoutDelay, boolean heartbeatActivated,
            Integer hreatbeatDelay) {
        this.id = id;
        this.service = service;
        this.sessionCallback = sessionCallback;
        this.scheduledExecutor = scheduledExecutor;
        this.timeoutDelay = timeoutDelay;
        this.heartbeatRequired = heartbeatActivated;
        this.hreatbeatDelay = hreatbeatDelay;

        if (LOGGER.isDebugEnabled())
            LOGGER.debug("Session " + id + " created");
    }

    @Override
    public synchronized void channelConnected(ChannelHandlerContext context, ChannelStateEvent event) throws Exception {
        if (LOGGER.isDebugEnabled())
            LOGGER.debug("Session " + id + " connected " + event.getChannel());

        // FIXME: Check if session is locked (another handler already uses it),
        // all but WS can do this

        if (state == State.CLOSED) {
            throw new IllegalStateException("session already closed");
        }

        if (channel != null) {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("Session " + id + " already have a channel connected.");

            event.getChannel().write(Frame.closeFrame(2010, "Another connection still open"))
                    .addListener(ChannelFutureListener.CLOSE);
            return;
        }

        tryCancelTimeout();

        if (state == State.CLOSING) {
            doClose(event.getChannel());
            setState(State.CLOSED);
            service.removeSession(SessionHandler.this);
            sessionCallback.onClose(SessionHandler.this);
            return;
        }

        setChannel(event.getChannel());
        scheduleHeartbeat();

        if (state == State.CONNECTING) {
            channel.write(Frame.openFrame());
            setState(State.OPEN);
            // FIXME: Ability to reject a connection here by returning false in
            // callback to onOpen?
            sessionCallback.onOpen(this);
        }

        tryFlush();
    }

    @Override
    public synchronized void channelClosed(ChannelHandlerContext context, ChannelStateEvent event) throws Exception {
        if (channel != null && channel == event.getChannel()) {
            unsetChannel(event.getChannel());
            tryCancelHeartbeat();
            if (state == State.CONNECTING || state == State.OPEN) {
                scheduleTimeout();
            }
        }
    }

    @Override
    public synchronized void writeRequested(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        if (LOGGER.isDebugEnabled() && e.getMessage() instanceof Frame) {
            Frame f = (Frame) e.getMessage();
            String data = f.getData().toString(CharsetUtil.UTF_8);
            LOGGER.debug("Session " + id + " for channel " + e.getChannel() + " sending: " + data);
        }
        super.writeRequested(ctx, e);
    }

    @Override
    public synchronized void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        if (state == State.OPEN) {
            SockJsMessage msg = (SockJsMessage) e.getMessage();

            if (LOGGER.isDebugEnabled())
                LOGGER.debug("Session " + id + " received message: " + msg.getMessage());

            sessionCallback.onMessage(this, msg.getMessage());
        }
    }

    @Override
    public synchronized void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
        if (ctx.getChannel().isOpen()) {
            ctx.getChannel().close();
        }
        if (state != State.CLOSED) {
            tryCancelTimeout();
            setState(State.CLOSED);
            service.removeSession(this);
            sessionCallback.onClose(this);
            sessionCallback.onError(this, e.getCause());
        }
    }

    @Override
    public synchronized void send(String message) {
        SockJsMessage msg = new SockJsMessage(message);
        messageQueue.addLast(msg);
        tryFlush();
    }

    @Override
    public synchronized void close() {
        close(1000, "Normal closure");
    }

    @Override
    public synchronized void close(int code, String message) {
        if (state != State.CLOSED) {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("Session " + id + " server initiated close, closing...");

            closeReason = Frame.closeFrame(code, message);
            setState(State.CLOSING);

            if (channel != null && channel.isConnected()) {
                doClose(channel).addListener(new ChannelFutureListener() {

                    @Override
                    public void operationComplete(ChannelFuture aFuture) throws Exception {
                        if (aFuture.isSuccess()) {
                            setState(State.CLOSED);
                            service.removeSession(SessionHandler.this);
                            sessionCallback.onClose(SessionHandler.this);
                        } else {
                            scheduleTimeout();
                        }
                    }

                });
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

    private void tryFlush() {
        if (LOGGER.isDebugEnabled())
            LOGGER.debug("Session " + id + " flushing queue");

        if (channel != null && channel.isConnected()) {
            doFlush(channel);
        }
    }

    private ChannelFuture doClose(final Channel channel) {
        final ChannelFuture future;
        future = Channels.future(channel);

        doFlush(channel).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture aFuture) throws Exception {
                channel.write(closeReason).addListener(new ChannelFutureListener() {

                    @Override
                    public void operationComplete(ChannelFuture aFuture) throws Exception {
                        channel.close().addListener(new ChannelFutureListener() {

                            @Override
                            public void operationComplete(ChannelFuture aFuture) throws Exception {
                                if (aFuture.isSuccess()) {
                                    future.setSuccess();
                                } else {
                                    future.setFailure(aFuture.getCause());
                                }
                            }

                        });
                    }

                });
            }
        });

        return future;
    }

    private ChannelFuture doFlush(Channel channel) {
        ChannelFuture future;

        SockJsMessage[] flushableMessages;
        flushableMessages = messageQueue.toArray(new SockJsMessage[messageQueue.size()]);

        if (flushableMessages.length > 0) {
            messageQueue.clear();
            tryCancelHeartbeat();
            scheduleHeartbeat();
            future = channel.write(Frame.messageFrame(flushableMessages));

        } else {
            future = Channels.succeededFuture(channel);
        }

        return future;
    }

    private void setState(State state) {
        if (LOGGER.isDebugEnabled())
            LOGGER.debug("Session " + id + " state changed to " + state);

        this.state = state;
    }

    private void setChannel(Channel channel) {
        if (LOGGER.isDebugEnabled())
            LOGGER.debug("Session " + id + " channel added");

        this.channel = channel;
        this.localAddress = channel.getLocalAddress();
        this.remoteAddress = channel.getRemoteAddress();
    }

    private void unsetChannel(Channel channel) {
        if (this.channel != channel && this.channel != null) {
            return;
        }

        if (LOGGER.isDebugEnabled())
            LOGGER.debug("Session " + id + " channel removed " + channel);

        this.channel = null;
    }

    private void scheduleHeartbeat() {
        if (heartbeatFuture != null) {
            throw new IllegalStateException("heartbeat is already scheduled");
        }

        heartbeatFuture = scheduledExecutor.scheduleWithFixedDelay(new Runnable() {

            @Override
            public void run() {
                synchronized (SessionHandler.this) {
                    if (state != State.OPEN) {
                        throw new IllegalStateException("session must be open");
                    }
                    if (channel == null || !channel.isConnected()) {
                        throw new IllegalStateException("channel must be initialized and connected");
                    }

                    channel.write(Frame.heartbeatFrame());
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

    private void scheduleTimeout() {
        if (!heartbeatRequired) {
            return;
        }

        if (timeoutFuture != null) {
            throw new IllegalStateException("timeout is already scheduled");
        }

        timeoutFuture = scheduledExecutor.schedule(new Runnable() {

            @Override
            public void run() {
                synchronized (SessionHandler.this) {
                    if (state == State.CLOSED) {
                        throw new IllegalStateException("session is already closed");
                    }
                    setState(State.CLOSED);
                    service.removeSession(SessionHandler.this);
                    try {
                        sessionCallback.onClose(SessionHandler.this);
                    } catch (Exception exception) {
                        sessionCallback.onError(SessionHandler.this, exception);
                    }
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
        public NotFoundException(String baseUrl, String sessionId) {
            super("Session '" + sessionId + "' not found in sessionCallback '" + baseUrl + "'");
        }
    }

    public static class LockException extends Exception {
        public LockException(Channel channel) {
            super("Session is locked by channel " + channel
                + ". Please disconnect other channel first before trying to register it with a session.");
        }
    }

}
