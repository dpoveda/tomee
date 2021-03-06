/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.openejb.server.httpd;

import org.apache.openejb.AppContext;
import org.apache.openejb.assembler.classic.WebAppBuilder;
import org.apache.openejb.cdi.CdiAppContextsService;
import org.apache.openejb.cdi.Proxys;
import org.apache.openejb.core.WebContext;
import org.apache.openejb.loader.SystemInstance;
import org.apache.openejb.util.AppFinder;
import org.apache.openejb.web.LightweightWebAppBuilder;
import org.apache.webbeans.config.WebBeansContext;
import org.apache.webbeans.spi.ContextsService;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringTokenizer;

/**
 * @version $Revision$ $Date$
 */
public class HttpListenerRegistry implements HttpListener {
    private final Map<String, HttpListener> registry = new LinkedHashMap<>();
    private final Map<String, Collection<HttpListener>> filterRegistry = new LinkedHashMap<>();
    private final ThreadLocal<FilterListener> currentFilterListener = new ThreadLocal<>();
    private final ThreadLocal<HttpRequest> request = new ThreadLocal<>();

    public HttpListenerRegistry() {
        HttpServletRequest mock = null;
        final SystemInstance systemInstance = SystemInstance.get();
        if ("true".equalsIgnoreCase(systemInstance.getProperty("openejb.http.mock-request", "false"))) {
            HttpRequestImpl mockRequest = null;
            try {
                mockRequest = new HttpRequestImpl(new URI("http://mock/"));
                mockRequest.parseURI(new StringTokenizer("mock\n"));  // will do http://mock/mock, we don't really care
                mock = mockRequest;
            } catch (final Exception e) {
                // no-op
            }
        }
        if (systemInstance.getComponent(HttpServletRequest.class) == null) {
            systemInstance.setComponent(HttpServletRequest.class, Proxys.threadLocalProxy(HttpServletRequest.class, request, mock));
        }
        if (systemInstance.getComponent(HttpSession.class) == null) {
            final javax.servlet.http.HttpSession delegate = mock != null ? mock.getSession() : null;
            systemInstance.setComponent(javax.servlet.http.HttpSession.class, Proxys.threadLocalRequestSessionProxy(request, new ServletSessionAdapter(delegate) {
                @Override
                public void invalidate() {
                    final Object web = AppFinder.findAppContextOrWeb(Thread.currentThread().getContextClassLoader(), AppFinder.AppOrWebContextTransformer.INSTANCE);
                    if (WebContext.class.isInstance(web)) {
                        doInvokeSpecificListeners(WebContext.class.cast(web).getContextRoot());
                    } else if (AppContext.class.isInstance(web)) {
                        doInvokeSpecificListeners(AppContext.class.cast(web).getId());
                    }
                    super.invalidate();
                }

                private void doInvokeSpecificListeners(final String web) {
                    final WebAppBuilder wab = SystemInstance.get().getComponent(WebAppBuilder.class);
                    if (LightweightWebAppBuilder.class.isInstance(wab)) {
                        final Collection<HttpSessionListener> listeners = LightweightWebAppBuilderListenerExtractor.findByTypeForContext(web, HttpSessionListener.class);
                        final HttpSessionEvent event = new HttpSessionEvent(this);
                        for (final HttpSessionListener o : listeners) {
                            try {
                                o.sessionDestroyed(event);
                            } catch (final Throwable th) {
                                // ignore, may be undeployed
                            }
                        }
                    }
                }
            }));
        }
        if (systemInstance.getComponent(ServletContext.class) == null) { // a poor impl but at least we set something
            systemInstance.setComponent(ServletContext.class, new EmbeddedServletContext());
        }
    }

    @Override
    public void onMessage(final HttpRequest request, final HttpResponse response) throws Exception {
        final String path;
        if (!HttpRequestImpl.class.isInstance(request)) {
            path = request.getRequestURI();
        } else {
            path = getRequestHandledPath(request);
        }

        final FilterListener currentFL = currentFilterListener.get();

        // first look filters
        Map<String, Collection<HttpListener>> filters;
        synchronized (filterRegistry) {
            filters = new HashMap<>(filterRegistry);
        }

        final HttpRequest registered = this.request.get();
        final boolean reset = registered == null;
        try {
            if (reset) {
                this.request.set(request);
            }
            boolean lastWasCurrent = false;
            for (Map.Entry<String, Collection<HttpListener>> entry : filters.entrySet()) {
                String pattern = entry.getKey();
                for (HttpListener listener : entry.getValue()) {
                    if ((lastWasCurrent || currentFL == null) && path.matches(pattern)) {
                        listener.onMessage(request, response);
                        return;
                    }
                    lastWasCurrent = listener == currentFL;
                }
            }


            // then others
            Map<String, HttpListener> listeners;
            synchronized (registry) {
                listeners = new HashMap<>(registry);
            }

            for (final Map.Entry<String, HttpListener> entry : listeners.entrySet()) {
                final String pattern = entry.getKey();
                if (path.matches(pattern) || path.equals(pattern)) {
                    final WebBeansContext wbc = WebBeansContext.class.cast(request.getAttribute("openejb_owb_context"));
                    if (wbc != null) {
                        CdiAppContextsService.class.cast(wbc.getService(ContextsService.class)).checkConversationState();
                    }
                    entry.getValue().onMessage(request, response);
                    break;
                }
            }
        } finally {
            if (currentFL == null) {
                currentFilterListener.set(null);
            }
            if (reset) {
                this.request.set(null);
            }
        }
    }

    private String getRequestHandledPath(final HttpRequest request) {
        final String servletPath = request.getServletPath();
        return request.getContextPath() + (!servletPath.startsWith("/") ? "/" : "") + servletPath;
    }

    public void addHttpListener(HttpListener listener, String regex) {
        synchronized (registry) {
            registry.put(regex, listener);
        }
    }

    public HttpListener removeHttpListener(String regex) {
        HttpListener listener;
        synchronized (registry) {
            listener = registry.remove(regex);
        }
        return listener;
    }

    public void addHttpFilter(HttpListener listener, String regex) {
        synchronized (filterRegistry) {
            if (!filterRegistry.containsKey(regex)) {
                filterRegistry.put(regex, new ArrayList<HttpListener>());
            }
            filterRegistry.get(regex).add(listener);
        }
    }

    public Collection<HttpListener> removeHttpFilter(String regex) {
        synchronized (filterRegistry) {
            return filterRegistry.remove(regex);
        }
    }

    public void setOrigin(final FilterListener origin) {
        if (origin == null) {
            currentFilterListener.remove();
        } else {
            currentFilterListener.set(origin);
        }
    }
}
