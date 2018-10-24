/**
 * Copyright (c) 2012-2013 Reficio (TM) - Reestablish your software!. All Rights Reserved.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.reficio.ws.server.core;

import org.apache.log4j.Logger;
import org.mortbay.jetty.AbstractConnector;
import org.mortbay.jetty.Handler;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.nio.SelectChannelConnector;
import org.mortbay.jetty.security.SslSelectChannelConnector;
import org.mortbay.jetty.servlet.Context;
import org.reficio.ws.annotation.ThreadSafe;
import org.reficio.ws.server.ServiceRegistrationException;
import org.reficio.ws.server.SoapServerException;
import org.reficio.ws.server.endpoint.GenericContextDomEndpoint;
import org.reficio.ws.server.responder.RequestResponder;
import org.springframework.beans.factory.config.PropertyPlaceholderConfigurer;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.GenericWebApplicationContext;

import javax.servlet.ServletContext;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * SOAP server enables the user to handle a SOAP communication on a purely XML level.
 * It supports plain HTTP as well as HTTPS communication.
 * When it comes to SOAP it supports SOAP 1.1 and 1.2
 * This class may throw an unchecked @see org.reficio.ws.server.SoapServerException
 *
 * @author Tom Bujok
 * @since 1.0.0
 */
@ThreadSafe
public final class SoapServer {

    private final static Logger log = Logger.getLogger(SoapServer.class);

    private Integer httpPort = SoapServerConstants.HTTP_PORT;
    private Integer httpsPort = SoapServerConstants.HTTPS_PORT;
    private boolean reuseAddress = SoapServerConstants.REUSE_ADDRESS;
    private Integer connectionMaxIdleTimeInSeconds = SoapServerConstants.CONNECTION_MAX_IDLE_TIME_IN_SECONDS;
    private Integer acceptorThreads = SoapServerConstants.ACCEPTOR_THREADS_COUNT;
    private Integer coreThreads = SoapServerConstants.CORE_THREADS_COUNT;
    private Integer maxThreads = SoapServerConstants.MAX_THREADS_COUNT;
    private Integer threadKeepAliveTimeInSeconds = SoapServerConstants.THREAD_KEEP_ALIVE_TIME_IN_SECONDS;

    private URL keyStoreUrl;
    private String keyStoreType = SoapServerConstants.KEYSTORE_TYPE;
    private String keyStorePassword;

    // helper attributes
    private boolean http;
    private boolean https;

    // runtime attributes
    private ClassPathXmlApplicationContext context;
    private Server server;
    private GenericContextDomEndpoint endpoint;

    // ----------------------------------------------------------------
    // PUBLIC API
    // ----------------------------------------------------------------

    /**
     * Starts the SOAP server
     */
    public void start() {
        try {
            server.start();
        } catch (Exception ex) {
            throw new SoapServerException(ex);
        }
    }

    public boolean isRunning() {
        return server.isRunning();
    }

    public boolean isStarted() {
        return server.isStarted();
    }

    public boolean isStarting() {
        return server.isStarting();
    }

    public boolean isStopping() {
        return server.isStopping();
    }

    public boolean isStopped() {
        return server.isStopped();
    }

    public boolean isNotRunning() {
        return isStopping() || isStopped();
    }

    public boolean isFailed() {
        return server.isFailed();
    }

    /**
     * Stops the SOAP server (does not release the allocated resources)
     */
    public void stop() {
        try {
            server.stop();
        } catch (Exception ex) {
            throw new SoapServerException(ex);
        }
    }

    /**
     * Stops the SOAP server and deallocates resources
     */
    public void destroy() {
        stop();
        context.close();
    }

    /**
     * Registers responder under specified context path. Only one responder may be registered under one context path.
     *
     * @param contextPath parameter
     * @param responder parameter
     * @throws ServiceRegistrationException thrown if a registration error occurs - for example duplicate responder registered
     */
    public void registerRequestResponder(String contextPath, RequestResponder responder) throws ServiceRegistrationException {
        checkNotNull(contextPath, "contextPath cannot be null");
        checkNotNull(responder, "responder cannot be null");
        validateUrl(contextPath);
        log.info(String.format("Registering responder [%s] under the url [%s]", responder, getUrl(contextPath)));
        endpoint.registerRequestResponder(contextPath, responder);
    }

