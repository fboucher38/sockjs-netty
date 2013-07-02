package com.cgbystrom.sockjs;

import static org.jboss.netty.channel.Channels.pipeline;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.codec.http.HttpChunkAggregator;
import org.jboss.netty.handler.codec.http.HttpRequestDecoder;
import org.jboss.netty.handler.codec.http.HttpResponseEncoder;
import org.jboss.netty.handler.timeout.IdleStateHandler;
import org.jboss.netty.logging.InternalLoggerFactory;
import org.jboss.netty.logging.Slf4JLoggerFactory;
import org.jboss.netty.util.HashedWheelTimer;
import org.jboss.netty.util.Timer;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;

import com.cgbystrom.sockjs.handlers.PreflightHandler;
import com.cgbystrom.sockjs.handlers.ServiceRouterHandler;

public class TestServer {
    public static void main(String[] args) {
        Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        LoggerContext loggerContext = rootLogger.getLoggerContext();
        loggerContext.reset();
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(loggerContext);
        encoder.setPattern("%-5level %-20class{0}: %message%n");
        encoder.start();

        ConsoleAppender<ILoggingEvent> appender = new ConsoleAppender<ILoggingEvent>();
        appender.setContext(loggerContext);
        appender.setEncoder(encoder);
        appender.start();

        rootLogger.addAppender(appender);
        InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory());

        ServerBootstrap bootstrap = new ServerBootstrap(
                new NioServerSocketChannelFactory(
                        Executors.newCachedThreadPool(),
                        Executors.newCachedThreadPool()));

        ServiceBuilder echoServiceBuilder;
        echoServiceBuilder = new ServiceBuilder();
        echoServiceBuilder.setUrl("/echo");
        echoServiceBuilder.setFactory(new SessionCallbackFactory() {
            @Override
            public SessionCallback createSessionCallback(String aId) {
                return new EchoSession();
            }
        });

        ServiceBuilder disabledWebsocketEchoServiceBuilder;
        disabledWebsocketEchoServiceBuilder = new ServiceBuilder();
        disabledWebsocketEchoServiceBuilder.setUrl("/disabled_websocket_echo");
        disabledWebsocketEchoServiceBuilder.setWebSocketEnabled(false);
        disabledWebsocketEchoServiceBuilder.setResponseSizeLimit(128 * 1024);
        disabledWebsocketEchoServiceBuilder.setFactory(new SessionCallbackFactory() {
            @Override
            public SessionCallback createSessionCallback(String aId) {
                return new EchoSession();
            }
        });

        ServiceBuilder cookieNeededEchoServiceBuilder;
        cookieNeededEchoServiceBuilder = new ServiceBuilder();
        cookieNeededEchoServiceBuilder.setUrl("/cookie_needed_echo");
        cookieNeededEchoServiceBuilder.setJsessionidEnabled(true);
        cookieNeededEchoServiceBuilder.setResponseSizeLimit(4096);
        cookieNeededEchoServiceBuilder.setFactory(new SessionCallbackFactory() {
            @Override
            public SessionCallback createSessionCallback(String aId) {
                return new EchoSession();
            }
        });

        ServiceBuilder closeServiceBuilder;
        closeServiceBuilder = new ServiceBuilder();
        closeServiceBuilder.setUrl("/cookie_needed_echo");
        closeServiceBuilder.setResponseSizeLimit(128 * 1024);
        closeServiceBuilder.setFactory(new SessionCallbackFactory() {
            @Override
            public SessionCallback createSessionCallback(String aId) {
                return new CloseSession();
            }
        });

        ServiceBuilder amplifyServiceBuilder;
        amplifyServiceBuilder = new ServiceBuilder();
        amplifyServiceBuilder.setUrl("/amplify");
        amplifyServiceBuilder.setResponseSizeLimit(128 * 1024);
        amplifyServiceBuilder.setFactory(new SessionCallbackFactory() {
            @Override
            public SessionCallback createSessionCallback(String aId) {
                return new AmplifySession();
            }
        });

        ServiceBuilder broadcastServiceBuilder;
        broadcastServiceBuilder = new ServiceBuilder();
        broadcastServiceBuilder.setUrl("/broadcast");
        broadcastServiceBuilder.setResponseSizeLimit(128 * 1024);
        broadcastServiceBuilder.setFactory(new SessionCallbackFactory() {
            @Override
            public SessionCallback createSessionCallback(String aId) {
                return new BroadcastSession();
            }
        });

        List<Service> services;
        services = Arrays.asList(echoServiceBuilder.build(), disabledWebsocketEchoServiceBuilder.build(),
                cookieNeededEchoServiceBuilder.build(), closeServiceBuilder.build(), amplifyServiceBuilder.build(), broadcastServiceBuilder.build());

        final ServiceRouterHandler router;
        router = new ServiceRouterHandler(services);

        final Timer timer;
        timer = new HashedWheelTimer();

        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            @Override
            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = pipeline();

                pipeline.addLast("HttpRequestDecoder", new HttpRequestDecoder());
                pipeline.addLast("HttpChunkAggregator", new HttpChunkAggregator(65536));
                pipeline.addLast("HttpResponseEncoder", new HttpResponseEncoder());
                pipeline.addLast("ReadIdleStateHandler", new IdleStateHandler(timer, 0, 0, 5));
                pipeline.addLast("SockJsPreflight", new PreflightHandler());
                pipeline.addLast("SockJsRouter", router);

                return pipeline;
            }
        });

        bootstrap.bind(new InetSocketAddress(8090));
        System.out.println("Server running..");
    }
}
