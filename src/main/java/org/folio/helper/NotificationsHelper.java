package org.folio.helper;

import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.client.WebClient;
import org.apache.http.HttpStatus;
import org.folio.rest.RestVerticle;
import org.folio.rest.jaxrs.model.Context;
import org.folio.rest.jaxrs.model.EventEntity;
import org.folio.rest.jaxrs.model.EventEntityCollection;
import org.folio.rest.jaxrs.model.Message;
import org.folio.rest.jaxrs.model.Notification;
import org.folio.rest.jaxrs.model.NotifySendRequest;
import org.folio.rest.jaxrs.model.TemplateProcessingRequest;
import org.folio.rest.jaxrs.model.TemplateProcessingResult;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The {@code NotificationsHelper} class encapsulates business logic of notifications sending.
 */
public class NotificationsHelper {

  private static final Logger logger = LoggerFactory.getLogger(NotificationsHelper.class);

  private WebClient webClient;

  private static final String OKAPI_HEADER_URL = "x-okapi-url";
  private static final String EVENT_CONFIG_PATH = "/eventConfig";
  private static final String PROCESS_TEMPLATE_PATH = "/template-request";
  private static final String SEND_NOTIFICATION_PATH = "/message-delivery";
  private static final String CANNOT_FETCH_EVENT_ENTITY = "Cannot fetch event entity. ";
  private static final String CANNOT_SEND_NOTIFICATION = "Cannot send notification. ";
  private static final String CANNOT_PROCESS_TEMPLATE = "Cannot process template. ";

  public NotificationsHelper(WebClient webClient) {
    this.webClient = webClient;
  }

  /**
   * Perform all necessary modules calls in order to send notification and returns
   * the succeeded {@code Future} if notification sending flow was completed successfully,
   * otherwise returns failed {@code Future} with {@code Throwable} cause.
   *
   * @param entity       containing necessary info to send notification
   * @param okapiHeaders okapi http headers
   * @return the {@code Future} object
   */
  public Future<Void> postNotify(Notification entity, Map<String, String> okapiHeaders) {

    String okapiUrl = okapiHeaders.get(OKAPI_HEADER_URL);

    Future<EventEntity> eventConfigFuture = getEventConfig(okapiUrl, entity.getEventConfigName(), okapiHeaders);

    Future<CompositeFuture> templateEngineFuture = eventConfigFuture.compose(eventEntity -> {
      List<Future> templateProcessingResults = eventEntity.getTemplates().stream()
        .map(template -> processTemplate(okapiUrl, okapiHeaders, template.getTemplateId(),
          template.getDeliveryChannel(), template.getOutputFormat(), entity.getLang(), entity.getContext()))
        .collect(Collectors.toList());
      return CompositeFuture.all(templateProcessingResults);
    });

    return templateEngineFuture.compose(
      messages -> sendNotification(okapiUrl, okapiHeaders, messages, entity.getRecipientId()));
  }

  private Future<EventEntity> getEventConfig(String okapiUrl, String eventConfigName, Map<String, String> okapiHeaders) {
    Future<EventEntity> future = Future.future();
    webClient.getAbs(okapiUrl + EVENT_CONFIG_PATH)
      .addQueryParam("query", "name==" + eventConfigName)
      .putHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
      .putHeader(RestVerticle.OKAPI_HEADER_TENANT, okapiHeaders.get(RestVerticle.OKAPI_HEADER_TENANT))
      .putHeader(RestVerticle.OKAPI_HEADER_TOKEN, okapiHeaders.get(RestVerticle.OKAPI_HEADER_TOKEN))
      .send(resp -> {
        if (resp.succeeded()) {
          if (resp.result().statusCode() == HttpStatus.SC_OK) {
            EventEntityCollection eventConfig = resp.result().bodyAsJson(EventEntityCollection.class);
            if (eventConfig.getEventEntity().size() != 1) {
              logger.error(String.format(CANNOT_FETCH_EVENT_ENTITY + "Body: %s", resp.result().bodyAsString()));
              future.fail(new InternalServerErrorException());
              return;
            }
            future.complete(eventConfig.getEventEntity().get(0));
          } else if (resp.result().statusCode() == HttpStatus.SC_NOT_FOUND) {
            logger.error(CANNOT_FETCH_EVENT_ENTITY + resp.result().statusMessage());
            future.fail(new BadRequestException(resp.result().statusMessage()));
          } else {
            logger.error(CANNOT_FETCH_EVENT_ENTITY + resp.result().statusMessage());
            future.fail(new InternalServerErrorException());
          }
        } else {
          logger.error(CANNOT_FETCH_EVENT_ENTITY + resp.cause().getMessage());
          future.fail(resp.cause());
        }
      });
    return future;
  }

