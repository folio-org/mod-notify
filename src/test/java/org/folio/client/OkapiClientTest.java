package org.folio.client;

import static org.folio.okapi.common.XOkapiHeaders.REQUEST_ID;
import static org.folio.okapi.common.XOkapiHeaders.TENANT;
import static org.folio.okapi.common.XOkapiHeaders.TOKEN;
import static org.folio.okapi.common.XOkapiHeaders.URL;
import static org.junit.Assert.assertNotNull;

import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;

public class OkapiClientTest {

  private static final Map<String, String> okapiHeaders = Map.of(
    REQUEST_ID, "okapi-request-id",
    TENANT, "okapi-tenant",
    TOKEN, "okapi-token",
    URL, "http:localhost"
  );
  private OkapiClient client;

  @Before
  public void setUp() {
    client = new OkapiClient(Vertx.vertx(), okapiHeaders);
  }

  @Test
  public void shouldPassHeaderParamsWhenGET() {
    MultiMap headers = client.getAbs("get-abc").headers();

    assertNotNull(headers.get(REQUEST_ID));
    assertNotNull(headers.get(TENANT));
    assertNotNull(headers.get(TOKEN));
  }

  @Test
  public void shouldPassHeaderParamsWhenPOST() {
    MultiMap headers = client.postAbs("post-abc").headers();

    assertNotNull(headers.get(REQUEST_ID));
    assertNotNull(headers.get(TENANT));
    assertNotNull(headers.get(TOKEN));
  }
}