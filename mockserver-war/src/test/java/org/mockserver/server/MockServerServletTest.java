package org.mockserver.server;

import io.netty.channel.ChannelHandlerContext;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockserver.client.serialization.ExpectationSerializer;
import org.mockserver.client.serialization.HttpRequestSerializer;
import org.mockserver.client.serialization.PortBindingSerializer;
import org.mockserver.log.model.ExpectationMatchLogEntry;
import org.mockserver.log.model.RequestLogEntry;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpStateHandler;
import org.mockserver.mock.action.ActionHandler;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Collections;

import static org.apache.commons.codec.Charsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.nullValue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.PortBinding.portBinding;

/**
 * @author jamesdbloom
 */
public class MockServerServletTest {

    private HttpRequestSerializer httpRequestSerializer = new HttpRequestSerializer();
    private ExpectationSerializer expectationSerializer = new ExpectationSerializer();
    private PortBindingSerializer portBindingSerializer = new PortBindingSerializer();

    private HttpStateHandler httpStateHandler;
    private ActionHandler mockActionHandler;

    @InjectMocks
    private MockServerServlet mockServerServlet;

    private MockHttpServletResponse response;

    @Before
    public void setupFixture() {
        mockActionHandler = mock(ActionHandler.class);
        httpStateHandler = spy(new HttpStateHandler());
        response = new MockHttpServletResponse();
        mockServerServlet = new MockServerServlet();


        initMocks(this);
    }

    private MockHttpServletRequest buildHttpServletRequest(String method, String requestURI, String body) {
        MockHttpServletRequest expectationRetrieveRequestsRequest = new MockHttpServletRequest(method, requestURI);
        expectationRetrieveRequestsRequest.setContent(body.getBytes());
        return expectationRetrieveRequestsRequest;
    }

    private void assertResponse(MockHttpServletResponse response, int responseStatusCode, String responseBody) {
        assertThat(response.getStatus(), is(responseStatusCode));
        assertThat(new String(response.getContentAsByteArray(), UTF_8), is(responseBody));
    }

    @Test
    public void shouldRetrieveRequests() {
        // given
        MockHttpServletRequest expectationRetrieveRequestsRequest = buildHttpServletRequest(
                "PUT",
                "/retrieve",
                httpRequestSerializer.serialize(request("request_one"))
        );
        httpStateHandler.log(new RequestLogEntry(request("request_one")));

        // when
        mockServerServlet.service(expectationRetrieveRequestsRequest, response);

        // then
        assertResponse(response, 200, httpRequestSerializer.serialize(Collections.singletonList(
                request("request_one")
        )));
    }

    @Test
    public void shouldClear() {
        // given
        httpStateHandler.add(new Expectation(request("request_one")).thenRespond(response("response_one")));
        httpStateHandler.log(new RequestLogEntry(request("request_one")));
        MockHttpServletRequest clearRequest = buildHttpServletRequest(
                "PUT",
                "/clear",
                httpRequestSerializer.serialize(request("request_one"))
        );

        // when
        mockServerServlet.service(clearRequest, response);

        // then
        assertResponse(response, 200, "");
        assertThat(httpStateHandler.firstMatchingExpectation(request("request_one")), is(nullValue()));
        assertThat(httpStateHandler.retrieve(request("/retrieve")
                .withMethod("PUT")
                .withBody(
                        httpRequestSerializer.serialize(request("request_one"))
                )), is(""));
    }

    @Test
    public void shouldReturnStatus() {
        // given
        MockHttpServletRequest statusRequest = buildHttpServletRequest(
                "PUT",
                "/status",
                ""
        );

        // when
        mockServerServlet.service(statusRequest, response);

        // then
        assertResponse(response, 200, portBindingSerializer.serialize(
                portBinding(80)
        ));
    }

    @Test
    public void shouldBindNewPorts() {
        // given
        MockHttpServletRequest statusRequest = buildHttpServletRequest(
                "PUT",
                "/bind", portBindingSerializer.serialize(
                        portBinding(1080, 1090)
                ));

        // when
        mockServerServlet.service(statusRequest, response);

        // then
        assertResponse(response, 501, "");
    }

    @Test
    public void shouldStop() throws InterruptedException {
        // given
        MockHttpServletRequest statusRequest = buildHttpServletRequest(
                "PUT",
                "/stop",
                ""
        );

        // when
        mockServerServlet.service(statusRequest, response);

        // then
        assertResponse(response, 501, "");
    }

    @Test
    public void shouldRetrieveRecordedExpectations() {
        // given
        Expectation expectationOne = new Expectation(request("request_one")).thenRespond(response("response_one"));
        httpStateHandler.log(new ExpectationMatchLogEntry(
                request("request_one"),
                expectationOne
        ));
        MockHttpServletRequest expectationRetrieveExpectationsRequest = buildHttpServletRequest(
                "PUT",
                "/retrieve",
                httpRequestSerializer.serialize(request("request_one"))
        );
        expectationRetrieveExpectationsRequest.setQueryString("type=" + HttpStateHandler.RetrieveType.RECORDED_EXPECTATIONS.name());

        // when
        mockServerServlet.service(expectationRetrieveExpectationsRequest, response);

        // then
        assertResponse(response, 200, expectationSerializer.serialize(Collections.singletonList(
                expectationOne
        )));
    }

    @Test
    public void shouldAddExpectation() {
        // given
        Expectation expectationOne = new Expectation(request("request_one")).thenRespond(response("response_one"));
        MockHttpServletRequest request = buildHttpServletRequest(
                "PUT",
                "/expectation",
                expectationSerializer.serialize(expectationOne)
        );

        // when
        mockServerServlet.service(request, response);

        // then
        assertResponse(response, 201, "");
        assertThat(httpStateHandler.firstMatchingExpectation(request("request_one")), is(expectationOne));
    }

    @Test
    public void shouldRetrieveActiveExpectations() {
        // given
        Expectation expectationOne = new Expectation(request("request_one")).thenRespond(response("response_one"));
        httpStateHandler.add(expectationOne);
        MockHttpServletRequest expectationRetrieveExpectationsRequest = buildHttpServletRequest(
                "PUT",
                "/retrieve",
                httpRequestSerializer.serialize(request("request_one"))
        );
        expectationRetrieveExpectationsRequest.setQueryString("type=" + HttpStateHandler.RetrieveType.ACTIVE_EXPECTATIONS.name());

        // when
        mockServerServlet.service(expectationRetrieveExpectationsRequest, response);

        // then
        assertResponse(response, 200, expectationSerializer.serialize(Collections.singletonList(
                expectationOne
        )));
    }

    @Test
    public void shouldUseActionHandlerToHandleNonAPIRequests() {
        // given
        MockHttpServletRequest request = buildHttpServletRequest(
                "GET",
                "request_one",
                ""
        );

        // when
        mockServerServlet.service(request, response);

        // then
        verify(mockActionHandler).processAction(
                eq(
                        request("request_one")
                                .withMethod("GET")
                                .withSecure(false)
                ),
                any(ServletResponseWriter.class),
                isNull(ChannelHandlerContext.class)
        );
    }

}
