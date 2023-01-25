package org.folio.helper;

import static java.util.Collections.singletonList;
import static org.folio.util.LogUtil.asJson;
import static org.folio.util.LogUtil.patronNoticeAsString;

import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.rest.jaxrs.model.Message;
import org.folio.rest.jaxrs.model.Notification;
import org.folio.rest.jaxrs.model.NotifySendRequest;
import org.folio.rest.jaxrs.model.PatronNoticeEntity;
import org.folio.rest.jaxrs.model.Template;
import org.folio.rest.jaxrs.model.TemplateProcessingRequest;
import org.folio.rest.jaxrs.model.TemplateProcessingResult;

public class OkapiModulesClientHelper {
  private static final Logger log = LogManager.getLogger(OkapiModulesClientHelper.class);

  public NotifySendRequest buildNotifySendRequest(TemplateProcessingResult templateProcessingResult,
    PatronNoticeEntity entity) {

    log.debug("buildNotifySendRequest:: parameters result: {}, entity: {}",
      () -> asJson(templateProcessingResult), () -> patronNoticeAsString(entity));

    Message message = new Message()
        .withHeader(templateProcessingResult.getResult().getHeader())
        .withBody(templateProcessingResult.getResult().getBody())
        .withAttachments(templateProcessingResult.getResult().getAttachments())
        .withDeliveryChannel(entity.getDeliveryChannel())
        .withOutputFormat(entity.getOutputFormat());

    NotifySendRequest result = new NotifySendRequest()
      .withNotificationId(UUID.randomUUID().toString())
      .withMessages(singletonList(message))
      .withRecipientUserId(entity.getRecipientId());

    log.info("buildNotifySendRequest:: result: NotifySendRequest(id={})",
      result::getNotificationId);

    return result;
  }

  public NotifySendRequest buildNotifySendRequest(List<Message> messages, Notification entity) {
    log.debug("buildNotifySendRequest:: parameters messages: {}, entity: {}",
      () -> asJson(messages), () -> asJson(entity));

    NotifySendRequest result = new NotifySendRequest()
      .withNotificationId(UUID.randomUUID().toString())
      .withMessages(messages)
      .withRecipientUserId(entity.getRecipientId());

    log.info("buildNotifySendRequest:: result: {}", () -> asJson(result));

    return result;
  }

  public TemplateProcessingRequest buildTemplateProcessingRequest(PatronNoticeEntity entity) {
    log.debug("buildTemplateProcessingRequest:: parameters entity: {}",
      () -> patronNoticeAsString(entity));

    TemplateProcessingRequest result = new TemplateProcessingRequest()
      .withTemplateId(entity.getTemplateId())
      .withOutputFormat(entity.getOutputFormat())
      .withLang(entity.getLang())
      .withContext(entity.getContext());

    log.info("buildTemplateProcessingRequest:: result: TemplateProcessingRequest");

    return result;
  }

  public TemplateProcessingRequest buildTemplateProcessingRequest(Template template,
    Notification notification) {

    log.debug("buildTemplateProcessingRequest:: parameters template: {}, notification: {}",
      () -> asJson(template), () -> asJson(notification));

    TemplateProcessingRequest result = new TemplateProcessingRequest()
      .withTemplateId(template.getTemplateId())
      .withOutputFormat(template.getOutputFormat())
      .withLang(notification.getLang())
      .withContext(notification.getContext());

    log.info("buildTemplateProcessingRequest:: result: {}", () -> asJson(result));

    return result;
  }
}
