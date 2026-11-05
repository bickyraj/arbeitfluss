package com.flowforge.executor;

import java.net.UnknownHostException;

final class SSRFBlockedException extends UnknownHostException {
    SSRFBlockedException() {
        super("Destination is not publicly routable");
    }
}
