package org.folio.client;

import static org.apache.http.HttpStatus.SC_BAD_REQUEST;
import static org.apache.http.HttpStatus.SC_NO_CONTENT;
import static org.apache.http.HttpStatus.SC_OK;
import static org.folio.okapi.common.WebClientFactory.getWebClient;
import static org.folio.okapi.common.XOkapiHeaders.REQUEST_ID;
import static org.folio.okapi.common.XOkapiHeaders.TENANT;
import static org.folio.okapi.common.XOkapiHeaders.TOKEN;
import static org.folio.okapi.common.XOkapiHeaders.URL;
import static org.folio.util.LogUtil.asJson;

import java.util.Map;
import java.util.function.Function;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.InternalServerErrorException;

import org.apache.commons.collections4.map.CaseInsensitiveMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;

public class OkapiClient {
  private static final Logger log = LogManager.getLogger(OkapiClient.class);
  private final WebClient webClient;
  private final String okapiUrl;
  private final String tenant;
  private final String token;
  private final String requestId;

  public OkapiClient(Vertx vertx, Map<String, String> okapiHeaders) {
    CaseInsensitiveMap<String, String> headers = new CaseInsensitiveMap<>(okapiHeaders);
    this.webClient = getWebClient(vertx);
    this.okapiUrl = headers.get(URL);
    this.tenant = headers.get(TENANT);
    this.token = headers.get(TOKEN);
    this.requestId = headers.get(REQUEST_ID);
  }

  protected HttpRequest<Buffer> postAbs(String path) {
    log.debug("postAbs:: parameters path: {}", path);
    return webClient.requestAbs(HttpMethod.POST, okapiUrl + path)
      .putHeader(TENANT, tenant)
      .putHeader(TOKEN, token)
      .putHeader(REQUEST_ID, requestId);
  }

  protected HttpRequest<Buffer> getAbs(String path) {
    log.debug("getAbs:: parameters path: {}", path);
    return webClient.requestAbs(HttpMethod.GET, okapiUrl + path)
      .putHeader(TENANT, tenant)
      .putHeader(TOKEN, token)
      .putHeader(REQUEST_ID, requestId);
  }

  protected static <T> Function<HttpResponse<Buffer>, T> responseMapper(Class<T> type) {
    log.debug("responseMapper:: parameters type: {}", type);
    return resp -> {
      if (resp.statusCode() == SC_BAD_REQUEST) {
        log.info("responseMapper:: response status code is {}", SC_BAD_REQUEST);
        throw new BadRequestException(resp.bodyAsString());
      }
      if (resp.statusCode() != SC_OK && resp.statusCode() != SC_NO_CONTENT) {
        log.info("responseMapper:: response status code is not {} or {}", SC_OK, SC_NO_CONTENT);
        throw new InternalServerErrorException();
      }
      T result = type == Void.class ? null : resp.bodyAsJson(type);
      log.info("responseMapper:: Mapped Response Successfully");
      return result;
    };
  }
}
