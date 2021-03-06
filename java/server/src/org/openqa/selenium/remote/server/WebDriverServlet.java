// Licensed to the Software Freedom Conservancy (SFC) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The SFC licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.openqa.selenium.remote.server;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.openqa.selenium.remote.CapabilityType.BROWSER_NAME;
import static org.openqa.selenium.remote.server.DriverServlet.SESSION_TIMEOUT_PARAMETER;

import com.google.common.base.Splitter;
import com.google.common.net.HttpHeaders;
import com.google.common.net.MediaType;

import org.openqa.selenium.logging.LoggingHandler;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.server.commandhandler.ExceptionHandler;
import org.openqa.selenium.remote.server.log.LoggingManager;
import org.openqa.selenium.remote.server.log.PerSessionLogHandler;
import org.openqa.selenium.remote.server.xdrpc.CrossDomainRpc;
import org.openqa.selenium.remote.server.xdrpc.CrossDomainRpcLoader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.logging.Handler;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;

public class WebDriverServlet extends HttpServlet {

  private static final Logger LOG = Logger.getLogger(WebDriverServlet.class.getName());
  public static final String ACTIVE_SESSIONS_KEY = WebDriverServlet.class.getName() + ".sessions";

  private static final String CROSS_DOMAIN_RPC_PATH = "/xdrpc";

  private final StaticResourceHandler staticResourceHandler = new StaticResourceHandler();
  private final ExecutorService executor = Executors.newCachedThreadPool();
  private ActiveSessions allSessions;
  private AllHandlers handlers;

  @Override
  public void init() throws ServletException {
    configureLogging();
    log("Initialising WebDriverServlet");

    String value = getInitParameter(SESSION_TIMEOUT_PARAMETER);
    long inactiveSessionTimeout = value != null ?
                                  SECONDS.toMillis(Long.parseLong(value)) :
                                  Long.MAX_VALUE;

    allSessions = (ActiveSessions) getServletContext().getAttribute(ACTIVE_SESSIONS_KEY);
    if (allSessions == null) {
      allSessions = new ActiveSessions(inactiveSessionTimeout, MILLISECONDS);
      getServletContext().setAttribute(ACTIVE_SESSIONS_KEY, allSessions);
    }

    handlers = new AllHandlers(allSessions);
  }

  private synchronized Logger configureLogging() {
    Logger logger = Logger.getGlobal();
    logger.addHandler(LoggingHandler.getInstance());

    Logger rootLogger = Logger.getLogger("");
    boolean sessionLoggerAttached = false;
    for (Handler handler : rootLogger.getHandlers()) {
      sessionLoggerAttached |= handler instanceof PerSessionLogHandler;
    }
    if (!sessionLoggerAttached) {
      rootLogger.addHandler(LoggingManager.perSessionLogHandler());
    }

    return logger;
  }

  @Override
  protected void service(HttpServletRequest request, HttpServletResponse response)
      throws IOException, ServletException {
    if (request.getHeader("Origin") != null) {
      setAccessControlHeaders(response);
    }
    // Make sure our browser-clients never cache responses.
    response.setHeader("Expires", "Thu, 01 Jan 1970 00:00:00 GMT");
    response.setHeader("Cache-Control", "no-cache");
    super.service(request, response);
  }

  /**
   * Sets access control headers to allow cross-origin resource sharing from
   * any origin.
   *
   * @param response The response to modify.
   * @see <a href="http://www.w3.org/TR/cors/">http://www.w3.org/TR/cors/</a>
   */
  private void setAccessControlHeaders(HttpServletResponse response) {
    response.setHeader("Access-Control-Allow-Origin", "*");  // Real safe.
    response.setHeader("Access-Control-Allow-Methods", "DELETE,GET,HEAD,POST");
    response.setHeader("Access-Control-Allow-Headers", "Accept,Content-Type");
  }

