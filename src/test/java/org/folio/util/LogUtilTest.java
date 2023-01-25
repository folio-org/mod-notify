package org.folio.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.folio.rest.jaxrs.model.Context;
import org.folio.rest.jaxrs.model.PatronNoticeEntity;
import org.junit.Test;

import io.vertx.core.json.JsonObject;

public class LogUtilTest {

  @Test
  public void asJsonShouldReturnNullIfCallWithNull() {
    assertNull(LogUtil.asJson(null));
  }

  @Test
  public void asJsonShouldReturnStringValueOfJson() {
    JsonObject json = new JsonObject()
      .put("key", "value");
    assertEquals("{\"key\":\"value\"}", LogUtil.asJson(json));
  }

  @Test
  public void asJsonShouldReturnStringValueOfNumbers() {
    assertEquals("1", LogUtil.asJson(1));
    assertEquals("1.123", LogUtil.asJson(1.123));
  }

  @Test
  public void asJsonShouldReturnStringValueOfString() {
    assertEquals("string", LogUtil.asJson("string"));
  }

  @Test
  public void asJsonShouldReturnNullIfCallWithNullAndSizeValue() {
    assertNull(LogUtil.asJson(null, 10));
  }

  @Test
  public void asJsonShouldReturnStringRepresentationOfListOfIntegers() {
    List<Integer> list = new ArrayList<>();
    list.add(1);
    list.add(2);
    list.add(3);
    assertEquals("list(size: 3, elements: [1, 2, 3])", LogUtil.asJson(list));
  }

  @Test
  public void asJsonShouldReturnStringRepresentationOfListOfObjects() {
    List<Object> list = new ArrayList<>();
    list.add(1);
    list.add(2.2);
    list.add("string");
    assertEquals("list(size: 3, elements: [1, 2.2, string])", LogUtil.asJson(list));
  }

  @Test
  public void patronNoticeAsStringShouldReturnJsonStringWithNoContext() {
    PatronNoticeEntity patronNoticeEntity = new PatronNoticeEntity()
      .withRecipientId("recipientId")
      .withDeliveryChannel("email")
      .withTemplateId("templateId")
      .withOutputFormat("text/html")
      .withLang("en")
      .withContext(new Context().withAdditionalProperty("test", "test"));

    assertEquals("{\"recipientId\":\"recipientId\",\"deliveryChannel\":\"email\"," +
      "\"templateId\":\"templateId\",\"outputFormat\":\"text/html\",\"lang\":\"en\"}",
      LogUtil.patronNoticeAsString(patronNoticeEntity));
  }

}