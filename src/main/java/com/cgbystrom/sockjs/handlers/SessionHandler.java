package com.cgbystrom.sockjs.handlers;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.LinkedList;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
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
    private final LinkedList<String>       queue = new LinkedList<String>();
    private final AtomicReference<Channel> channel;
    private final AtomicReference<State>   state;

    private CloseFrame                     closeFrame;
    private SocketAddress                  localAddress;
    private SocketAddress                  remoteAddress;
    private Future<?>                      timeoutFuture;
    private Future<?>                      heartbeatFuture;

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
            flushAndClose(newChannel);
            return;
        }

        scheduleHeartbeat();

        if (state.compareAndSet(State.CONNECTING, State.OPEN)) {
            newChannel.write(Frame.openFrame()).await();
            sessionCallback.onOpen(this);
        }

        if(newChannel.isOpen()) {
            flush(newChannel);
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
        if (state.get() == State.OPEN) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Session " + id + " received message: " + event.getMessage());
            }

            sessionCallback.onMessage(this, (String) event.getMessage());

        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext aCtx, ExceptionEvent event) throws Exception {
        if(event.getCause() instanceof ClosedChannelException || event.getCause() instanceof IOException) {
            if(event.getChannel().isOpen()) {
                event.getFuture().addListener(ChannelFutureListener.CLOSE);
            }
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
        if (LOGGER.isDebugEnabled())
            LOGGER.debug("Session " + id + " sending message: " + message);

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

            Channel currentChannel;
            currentChannel = channel.get();

            closeFrame = Frame.closeFrame(status, reason);

            try {
                if(currentChannel != null && currentChannel.isOpen() && !flushAndClose(currentChannel))  {
                    scheduleTimeout();
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

    private boolean flush(Channel channel) throws InterruptedException {
        boolean done;

        synchronized (queue) {
            String[] flushableMessages;
            flushableMessages = queue.toArray(new String[queue.size()]);

            if(flushableMessages.length > 0) {
                ChannelFuture flushFuture;
                flushFuture = channel.write(Frame.messageFrame(flushableMessages));
                flushFuture.await();

                if(flushFuture.isSuccess()) {
                    if(channel.isOpen()) {
                        tryCancelHeartbeat();
                        scheduleHeartbeat();
                    }
                    done = true;
                    queue.clear();

                } else {
                    done = false;
                }

            } else {
                done = true;
            }
        }

        return done;
    }

    private boolean flushAndClose(Channel channel) throws InterruptedException {
        if(state.get() != State.CLOSING) {
            throw new IllegalStateException();
        }

        boolean closed = false;

        if(flush(channel) && channel.isOpen()) {
            ChannelFuture closeWriteFuture;
            closeWriteFuture = channel.write(closeFrame);
            closeWriteFuture.addListener(ChannelFutureListener.CLOSE);

            if(closeWriteFuture.await().isSuccess() && state.compareAndSet(State.CLOSING, State.CLOSED)) {
                sessionCallback.onClose(SessionHandler.this);
                closed = true;
            }
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
                synchronized (SessionHandler.this) {
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
            }

        }, timeoutDelay, TimeUnit.MILLISECONDS);
    }

    private void tryCancelTimeout() {
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
            timeoutFuture = null;
        }
    }

    private void scheduleHeartbeat() {
        if (heartbeatFuture != null) {
            throw new IllegalStateException("heartbeat is already scheduled");
        }

        heartbeatFuture = scheduledExecutor.scheduleWithFixedDelay(new Runnable() {

            @Override
            public void run() {
                Channel currentChannel;
                currentChannel = channel.get();

                if (currentChannel == null) {
                    throw new IllegalStateException("null channel");
                }

                currentChannel.write(Frame.heartbeatFrame());

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
