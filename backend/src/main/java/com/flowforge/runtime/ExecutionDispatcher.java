package com.flowforge.runtime;

import java.util.UUID;

public interface ExecutionDispatcher {
    void dispatch(UUID executionId);
}
