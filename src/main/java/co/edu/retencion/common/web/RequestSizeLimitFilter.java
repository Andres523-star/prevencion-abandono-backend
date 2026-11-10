package co.edu.retencion.common.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Límite de tamaño del cuerpo de las peticiones que no son archivos (JSON). Tomcat no limita el cuerpo de un
 * POST JSON; sin esto, una petición de cientos de MB llega hasta Jackson y agota memoria (ASVS V12/V13).
 * Las subidas multipart tienen sus propios límites ({@code spring.servlet.multipart}).
 *
 * <p>Con {@code Content-Length} se rechaza antes de leer (413). Sin él (transferencia por partes) se cuenta
 * lo leído y se corta al pasar el límite.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;

    public RequestSizeLimitFilter(@Value("${app.web.max-body-size:1MB}") DataSize maxBody) {
        this.maxBytes = maxBody.toBytes();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String type = request.getContentType();
        if (type != null && type.toLowerCase(java.util.Locale.ROOT).startsWith(MediaType.MULTIPART_FORM_DATA_VALUE)) {
            chain.doFilter(request, response);
            return;
        }
        long length = request.getContentLengthLong();
        if (length > maxBytes) {
            reject(response);
            return;
        }
        chain.doFilter(length >= 0 ? request : new LimitedRequest(request, maxBytes), response);
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"type\":\"urn:retencion:problem:payload_too_large\",\"title\":\"La petición es demasiado grande\","
                + "\"status\":413,\"code\":\"payload_too_large\"}");
    }

    /** Cuerpo sin longitud declarada: se corta al superar el límite. */
    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long max;

        LimitedRequest(HttpServletRequest request, long max) {
            super(request);
            this.max = max;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream in = super.getInputStream();
            return new ServletInputStream() {
                private long read;

                @Override
                public int read() throws IOException {
                    int b = in.read();
                    if (b >= 0 && ++read > max) {
                        throw new PayloadTooLargeException();
                    }
                    return b;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int n = in.read(buffer, offset, length);
                    if (n > 0 && (read += n) > max) {
                        throw new PayloadTooLargeException();
                    }
                    return n;
                }

                @Override
                public boolean isFinished() {
                    return in.isFinished();
                }

                @Override
                public boolean isReady() {
                    return in.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    in.setReadListener(listener);
                }
            };
        }
    }

    /** Se traduce a 413 en el manejador global de errores. */
    public static final class PayloadTooLargeException extends IOException {

        private static final long serialVersionUID = 1L;

        public PayloadTooLargeException() {
            super("Cuerpo de la petición demasiado grande");
        }
    }
}
