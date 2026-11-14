package co.edu.permanencia.demo.chat;

import co.edu.permanencia.demo.auth.Usuario;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Conversaciones y mensajes del chat interno. */
@RestController
@RequestMapping("/api/chats")
public class ChatController {

    private final ChatService chat;

    public ChatController(ChatService chat) {
        this.chat = chat;
    }

    @GetMapping
    public List<Conversacion> conversaciones(
            @RequestAttribute(name = "usuario", required = false) Usuario usuario) {
        return chat.conversaciones(usuario);
    }

    @GetMapping("/{estudianteId}/mensajes")
    public List<Mensaje> mensajes(@PathVariable int estudianteId,
            @RequestAttribute(name = "usuario", required = false) Usuario usuario) {
        return chat.mensajes(usuario, estudianteId);
    }

    @PostMapping("/{estudianteId}/mensajes")
    public Mensaje enviar(@PathVariable int estudianteId, @RequestBody MensajeRequest request,
            @RequestAttribute(name = "usuario", required = false) Usuario usuario) {
        return chat.enviar(usuario, estudianteId, request);
    }

    @GetMapping("/adjuntos/{archivoId}")
    public ResponseEntity<byte[]> adjunto(@PathVariable String archivoId) {
        Adjunto adjunto = chat.descargar(archivoId);
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(adjunto.mime());
        } catch (Exception ex) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        String nombre = adjunto.nombre().replace("\"", "");
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + nombre + "\"")
                .body(adjunto.bytes());
    }
}
