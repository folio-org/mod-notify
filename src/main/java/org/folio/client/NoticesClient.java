package org.folio.client;

import static javax.ws.rs.core.HttpHeaders.ACCEPT;
import static javax.ws.rs.core.HttpHeaders.CONTENT_TYPE;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.MediaType.TEXT_PLAIN;

import java.util.Map;

import javax.ws.rs.BadRequestException;

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

  public NoticesClient(Vertx vertx, Map<String, String> okapiHeaders) {
    super(vertx, okapiHeaders);
  }

  public Future<EventEntity> getEventConfig(String name) {
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
        .orElseThrow(() -> new BadRequestException("Cannot fetch event entity")));
  }

  public Future<TemplateProcessingResult> postTemplateRequest(TemplateProcessingRequest request) {
    Promise<HttpResponse<Buffer>> promise = Promise.promise();
    postAbs("/template-request")
      .putHeader(ACCEPT, APPLICATION_JSON)
      .putHeader(CONTENT_TYPE, APPLICATION_JSON)
      .sendJson(request, promise);

    return promise.future().map(responseMapper(TemplateProcessingResult.class));
  }

  public Future<Void> postMessageDelivery(NotifySendRequest request) {
    Promise<HttpResponse<Buffer>> promise = Promise.promise();
    postAbs("/message-delivery")
      .putHeader(ACCEPT, TEXT_PLAIN)
      .sendJson(request, promise);

    return promise.future().map(responseMapper(Void.class));
  }
}
