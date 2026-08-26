package org.folio.util;

import static org.junit.Assert.assertTrue;

import java.util.Optional;

import org.folio.rest.jaxrs.model.PatronNoticeEntity;
import org.junit.Test;

public class PatronNoticeValidatorTest {

  private static final String TEXT_PLAIN = "text/plain";
  private static final String TEXT_HTML = "text/html";

  @Test
  public void smsWithPlainTextIsValid() {
    assertTrue(validate("sms", TEXT_PLAIN).isEmpty());
  }

  @Test
  public void smsWithHtmlIsRejected() {
    var error = validate("sms", TEXT_HTML);

    assertTrue(error.isPresent());
    assertTrue(error.get().contains(TEXT_PLAIN));
    assertTrue(error.get().contains(TEXT_HTML));
  }

  @Test
  public void smsChannelMatchIsCaseInsensitive() {
    assertTrue(validate("SMS", TEXT_HTML).isPresent());
  }

  @Test
  public void outputFormatMatchIsCaseSensitive() {
    assertTrue(validate("sms", "TEXT/PLAIN").isPresent());
  }

  @Test
  public void emailWithHtmlIsValid() {
    assertTrue(validate("email", TEXT_HTML).isEmpty());
  }

  @Test
  public void mailWithHtmlIsValid() {
    assertTrue(validate("mail", TEXT_HTML).isEmpty());
  }

  @Test
  public void unrecognizedChannelWithHtmlIsValid() {
    assertTrue(validate("carrier-pigeon", TEXT_HTML).isEmpty());
  }

  @Test
  public void smsWithDefaultOutputFormatIsRejected() {
    var entity = new PatronNoticeEntity().withDeliveryChannel("sms");

    var error = PatronNoticeValidator.validate(entity);

    assertTrue(error.isPresent());
    assertTrue(error.get().contains(TEXT_HTML));
  }

  @Test
  public void nullDeliveryChannelIsValid() {
    assertTrue(validate(null, TEXT_HTML).isEmpty());
  }

  @Test
  public void smsWithNullOutputFormatIsRejected() {
    var error = validate("sms", null);

    assertTrue(error.isPresent());
    assertTrue(error.get().contains(TEXT_PLAIN));
  }

  @Test
  public void nullEntityIsValid() {
    assertTrue(PatronNoticeValidator.validate(null).isEmpty());
  }

  private Optional<String> validate(String deliveryChannel, String outputFormat) {
    return PatronNoticeValidator.validate(new PatronNoticeEntity()
      .withDeliveryChannel(deliveryChannel)
      .withOutputFormat(outputFormat));
  }
}