  @Override
  protected void doDelete(HttpServletRequest req, HttpServletResponse resp)
      throws ServletException, IOException {
    handle(req, resp);
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp)
      throws ServletException, IOException {
    if (req.getPathInfo() == null || "/".equals(req.getPathInfo())) {
      staticResourceHandler.redirectToHub(req, resp);
    } else if (staticResourceHandler.isStaticResourceRequest(req)) {
      staticResourceHandler.service(req, resp);
    } else {
      handle(req, resp);
    }
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp)
      throws ServletException, IOException {
    if (CROSS_DOMAIN_RPC_PATH.equalsIgnoreCase(req.getPathInfo())) {
      handleCrossDomainRpc(req, resp);
    } else {
      handle(req, resp);
    }
  }

  private void handleCrossDomainRpc(
      HttpServletRequest servletRequest, HttpServletResponse servletResponse)
      throws ServletException, IOException {
    CrossDomainRpc rpc;

    try {
      rpc = new CrossDomainRpcLoader().loadRpc(servletRequest);
    } catch (IllegalArgumentException e) {
      servletResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      servletResponse.getOutputStream().println(e.getMessage());
      servletResponse.getOutputStream().flush();
      return;
    }

    servletRequest.setAttribute(HttpHeaders.CONTENT_TYPE, MediaType.JSON_UTF_8.toString());
    HttpServletRequestWrapper wrapper = new HttpServletRequestWrapper(servletRequest) {
      @Override
      public String getMethod() {
        return rpc.getMethod();
      }

      @Override
      public String getPathInfo() {
        return rpc.getPath();
      }

      @Override
      public ServletInputStream getInputStream() throws IOException {
        return new InputStreamWrappingServletInputStream(
            new ByteArrayInputStream(rpc.getContent()));
      }
    };

    handle(wrapper, servletResponse);
  }

  private void handle(HttpServletRequest req, HttpServletResponse resp) {
    CommandHandler handler = handlers.match(req);

    LOG.info("Found handler: " + handler);

    boolean invalidateSession =
        handler instanceof ActiveSession &&
        "DELETE".equalsIgnoreCase(req.getMethod()) &&
        req.getPathInfo().equals("/session/" + ((ActiveSession) handler).getId());

    Future<?> execution = executor.submit(() -> {
      // Clear the logs
      PerSessionLogHandler sessionLogHandler = LoggingManager.perSessionLogHandler();
      sessionLogHandler.clearThreadTempLogs();

      try {
        if (handler instanceof ActiveSession) {
          sessionLogHandler .attachToCurrentThread(((ActiveSession) handler).getId());
          ActiveSession session = (ActiveSession) handler;
          Thread.currentThread().setName(String.format(
              "Handler thread for session %s (%s)",
              session.getId(),
              session.getCapabilities().get(BROWSER_NAME)));
        } else {
          // All commands that take a session id expect that as the path fragment immediately after "/session".
          List<String> fragments = Splitter.on('/').limit(4).splitToList(req.getPathInfo());
          if (fragments.size() > 2) {
            if ("session".equals(fragments.get(1))) {
              sessionLogHandler.attachToCurrentThread(new SessionId(fragments.get(2)));
            }
          }

          Thread.currentThread().setName(req.getPathInfo());
        }
        LOG.info(String.format(
            "%s: Executing %s on %s (handler: %s)",
            Thread.currentThread().getName(),
            req.getMethod(),
            req.getPathInfo(),
            handler.getClass().getSimpleName()));
        handler.execute(
            new ServletRequestWrappingHttpRequest(req),
            new ServletResponseWrappingHttpResponse(resp));
      } catch (IOException e) {
        resp.reset();
        throw new RuntimeException(e);
      } finally {
        Thread.currentThread().setName("Selenium WebDriver Servlet - Quiescent Thread");
        sessionLogHandler.detachFromCurrentThread();
      }
    });

    try {
      execution.get(10, MINUTES);
    } catch (ExecutionException e) {
      resp.reset();
      new ExceptionHandler(e).execute(
          new ServletRequestWrappingHttpRequest(req),
          new ServletResponseWrappingHttpResponse(resp));
    } catch (InterruptedException e) {
      log("Unexpectedly interrupted: " + e.getMessage(), e);
      invalidateSession = true;

      Thread.currentThread().interrupt();
    } catch (TimeoutException e) {
      invalidateSession = true;
    }

    if (invalidateSession && handler instanceof ActiveSession) {
      allSessions.invalidate(((ActiveSession) handler).getId());
    }
  }
}
