package org.folio.client;

import static javax.ws.rs.core.HttpHeaders.ACCEPT;
import static javax.ws.rs.core.HttpHeaders.CONTENT_TYPE;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.MediaType.TEXT_PLAIN;
import static org.folio.util.LogUtil.asJson;
import static org.folio.util.LogUtil.bodyAsString;

import java.util.Map;

import javax.ws.rs.BadRequestException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.rest.jaxrs.model.EventEntity;
import org.folio.rest.jaxrs.model.EventEntityCollection;
import org.folio.rest.jaxrs.model.NotifySendRequest;
import org.folio.rest.jaxrs.model.TemplateProcessingRequest;
import org.folio.rest.jaxrs.model.TemplateProcessingResult;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpResponse;

public class NoticesClient extends OkapiClient {
  private static final Logger log = LogManager.getLogger(NoticesClient.class);

  public NoticesClient(Vertx vertx, Map<String, String> okapiHeaders) {
    super(vertx, okapiHeaders);
  }

  public Future<EventEntity> getEventConfig(String name) {
    log.debug("getEventConfig:: parameters name: {}", name);
    Promise<HttpResponse<Buffer>> promise = Promise.promise();
    getAbs("/eventConfig")
      .addQueryParam("query", "name==" + name)
      .putHeader(ACCEPT, APPLICATION_JSON)
      .send(promise);

    return promise.future()
      .map(responseMapper(EventEntityCollection.class))
      .map(collection -> collection.getEventEntity()
        .stream()
        .findAny()
        .orElseThrow(() -> new BadRequestException("Cannot fetch event entity")))
      .onSuccess(r -> log.info("getEventConfig:: result: {}", () -> asJson(r)));
  }

  public Future<TemplateProcessingResult> postTemplateRequest(TemplateProcessingRequest request) {
    log.debug("postTemplateRequest:: parameters request: {}", () -> asJson(request));
    Promise<HttpResponse<Buffer>> promise = Promise.promise();
    postAbs("/template-request")
      .putHeader(ACCEPT, APPLICATION_JSON)
      .putHeader(CONTENT_TYPE, APPLICATION_JSON)
      .sendJson(request, promise);

    return promise.future()
      .onSuccess(r -> log.info("postTemplateRequest:: Posted Template Request Successfully"))
      .map(responseMapper(TemplateProcessingResult.class))
      .onSuccess(r -> log.info("postTemplateRequest:: Posted Template Request Successfully"));
  }

  public Future<Void> postMessageDelivery(NotifySendRequest request) {
    log.debug("postMessageDelivery:: parameters request: {}", () -> asJson(request));
    Promise<HttpResponse<Buffer>> promise = Promise.promise();
    postAbs("/message-delivery")
      .putHeader(ACCEPT, TEXT_PLAIN)
      .sendJson(request, promise);

    return promise.future()
      .onSuccess(r -> log.info("postMessageDelivery:: result: {}", () -> bodyAsString(r)))
      .map(responseMapper(Void.class));
  }
}
