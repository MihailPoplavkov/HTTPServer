package net;

import lombok.Data;

import java.util.Map;

@Data
class HttpRequest {
    private HttpMethod method;
    private String path;
    private Map<String, String> params;
    private Map<String, String> headers;
    private StringBuilder body;
    //current phase of reading
    private int phase;

    String getBody() {
        return body.toString();
    }

    void addToBody(String s) {
        body.append(s);
    }

    void clear() {
        method = null;
        path = null;
        params = null;
        headers = null;
        body = new StringBuilder();
        phase = 0;
    }
}