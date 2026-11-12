package co.edu.permanencia.demo.common;

import java.time.Instant;

public class ProblemDetails {
    private final String title;
    private final int status;
    private final String detail;
    private final String timestamp;

    public ProblemDetails(String title, int status, String detail) {
        this.title = title;
        this.status = status;
        this.detail = detail;
        this.timestamp = Instant.now().toString();
    }

    public String getTitle() { return title; }
    public int getStatus() { return status; }
    public String getDetail() { return detail; }
    public String getTimestamp() { return timestamp; }
}
