package org.folio.rest.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.folio.client.OkapiModulesClient;
import org.folio.client.impl.OkapiModulesClientImpl;
import org.folio.rest.jaxrs.model.Error;
import org.folio.rest.jaxrs.model.Errors;
import org.folio.rest.jaxrs.model.PatronNoticeEntity;
import org.folio.rest.jaxrs.resource.PatronNotice;
import org.folio.rest.tools.messages.MessageConsts;
import org.folio.rest.tools.messages.Messages;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.core.Response;
import java.util.Map;

import static io.vertx.core.Future.succeededFuture;
import static java.util.Collections.singletonList;
import static org.folio.helper.OkapiModulesClientHelper.buildNotifySendRequest;
import static org.folio.helper.OkapiModulesClientHelper.buildTemplateProcessingRequest;

public class PatronNoticeResourceImpl implements PatronNotice {

  private static final Logger logger = LoggerFactory.getLogger(PatronNoticeResourceImpl.class);

  private final Messages messages = Messages.getInstance();

  @Override
  public void postPatronNotice(String lang,
                               PatronNoticeEntity entity,
                               Map<String, String> okapiHeaders,
                               Handler<AsyncResult<Response>> asyncResultHandler,
                               Context vertxContext) {

    OkapiModulesClient client = new OkapiModulesClientImpl(vertxContext.owner(), okapiHeaders);

    client.postTemplateRequest(buildTemplateProcessingRequest(entity))
      .map(result -> buildNotifySendRequest(result, entity))
      .compose(client::postMessageDelivery)
      .setHandler(res -> {
        if (res.failed()) {
          logger.error(res.cause());
          if (res.cause().getClass() == BadRequestException.class) {
            Error error = new Error().withMessage(res.cause().getMessage());
            Errors errors = new Errors().withErrors(singletonList(error));
            asyncResultHandler.handle(succeededFuture(PostPatronNoticeResponse.respond422WithApplicationJson(errors)));
            return;
          }
          asyncResultHandler.handle(succeededFuture(PostPatronNoticeResponse
            .respond500WithTextPlain(messages.getMessage(lang, MessageConsts.InternalServerError))));
        } else {
          asyncResultHandler.handle(succeededFuture(PostPatronNoticeResponse.respond200()));
        }
      });
  }
}
