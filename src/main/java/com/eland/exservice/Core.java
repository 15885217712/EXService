/*
 * Copyright © 2017 Yan Zhenjie.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.eland.exservice;

import com.eland.exservice.exception.resolver.ExceptionResolver;
import com.eland.exservice.filter.Filter;
import com.eland.exservice.interceptor.Interceptor;
import com.eland.exservice.ssl.SSLSocketInitializer;
import com.eland.exservice.util.Executors;
import com.eland.exservice.website.WebSite;

import org.apache.httpcore.ExceptionLogger;
import org.apache.httpcore.config.ConnectionConfig;
import org.apache.httpcore.config.SocketConfig;
import org.apache.httpcore.impl.bootstrap.HttpServer;
import org.apache.httpcore.impl.bootstrap.ServerBootstrap;

import java.net.InetAddress;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;

/**
 * Created by elandTong on 2018/8/22.
 */
final class Core implements Service {

    static Builder newBuilder() {
        return new Builder();
    }

    private final InetAddress mInetAddress;
    private final int mPort;
    private final int mTimeout;
    private final SSLContext mSSLContext;
    private final SSLSocketInitializer mSSLSocketInitializer;
    private final Interceptor mInterceptor;
    private final WebSite mWebSite;
    private final Map<String, RequestHandler> mRequestHandlerMap;
    private final Filter mFilter;
    private final ExceptionResolver mExceptionResolver;
    private final ServiceListener mListener;

    private HttpServer mHttpServer;
    private boolean isRunning;

    private Core(Builder builder) {
        this.mInetAddress = builder.mInetAddress;
        this.mPort = builder.mPort;
        this.mTimeout = builder.mTimeout;
        this.mSSLContext = builder.mSSLContext;
        this.mSSLSocketInitializer = builder.mSSLSocketInitializer;
        this.mInterceptor = builder.mInterceptor;
        this.mWebSite = builder.mWebSite;
        this.mRequestHandlerMap = builder.mRequestHandlerMap;
        this.mFilter = builder.mFilter;
        this.mExceptionResolver = builder.mExceptionResolver;
        this.mListener = builder.mListener;
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }

    @Override
    public void startup() {
        if (isRunning) return;

        Executors.getInstance().submit(new Runnable() {
            @Override
            public void run() {
                DispatchRequestHandler handler = new DispatchRequestHandler();
                handler.setInterceptor(mInterceptor);
                handler.setWebSite(mWebSite);
                if (mRequestHandlerMap != null && mRequestHandlerMap.size() > 0) {
                    for (Map.Entry<String, RequestHandler> handlerEntry : mRequestHandlerMap.entrySet()) {
                        String path = handlerEntry.getKey();
                        RequestHandler requestHandler = handlerEntry.getValue();
                        handler.registerRequestHandler(path, requestHandler);
                    }
                }
                handler.setFilter(mFilter);
                handler.setExceptionResolver(mExceptionResolver);

                mHttpServer = ServerBootstrap.bootstrap()
                        .setSocketConfig(
                                SocketConfig.custom()
                                        .setSoKeepAlive(true)
                                        .setSoReuseAddress(true)
                                        .setSoTimeout(mTimeout)
                                        .setTcpNoDelay(false)
                                        .build()
                        )
                        .setConnectionConfig(
                                ConnectionConfig.custom()
                                        .setBufferSize(4 * 1024)
                                        .setCharset(Charset.defaultCharset())
                                        .build()
                        )
                        .setLocalAddress(mInetAddress)
                        .setListenerPort(mPort)
                        .setSslContext(mSSLContext)
                        .setSslSetupHandler(new SSLSocketInitializer.SSLSocketInitializerWrapper(mSSLSocketInitializer))
                        .setServerInfo("ExService")
                        .registerHandler("*", handler)
                        .setExceptionLogger(ExceptionLogger.STD_ERR)
                        .create();
                try {
                    isRunning = true;
                    mHttpServer.start();

                    Executors.getInstance().post(new Runnable() {
                        @Override
                        public void run() {
                            if (mListener != null)
                                mListener.onStarted();
                        }
                    });
                    Runtime.getRuntime().addShutdownHook(new Thread() {
                        @Override
                        public void run() {
                            mHttpServer.shutdown(3, TimeUnit.SECONDS);
                        }
                    });
                } catch (final Exception e) {
                    Executors.getInstance().post(new Runnable() {
                        @Override
                        public void run() {
                            if (mListener != null)
                                mListener.onError(e);
                        }
                    });
                }
            }
        });
    }

    /**
     * The current server InetAddress.
     */
    @Override
    public InetAddress getInetAddress() {
        if (isRunning)
            return mHttpServer.getInetAddress();
        return null;
    }

    /**
     * Stop core server.
     */
    @Override
    public void shutdown() {
        if (!isRunning) return;

        Executors.getInstance().execute(new Runnable() {
            @Override
            public void run() {
                if (mHttpServer != null)
                    mHttpServer.shutdown(3, TimeUnit.SECONDS);

                Executors.getInstance().post(new Runnable() {
                    @Override
                    public void run() {
                        isRunning = false;
                        if (mListener != null)
                            mListener.onStopped();
                    }
                });
            }
        });
    }

    private static final class Builder implements Service.Builder {

        private InetAddress mInetAddress;
        private int mPort;
        private int mTimeout;
        private SSLContext mSSLContext;
        private SSLSocketInitializer mSSLSocketInitializer;
        private Interceptor mInterceptor;
        private WebSite mWebSite;
        private Map<String, RequestHandler> mRequestHandlerMap;
        private Filter mFilter;
        private ExceptionResolver mExceptionResolver;
        private ServiceListener mListener;

        private Builder() {
            this.mRequestHandlerMap = new LinkedHashMap<>();
        }

        @Override
        public Service.Builder inetAddress(InetAddress inetAddress) {
            this.mInetAddress = inetAddress;
            return this;
        }

        @Override
        public Service.Builder port(int port) {
            this.mPort = port;
            return this;
        }

        @Override
        public Service.Builder timeout(int timeout, TimeUnit timeUnit) {
            long timeoutMs = timeUnit.toMillis(timeout);
            this.mTimeout = (int) Math.min(timeoutMs, Integer.MAX_VALUE);
            return this;
        }

        @Override
        public Service.Builder sslContext(SSLContext sslContext) {
            this.mSSLContext = sslContext;
            return this;
        }

        @Override
        public Service.Builder sslSocketInitializer(SSLSocketInitializer initializer) {
            this.mSSLSocketInitializer = initializer;
            return this;
        }

        @Override
        public Service.Builder interceptor(Interceptor interceptor) {
            this.mInterceptor = interceptor;
            return this;
        }

        @Override
        public Service.Builder exceptionResolver(ExceptionResolver resolver) {
            this.mExceptionResolver = resolver;
            return this;
        }

        @Override
        public Service.Builder registerHandler(String path, RequestHandler handler) {
            this.mRequestHandlerMap.put(path, handler);
            return this;
        }

        @Override
        public Service.Builder filter(Filter filter) {
            this.mFilter = filter;
            return this;
        }

        @Override
        public Service.Builder website(WebSite webSite) {
            this.mWebSite = webSite;
            return this;
        }

        @Override
        public Service.Builder listener(ServiceListener listener) {
            this.mListener = listener;
            return this;
        }

        @Override
        public Service build() {
            return new Core(this);
        }
    }
}
