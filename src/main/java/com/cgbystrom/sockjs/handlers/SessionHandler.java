package com.cgbystrom.sockjs.handlers;

import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.LinkedList;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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

import com.cgbystrom.sockjs.Session;
import com.cgbystrom.sockjs.SessionCallback;
import com.cgbystrom.sockjs.frames.Frame;
import com.cgbystrom.sockjs.frames.Frame.CloseFrame;

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

    private final String                   id;
    private final Runnable                 disposer;
    private final SessionCallback          sessionCallback;
    private final ScheduledExecutorService scheduledExecutor;
    private final Integer                  timeoutDelay;
    private final Integer                  hreatbeatDelay;

    private final AtomicBoolean            flushingQueue = new AtomicBoolean(false);
    private final LinkedList<String>       queue = new LinkedList<String>();
    private final AtomicReference<Channel> channel;
    private final AtomicReference<State>   state;

    private CloseFrame                     closeFrame;
    private SocketAddress                  localAddress;
    private SocketAddress                  remoteAddress;
    private Future<?>                      timeoutFuture;
    private Future<?>                      heartbeatFuture;

    private final ChannelFutureListener flushListener = new ChannelFutureListener() {
        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            Channel currentChannel;
            currentChannel = future.getChannel();

            if(future.isSuccess() && currentChannel.isOpen()) {
                flush(currentChannel);
            }
        }
    };

    private final ChannelFutureListener closeListener = new ChannelFutureListener() {
        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            Channel currentChannel;
            currentChannel = future.getChannel();

            if(future.isSuccess() && currentChannel.isOpen()) {
                close(currentChannel);
            }
        }
    };

    private final ChannelFutureListener flushAndCloseListener = new ChannelFutureListener() {
        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            Channel currentChannel;
            currentChannel = future.getChannel();

            if(future.isSuccess() && currentChannel.isOpen()) {
                flush(currentChannel).addListener(closeListener);
            }
        }
    };

    public SessionHandler(String id, SessionCallback sessionCallback,
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
        this.channel = new AtomicReference<Channel>(null);
    }

    @Override
    public void channelOpen(ChannelHandlerContext context, ChannelStateEvent event) throws Exception {
        Channel newChannel;
        newChannel = context.getChannel();

        if (LOGGER.isDebugEnabled())
            LOGGER.debug("Session " + id + " connected " + newChannel);

        if (state.get() == State.CLOSED) {
            throw new IllegalStateException("session already closed");
        }

        if (!channel.compareAndSet(null, newChannel)) {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("Session " + id + " already have a channel connected.");

            newChannel.write(Frame.closeFrame(2010, "Another connection still open")).addListener(ChannelFutureListener.CLOSE);
            return;
        }

        localAddress = newChannel.getLocalAddress();
        remoteAddress = newChannel.getRemoteAddress();

        tryCancelTimeout();

        if (state.get() == State.CLOSING) {
            event.getFuture().addListener(flushAndCloseListener);
            return;
        }

        scheduleHeartbeat(newChannel);

        if (state.compareAndSet(State.CONNECTING, State.OPEN)) {
            newChannel.write(Frame.openFrame()).addListener(flushListener);
            sessionCallback.onOpen(this);

        } else {
            event.getFuture().addListener(flushListener);

        }
    }

    @Override
    public void channelClosed(ChannelHandlerContext context, ChannelStateEvent event) throws Exception {
        if(channel.compareAndSet(context.getChannel(), null)) {
            tryCancelHeartbeat();

            if (state.get() == State.OPEN) {
                scheduleTimeout();
            }

        } else {
            throw new IllegalStateException("unknown channel");
        }
    }

    @Override
    public void messageReceived(ChannelHandlerContext context, MessageEvent event) throws Exception {
        if (state.get() != State.OPEN) {
            throw new IllegalStateException("not opened");
        }

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Session " + id + " received message: " + event.getMessage());
        }

        sessionCallback.onMessage(this, (String) event.getMessage());

    }

    @Override
    public void exceptionCaught(ChannelHandlerContext aCtx, ExceptionEvent event) throws Exception {
        if(event.getCause() instanceof ClosedChannelException) { //  || event.getCause() instanceof IOException
            /*if(event.getChannel().isOpen()) {
                event.getFuture().addListener(ChannelFutureListener.CLOSE);
            }*/
            return;
        }

        if (LOGGER.isDebugEnabled())
            LOGGER.debug("Session " + id + " exceptionCaught", event.getCause());

        boolean closed = state.compareAndSet(State.OPEN, State.CLOSED) || state.compareAndSet(State.CLOSING, State.CLOSED);
        boolean terminated = closed || state.compareAndSet(State.CONNECTING, State.CLOSED);

        if (terminated) {
            tryCancelTimeout();
            sessionCallback.onError(this, event.getCause());
        }

        if(closed) {
            disposer.run();
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

        Channel currentChannel;
        currentChannel = channel.get();

        if(currentChannel != null && currentChannel.isOpen()) {
            try {
                flush(currentChannel);

            } catch (InterruptedException e) {
                throw new RuntimeException("cannot flush and close", e);

            }
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

            final Channel currentChannel;
            currentChannel = channel.get();

            closeFrame = Frame.closeFrame(status, reason);

            try {
                if(currentChannel != null && currentChannel.isOpen())  {
                    ChannelFuture flushFuture;
                    flushFuture = flush(currentChannel);
                    flushFuture.addListener(closeListener);
                    flushFuture.addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture future) throws Exception {
                            if(future.isSuccess()) {
                                scheduleTimeout();
                            }
                        }
                    });
                }

            } catch (InterruptedException e) {
                throw new RuntimeException("cannot flush and close", e);

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

    private ChannelFuture flush(Channel channel) throws InterruptedException {
        if(!channel.isOpen()) {
            throw new IllegalStateException("not opened");
        }

        ChannelFuture flushFuture;

        if(flushingQueue.compareAndSet(false, true)) {
            final String[] flushableMessages;

            synchronized (queue) {
                flushableMessages = queue.toArray(new String[queue.size()]);
            }

            if(flushableMessages.length > 0) {
                flushFuture = channel.write(Frame.messageFrame(flushableMessages));
                flushFuture.addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        if(future.isSuccess()) {
                            Channel currentChannel;
                            currentChannel = future.getChannel();

                            synchronized (queue) {
                                for(String message : flushableMessages) {
                                    if(!queue.removeFirst().equals(message)) {
                                        throw new IllegalStateException("unexpected message: this is a bug");
                                    }
                                }
                            }

                            if(currentChannel.isOpen()) {
                                tryCancelHeartbeat();
                                scheduleHeartbeat(currentChannel);
                            }
                        }

                        flushingQueue.compareAndSet(true, false);

                        Channel currentChannel;
                        currentChannel = SessionHandler.this.channel.get();
                        if(currentChannel != null && currentChannel.isOpen())  {
                            flush(currentChannel);
                        }
                    }
                });

            } else {
                flushingQueue.compareAndSet(true, false);
                flushFuture = Channels.succeededFuture(channel);

            }

        } else {
            flushFuture = Channels.succeededFuture(channel);
        }

        return flushFuture;
    }

    private ChannelFuture close(Channel channel) throws InterruptedException {
        if(!channel.isOpen()) {
            throw new IllegalStateException("not opened");
        }
        if(state.get() != State.CLOSING) {
            throw new IllegalStateException("not closing");
        }

        ChannelFuture closeWriteFuture;

        closeWriteFuture = channel.write(closeFrame);
        closeWriteFuture.addListener(ChannelFutureListener.CLOSE);
        closeWriteFuture.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if(future.isSuccess() && state.compareAndSet(State.CLOSING, State.CLOSED)) {
                    sessionCallback.onClose(SessionHandler.this);
                }
            }
        });

        return closeWriteFuture;
    }

    private void scheduleTimeout() {
        if (timeoutFuture != null) {
            tryCancelTimeout();
        }

        timeoutFuture = scheduledExecutor.schedule(new Runnable() {

            @Override
            public void run() {
                if (state.compareAndSet(State.OPEN, State.CLOSED) || state.compareAndSet(State.CLOSING, State.CLOSED)) {
                    disposer.run();
                    try {
                        sessionCallback.onClose(SessionHandler.this);
                    } catch (Exception exception) {
                        sessionCallback.onError(SessionHandler.this, exception);
                    }
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

    private void scheduleHeartbeat(final Channel channel) {
        if (heartbeatFuture != null) {
            throw new IllegalStateException("heartbeat is already scheduled");
        }

        heartbeatFuture = scheduledExecutor.scheduleWithFixedDelay(new Runnable() {

            @Override
            public void run() {
                if (!channel.isOpen()) {
                    throw new IllegalStateException("channel not opened");
                }

                channel.write(Frame.heartbeatFrame());

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
