package org.folio.client;

import io.vertx.core.Future;
import org.folio.rest.jaxrs.model.EventEntity;
import org.folio.rest.jaxrs.model.NotifySendRequest;
import org.folio.rest.jaxrs.model.TemplateProcessingRequest;
import org.folio.rest.jaxrs.model.TemplateProcessingResult;

public interface OkapiModulesClient {

  Future<EventEntity> getEventConfig(String name);

  Future<TemplateProcessingResult> postTemplateRequest(TemplateProcessingRequest request);

  Future<Void> postMessageDelivery(NotifySendRequest request);
}
