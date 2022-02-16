package org.folio.client.impl;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.folio.client.OkapiModulesClient;
import org.folio.helper.WebClientFactory;
import org.folio.rest.RestVerticle;
import org.folio.rest.jaxrs.model.EventEntity;
import org.folio.rest.jaxrs.model.EventEntityCollection;
import org.folio.rest.jaxrs.model.NotifySendRequest;
import org.folio.rest.jaxrs.model.TemplateProcessingRequest;
import org.folio.rest.jaxrs.model.TemplateProcessingResult;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import java.util.Map;
import java.util.function.Function;

import static org.apache.http.HttpStatus.SC_BAD_REQUEST;
import static org.apache.http.HttpStatus.SC_NO_CONTENT;
import static org.apache.http.HttpStatus.SC_OK;

public class OkapiModulesClientImpl implements OkapiModulesClient {

  private String tenant;
  private String token;
  private String okapiUrl;
  private String requestId;

  private WebClient webClient;

  private static final String OKAPI_HEADER_URL = "x-okapi-url";

  public OkapiModulesClientImpl(Vertx vertx, Map<String, String> okapiHeaders) {
    webClient = WebClientFactory.getWebClient(vertx);
    tenant = okapiHeaders.get(RestVerticle.OKAPI_HEADER_TENANT);
    token = okapiHeaders.get(RestVerticle.OKAPI_HEADER_TOKEN);
    okapiUrl = okapiHeaders.get(OKAPI_HEADER_URL);
    requestId = okapiHeaders.get(RestVerticle.OKAPI_REQUESTID_HEADER);
  }

  @Override
  public Future<EventEntity> getEventConfig(String name) {

    Promise<HttpResponse<Buffer>> promise = Promise.promise();

    webClient.getAbs(okapiUrl + "/eventConfig")
      .addQueryParam("query", "name==" + name)
      .putHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
      .putHeader(RestVerticle.OKAPI_HEADER_TENANT, tenant)
      .putHeader(RestVerticle.OKAPI_HEADER_TOKEN, token)
      .putHeader(RestVerticle.OKAPI_REQUESTID_HEADER, requestId)
      .send(promise);

    return promise.future()
      .map(responseMapper(EventEntityCollection.class))
      .map(collection -> collection.getEventEntity()
        .stream()
        .findAny()
        .orElseThrow(() -> new BadRequestException("Cannot fetch event entity")));
  }

  @Override
  public Future<TemplateProcessingResult> postTemplateRequest(TemplateProcessingRequest request) {

    Promise<HttpResponse<Buffer>> promise = Promise.promise();

    webClient.postAbs(okapiUrl + "/template-request")
      .putHeader(RestVerticle.OKAPI_HEADER_TENANT, tenant)
      .putHeader(RestVerticle.OKAPI_HEADER_TOKEN, token)
      .putHeader(RestVerticle.OKAPI_REQUESTID_HEADER, requestId)
      .putHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
      .putHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
      .sendJson(request, promise);

    return promise.future().map(responseMapper(TemplateProcessingResult.class));
  }

  @Override
  public Future<Void> postMessageDelivery(NotifySendRequest request) {

    Promise<HttpResponse<Buffer>> promise = Promise.promise();

    webClient.postAbs(okapiUrl + "/message-delivery")
      .putHeader(RestVerticle.OKAPI_HEADER_TENANT, tenant)
      .putHeader(RestVerticle.OKAPI_HEADER_TOKEN, token)
      .putHeader(RestVerticle.OKAPI_REQUESTID_HEADER, requestId)
      .putHeader(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN)
      .sendJson(request, promise);

    return promise.future().map(responseMapper(Void.class));
  }

  private static <T> Function<HttpResponse<Buffer>, T> responseMapper(Class<T> type) {

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