    /**
     * Unregisters responder from the specified context path
     *
     * @param contextPath parameter
     * @throws ServiceRegistrationException thrown if an unregistration error occurs - for example no responder registerd
     */
    public void unregisterRequestResponder(String contextPath) throws ServiceRegistrationException {
        checkNotNull(contextPath, "contextPath cannot be null");
        log.info(String.format("Unregistering responder from the url [%s]", getUrl(contextPath)));
        endpoint.unregisterRequestResponder(contextPath);
    }

    private String getUrl(String contextPath) {
        String format = String.format("%s://localhost:%d%s", http ? "http" : "https", http ? httpPort : httpsPort, contextPath);
        return format;
    }

    private void validateUrl(String contextPath) {
        checkArgument(contextPath.startsWith("/"), "contextPath has to begin with a slash");
        String url = getUrl(contextPath);
        try {
            new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(String.format("URL with context path is malformed [%s]", url), e);
        }
    }

    /**
     * @return a list of registered context paths
     */
    public List<String> getRegisteredContextPaths() {
        return Collections.list(endpoint.getRegisteredContextPaths());
    }

    // ----------------------------------------------------------------
    // INTERNAL API
    // ----------------------------------------------------------------
    private void configure() {
        configureParentContext();
        configureConnectors();
        configureWebContext();
    }

    private void configureParentContext() {
        PropertyPlaceholderConfigurer config = new PropertyPlaceholderConfigurer();
        config.setProperties(buildProperties());
        context = new ClassPathXmlApplicationContext();
        context.addBeanFactoryPostProcessor(config);
        context.setConfigLocation(SoapServerConstants.SPRING_CONTEXT_LOCATION);
        context.refresh();
        context.registerShutdownHook();
        server = context.getBean(SoapServerConstants.SERVER_BEAN_NAME, Server.class);
    }

    private void configureConnectors() {
        if (http) {
            SelectChannelConnector httpConnector = context.getBean(SoapServerConstants.CONNECTOR_BEAN_NAME, SelectChannelConnector.class);
            configureHttpConnector(httpConnector);
            server.addConnector(httpConnector);
        }
        if (https) {
            checkNotNull(keyStoreUrl, "keyStore has to be set in https mode");
            SslSelectChannelConnector httpsConnector = context.getBean(SoapServerConstants.SSL_CONNECTOR_BEAN_NAME, SslSelectChannelConnector.class);
            configureHttpsConnector(httpsConnector);
            server.addConnector(httpsConnector);
        }
    }

    private SelectChannelConnector configureHttpConnector(SelectChannelConnector connector) {
        configureGenericConnector(connector);
        connector.setReuseAddress(reuseAddress);
        connector.setPort(httpPort);
        return connector;
    }

    private SslSelectChannelConnector configureHttpsConnector(SslSelectChannelConnector connector) {
        configureGenericConnector(connector);
        connector.setReuseAddress(reuseAddress);
        connector.setPort(httpsPort);
        connector.setKeystore(keyStoreUrl.toString());
        connector.setKeystoreType(keyStoreType);
        connector.setKeyPassword(keyStorePassword);
        return connector;
    }

    private AbstractConnector configureGenericConnector(AbstractConnector connector) {
        connector.setAcceptors(acceptorThreads);
        connector.setMaxIdleTime(connectionMaxIdleTimeInSeconds * SoapServerConstants.SECONDS_TO_MILLIS_RATIO);
        return connector;
    }

