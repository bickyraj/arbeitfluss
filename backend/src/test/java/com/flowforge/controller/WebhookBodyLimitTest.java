package com.flowforge.controller;

import com.flowforge.entity.WebhookRegistration;
import com.flowforge.entity.Workflow;
import com.flowforge.service.ExecutionService;
import com.flowforge.service.WebhookIngressSettings;
import com.flowforge.service.WebhookRateLimiter;
import com.flowforge.service.WebhookRegistrationService;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WebhookBodyLimitTest {
    private static final int LIMIT = 1_048_576;
    private static final String ID = "a".repeat(43);
    private WebhookController controller;
    private ExecutionService executions;

    @BeforeEach
    void setUp() {
        var registrations = mock(WebhookRegistrationService.class);
        var registration = mock(WebhookRegistration.class);
        when(registrations.resolveEnabledWebhook(ID)).thenReturn(registration);
        when(registration.getWorkflow()).thenReturn(mock(Workflow.class));
        executions = mock(ExecutionService.class);
        controller = new WebhookController(registrations, new WebhookRateLimiter(), executions, new WebhookIngressSettings(true));
    }

    @Test
    void declaredOversizeIsRejectedBeforeReadingStream() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/webhooks/"+ID) {
            @Override public long getContentLengthLong() { return LIMIT + 1L; }
            @Override public ServletInputStream getInputStream() { throw new AssertionError("Oversized stream must not be read"); }
        };
        assertThat(controller.receive(ID, request).getStatusCode().value()).isEqualTo(413);
        verifyNoInteractions(executions);
    }

    @Test
    void unknownLengthStreamReadsAtMostLimitPlusOneBytes() throws Exception {
        CountingStream stream = new CountingStream(LIMIT + 10_000);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/webhooks/"+ID) {
            @Override public long getContentLengthLong() { return -1; }
            @Override public ServletInputStream getInputStream() { return stream; }
        };
        request.setContentType("application/json");
        assertThat(controller.receive(ID, request).getStatusCode().value()).isEqualTo(413);
        assertThat(stream.position).isEqualTo(LIMIT + 1);
        verifyNoInteractions(executions);
    }

    @Test
    void wrappedInputOverLimitIsRejectedEvenWhenRawBodyFits() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/webhooks/"+ID);
        request.setContentType("text/plain");
        request.setContent(new byte[LIMIT]);
        assertThat(controller.receive(ID, request).getStatusCode().value()).isEqualTo(413);
        verifyNoInteractions(executions);
    }

    @Test
    void streamFailureReturnsOpaque400WithoutExecution() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/webhooks/"+ID) {
            @Override public ServletInputStream getInputStream() throws IllegalStateException {
                return new CountingStream(1) {
                    @Override public int read(byte[] b, int off, int len) throws IOException { throw new IOException("private-test-diagnostic"); }
                    @Override public int read() throws IOException { throw new IOException("private-test-diagnostic"); }
                };
            }
        };
        var response = controller.receive(ID, request);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNull();
        verifyNoInteractions(executions);
    }

    private static class CountingStream extends ServletInputStream {
        private final int total;
        private int position;
        CountingStream(int total) { this.total = total; }
        @Override public boolean isFinished() { return position >= total; }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(ReadListener listener) {}
        @Override public int read() throws IOException {
            if (position >= total) return -1;
            position++;
            return 'x';
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            int count = Math.min(len, total - position);
            if (count == 0) return -1;
            Arrays.fill(b, off, off+count, (byte)'x');
            position += count;
            return count;
        }
    }
}
