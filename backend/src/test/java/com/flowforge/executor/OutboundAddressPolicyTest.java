package com.flowforge.executor;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

class OutboundAddressPolicyTest {
    private final OutboundAddressPolicy policy = new OutboundAddressPolicy();

    @Test
    void allowsGloballyRoutableAddressesAndBlocksPrivateAndReservedRanges() throws Exception {
        assertThat(isPublic("8.8.8.8")).isTrue();
        assertThat(isPublic("2001:4860:4860::8888")).isTrue();
        assertThat(isPublic("10.0.0.1")).isFalse();
        assertThat(isPublic("100.64.0.1")).isFalse();
        assertThat(isPublic("169.254.169.254")).isFalse();
        assertThat(isPublic("192.0.2.1")).isFalse();
        assertThat(isPublic("2001:db8::1")).isFalse();
        assertThat(isPublic("2002:0808:0808::1")).isFalse();
    }

    private boolean isPublic(String address) throws UnknownHostException {
        return policy.isPublic(InetAddress.getByName(address));
    }
}
