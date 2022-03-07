package org.folio.client;

import static org.apache.http.HttpStatus.SC_BAD_REQUEST;
import static org.apache.http.HttpStatus.SC_NO_CONTENT;
import static org.apache.http.HttpStatus.SC_OK;
import static org.folio.okapi.common.WebClientFactory.getWebClient;
import static org.folio.okapi.common.XOkapiHeaders.REQUEST_ID;
import static org.folio.okapi.common.XOkapiHeaders.TENANT;
import static org.folio.okapi.common.XOkapiHeaders.TOKEN;
import static org.folio.okapi.common.XOkapiHeaders.URL;

import java.util.Map;
import java.util.function.Function;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.InternalServerErrorException;

import org.apache.commons.collections4.map.CaseInsensitiveMap;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;

public class OkapiClient {
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
    return webClient.requestAbs(HttpMethod.POST, okapiUrl + path)
      .putHeader(TENANT, tenant)
      .putHeader(TOKEN, token)
      .putHeader(REQUEST_ID, requestId);
  }

  protected HttpRequest<Buffer> getAbs(String path) {
    return webClient.requestAbs(HttpMethod.GET, okapiUrl + path)
      .putHeader(TENANT, tenant)
      .putHeader(TOKEN, token)
      .putHeader(REQUEST_ID, requestId);
  }

  protected static <T> Function<HttpResponse<Buffer>, T> responseMapper(Class<T> type) {
    return resp -> {
      if (resp.statusCode() == SC_BAD_REQUEST) {
        throw new BadRequestException(resp.bodyAsString());
      }
      if (resp.statusCode() != SC_OK && resp.statusCode() != SC_NO_CONTENT) {
        throw new InternalServerErrorException();
      }
      return type == Void.class ? null : resp.bodyAsJson(type);
    };
  }
}
