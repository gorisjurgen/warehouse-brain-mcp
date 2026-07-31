package com.brutex.warehousebrain.notion;

/**
 * Error response from the Notion API, carrying Notion's own {@code code} and
 * {@code message} fields so callers can surface a readable explanation.
 */
public class NotionException extends RuntimeException {

    private final int status;
    private final String code;

    public NotionException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return "Notion API error " + status + " (" + code + "): " + super.getMessage();
    }
}
