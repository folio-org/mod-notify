package org.folio.rest.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.client.NoticesClient;
import org.folio.helper.OkapiModulesClientHelper;
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

public class PatronNoticeResourceImpl implements PatronNotice {

  private static final Logger logger = LogManager.getLogger(PatronNoticeResourceImpl.class);

  private final Messages messages = Messages.getInstance();
  private final OkapiModulesClientHelper okapiModulesClientHelper = new OkapiModulesClientHelper();

  @Override
  public void postPatronNotice(String lang, PatronNoticeEntity entity,
    Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler,
    Context vertxContext) {

    NoticesClient client = getNoticesClient(vertxContext, okapiHeaders);

    client.postTemplateRequest(getOkapiModulesClientHelper().buildTemplateProcessingRequest(entity))
      .map(result -> getOkapiModulesClientHelper().buildNotifySendRequest(result, entity))
      .compose(client::postMessageDelivery)
      .onComplete(res -> {
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

  NoticesClient getNoticesClient(Context vertxContext, Map<String,
    String> okapiHeaders) {

    return new NoticesClient(vertxContext.owner(), okapiHeaders);
  }

  OkapiModulesClientHelper getOkapiModulesClientHelper() {
    return okapiModulesClientHelper;
  }
}
