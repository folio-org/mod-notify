package org.folio.util;

import java.util.Optional;

import org.folio.rest.jaxrs.model.PatronNoticeEntity;

/**
 * Validates a patron notice request before it is dispatched to mod-template-engine
 * and mod-sender.
 */
public class PatronNoticeValidator {

  private static final String SMS_DELIVERY_CHANNEL = "sms";
  private static final String TEXT_PLAIN = "text/plain";

  private PatronNoticeValidator() {}

  /**
   * Checks that a notice for the {@code sms} delivery channel uses the
   * {@code text/plain} output format. mod-sender routes {@code sms} to
   * {@code TextNotifyDeliveryChannel}, which forwards the rendered body verbatim, so
   * an HTML body would reach the patron as raw markup.
   *
   * @param entity the request to validate; may be null
   * @return the error message when the request is invalid, otherwise empty
   */
  public static Optional<String> validate(PatronNoticeEntity entity) {
    if (entity == null) {
      return Optional.empty();
    }

    var outputFormat = entity.getOutputFormat();

    if (!SMS_DELIVERY_CHANNEL.equalsIgnoreCase(entity.getDeliveryChannel())
      || TEXT_PLAIN.equals(outputFormat)) {

      return Optional.empty();
    }

    return Optional.of("SMS notifications must use outputFormat '%s'. Received: '%s'"
      .formatted(TEXT_PLAIN, outputFormat));
  }
}
