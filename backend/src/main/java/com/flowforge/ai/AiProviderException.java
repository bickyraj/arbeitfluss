package com.flowforge.ai;

/** Safe, normalized provider failure. Never retain a provider response or credential. */
public final class AiProviderException extends RuntimeException {
    private final String code;

    public AiProviderException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
