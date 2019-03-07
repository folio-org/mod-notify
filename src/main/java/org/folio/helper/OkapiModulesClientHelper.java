package org.folio.helper;

import org.folio.rest.jaxrs.model.Message;
import org.folio.rest.jaxrs.model.Notification;
import org.folio.rest.jaxrs.model.NotifySendRequest;
import org.folio.rest.jaxrs.model.PatronNoticeEntity;
import org.folio.rest.jaxrs.model.Template;
import org.folio.rest.jaxrs.model.TemplateProcessingRequest;
import org.folio.rest.jaxrs.model.TemplateProcessingResult;

import java.util.List;
import java.util.UUID;

import static java.util.Collections.singletonList;

public class OkapiModulesClientHelper {

  private OkapiModulesClientHelper() {
    throw new IllegalStateException("Utility class");
  }

  public static NotifySendRequest buildNotifySendRequest(TemplateProcessingResult result, PatronNoticeEntity entity) {

    Message message = new Message()
      .withHeader(result.getResult().getHeader())
      .withBody(result.getResult().getBody())
      .withDeliveryChannel(entity.getDeliveryChannel())
      .withOutputFormat(entity.getOutputFormat());

    return new NotifySendRequest()
      .withNotificationId(UUID.randomUUID().toString())
      .withMessages(singletonList(message))
      .withRecipientUserId(entity.getRecipientId());
  }

  public static NotifySendRequest buildNotifySendRequest(List<Message> messages, Notification entity) {

    return new NotifySendRequest()
      .withNotificationId(UUID.randomUUID().toString())
      .withMessages(messages)
      .withRecipientUserId(entity.getRecipientId());
  }

  public static TemplateProcessingRequest buildTemplateProcessingRequest(PatronNoticeEntity entity) {

    return new TemplateProcessingRequest()
      .withTemplateId(entity.getTemplateId())
      .withOutputFormat(entity.getOutputFormat())
      .withLang(entity.getLang())
      .withContext(entity.getContext());
  }

  public static TemplateProcessingRequest buildTemplateProcessingRequest(Template template, Notification notification) {

    return new TemplateProcessingRequest()
      .withTemplateId(template.getTemplateId())
      .withOutputFormat(template.getOutputFormat())
      .withLang(notification.getLang())
      .withContext(notification.getContext());
  }
}
