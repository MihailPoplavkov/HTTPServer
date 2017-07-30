package net;

import lombok.Getter;
import lombok.Setter;
import lombok.val;

import java.util.HashMap;
import java.util.Map;

@Getter
class HttpRequest {
    @Setter
    private HttpMethod method;
    @Setter
    private String path;
    @Setter
    private String version;
    private Map<String, String> params = new HashMap<>();
    private Map<String, String> headers = new HashMap<>();
    private StringBuilder body = new StringBuilder();
    //current phase of reading
    @Setter
    private int phase;

    @SuppressWarnings("unused")
    String getBody() {
        return body.toString();
    }

    void addToBody(String s) {
        body.append(s);
    }

    void clear() {
        method = null;
        path = null;
        version = null;
        params.clear();
        headers.clear();
        body = new StringBuilder();
        phase = 0;
    }

    @Override
    public String toString() {
        val sb = new StringBuilder();
        sb.append(String.format("%s %s", method, path));
        if (!params.isEmpty()) {
            sb.append("?");
            params.forEach((k, v) -> sb.append(String.format("%s=%s&", k, v)));
            sb.deleteCharAt(sb.length() - 1);
        }
        if (version != null) {
            sb.append(String.format(" %s", version));
        }
        headers.forEach((k, v) -> sb.append(String.format("%n%s: %s", k, v)));
        sb.append("\n");
        if (body != null) {
            sb.append("\n");
            sb.append(body);
        }
        return sb.toString();
    }
}