    private void configureWebContext() {
        ServletContext servletContext = getServletContext();
        GenericWebApplicationContext webContext = new GenericWebApplicationContext();
        webContext.setServletContext(servletContext);
        webContext.setParent(context);
        webContext.refresh();
        servletContext.setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, webContext);
        if (webContext != null) {
            endpoint = webContext.getBean(SoapServerConstants.ENDPOINT_BEAN_NAME, GenericContextDomEndpoint.class);
        }
    }

    private ServletContext getServletContext() {
        for (Handler handler : server.getHandlers()) {
            if (handler instanceof Context) {
                return ((Context) handler).getServletContext();
            }
        }
        return null;
    }

    private Properties buildProperties() {
        Properties properties = new Properties();
        properties.setProperty(SoapServerConstants.CORE_POOL_SIZE_PROP_KEY, coreThreads.toString());
        properties.setProperty(SoapServerConstants.MAX_POOL_SIZE_PROP_KEY, maxThreads.toString());
        properties.setProperty(SoapServerConstants.KEEP_ALIVE_PROP_KEY, threadKeepAliveTimeInSeconds.toString());
        return properties;
    }

    // ----------------------------------------------------------------
    // BUILDER API
    // ----------------------------------------------------------------
    private SoapServer() {
    }

    /**
     * Builder to construct a properly populated SoapServer
     */
    public static class Builder {
        private final SoapServer server = new SoapServer();

        /**
         * @param value Sets the http port on which the server listens. Has to be not negative.
         * @return builder
         */
        public Builder httpPort(int value) {
            checkArgument(value >= 0);
            server.http = true;
            server.httpPort = value;
            return this;
        }

        /**
         * @param value Sets the https port on which the server listens. Has to be not negative.
         * @return builder
         */
        public Builder httpsPort(int value) {
            checkArgument(value >= 0);
            server.https = true;
            server.httpsPort = value;
            return this;
        }

        /**
         * @param value Sets the connection max idle time in seconds. Has to be not negative.
         * @return builder
         */
        public Builder connectionMaxIdleTimeInSeconds(int value) {
            checkArgument(value >= 0);
            server.connectionMaxIdleTimeInSeconds = value;
            return this;
        }

        /**
         * @param value Sets the number of http server connector acceptor threads. Has to be positive.
         * @return builder
         */
        public Builder acceptorThreads(int value) {
            checkArgument(value > 0);
            server.acceptorThreads = value;
            return this;
        }

        /**
         * @param value Sets the number of http server core threads. Has to be positive.
         * @return builder
         */
        public Builder coreThreads(int value) {
            checkArgument(value > 0);
            server.coreThreads = value;
            return this;
        }

        /**
         * @param value Sets the maximal number of threads that the http server may spawn. Has to be positive.
         * @return builder
         */
        public Builder maxThreads(int value) {
            checkArgument(value > 0);
            server.maxThreads = value;
            return this;
        }

        /**
         * @param value Sets the value of thread keep alive in seconds. Has to be not negative.
         * @return builder
         */
        public Builder threadKeepAliveTimeInSeconds(int value) {
            checkArgument(value >= 0);
            server.threadKeepAliveTimeInSeconds = value;
            return this;
        }

        /**
         * @param value Specifies the URL of the keystore to use in the SOAP communication. Null is not accepted. If there's more than
         *              one certificate in the keystore it is undefined which of them will be used
         * @return builder
         */
        public Builder keyStoreUrl(URL value) {
            checkNotNull(value);
            server.keyStoreUrl = value;
            return this;
        }

        /**
         * @param value Specifies the type of the keystore. Null is not accepted.
         * @return builder
         */
        public Builder keyStoreType(String value) {
            checkNotNull(value);
            server.keyStoreType = value;
            return this;
        }

        /**
         * @param value keystore password. Null is accepted.
         * @return builder
         */
        public Builder keyStorePassword(String value) {
            server.keyStorePassword = value;
            return this;
        }

        /**
         * @param value Sets the reuseAddress on the underlying @see java.net.Socket
         * @return builder
         */
        public Builder reuseAddress(boolean value) {
            server.reuseAddress = value;
            return this;
        }

        /**
         * Builds populated SoapServer instance
         *
         * @return builder
         */
        public SoapServer build() {
            server.configure();
            return server;
        }
    }

    /**
     * @return a new instance of a SoapServer Builder
     */
    public static Builder builder() {
        return new Builder();
    }

}