  private Future<Message> processTemplate(String okapiUrl, Map<String, String> okapiHeaders, String templateId,
                                          String deliveryChannel, String outputFormat, String lang, Context context) {
    Future<Message> future = Future.future();
    Message message = new Message();

    TemplateProcessingRequest templateProcessingRequest = new TemplateProcessingRequest();
    templateProcessingRequest.setTemplateId(templateId);
    templateProcessingRequest.setOutputFormat(outputFormat);
    templateProcessingRequest.setLang(lang);
    templateProcessingRequest.setContext(context);

    webClient.postAbs(okapiUrl + PROCESS_TEMPLATE_PATH)
      .putHeader(RestVerticle.OKAPI_HEADER_TENANT, okapiHeaders.get(RestVerticle.OKAPI_HEADER_TENANT))
      .putHeader(RestVerticle.OKAPI_HEADER_TOKEN, okapiHeaders.get(RestVerticle.OKAPI_HEADER_TOKEN))
      .putHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
      .putHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
      .sendJson(templateProcessingRequest, resp -> {
        if (resp.succeeded()) {
          if (resp.result().statusCode() == HttpStatus.SC_OK) {
            TemplateProcessingResult result = resp.result().bodyAsJson(TemplateProcessingResult.class);
            message.setDeliveryChannel(deliveryChannel);
            message.setHeader(result.getResult().getHeader());
            message.setBody(result.getResult().getBody());
            message.setOutputFormat(result.getMeta().getOutputFormat());
            future.complete(message);
          } else {
            logger.error(CANNOT_PROCESS_TEMPLATE + resp.result().statusMessage());
            future.fail(resp.result().statusMessage());
          }
        } else {
          logger.error(CANNOT_PROCESS_TEMPLATE + resp.cause().getMessage());
          future.fail(resp.cause());
        }
      });

    return future;
  }

  private Future<Void> sendNotification(String okapiUrl, Map<String, String> okapiHeaders,
                                        CompositeFuture messages, String recipientId) {
    Future<Void> future = Future.future();
    NotifySendRequest notifySendRequest = new NotifySendRequest();
    notifySendRequest.setNotificationId(UUID.randomUUID().toString());
    notifySendRequest.setMessages(messages.list());
    notifySendRequest.setRecipientUserId(recipientId);

    webClient.postAbs(okapiUrl + SEND_NOTIFICATION_PATH)
      .putHeader(RestVerticle.OKAPI_HEADER_TENANT, okapiHeaders.get(RestVerticle.OKAPI_HEADER_TENANT))
      .putHeader(RestVerticle.OKAPI_HEADER_TOKEN, okapiHeaders.get(RestVerticle.OKAPI_HEADER_TOKEN))
      .putHeader(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN)
      .sendJson(notifySendRequest, resp -> {
        if (resp.succeeded()) {
          if (resp.result().statusCode() == HttpStatus.SC_NO_CONTENT) {
            future.complete();
          } else if (resp.result().statusCode() == HttpStatus.SC_BAD_REQUEST) {
            logger.error(CANNOT_SEND_NOTIFICATION + resp.result().statusMessage());
            future.fail(new BadRequestException(resp.result().statusMessage()));
          } else {
            logger.error(CANNOT_SEND_NOTIFICATION + resp.result().statusMessage());
            future.fail(new InternalServerErrorException());
          }
        } else {
          logger.error(CANNOT_SEND_NOTIFICATION + resp.cause().getMessage());
          future.fail(resp.cause());
        }
      });
    return future;
  }
}
