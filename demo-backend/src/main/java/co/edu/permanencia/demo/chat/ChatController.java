package co.edu.permanencia.demo.chat;

import co.edu.permanencia.demo.auth.Usuario;
import co.edu.permanencia.demo.common.ApiException;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Conversaciones y mensajes del chat interno. */
@RestController
@RequestMapping("/api/chats")
public class ChatController {

    private final ChatService chat;
    private final EnlacesAdjuntos enlaces;

    public ChatController(ChatService chat, EnlacesAdjuntos enlaces) {
        this.chat = chat;
        this.enlaces = enlaces;
    }

    @GetMapping
    public List<Conversacion> conversaciones(
            @RequestAttribute(name = "usuario", required = false) Usuario usuario) {
        return chat.conversaciones(usuario);
    }

    @GetMapping("/{estudianteId}/mensajes")
    public List<Mensaje> mensajes(@PathVariable int estudianteId,
            @RequestAttribute(name = "usuario", required = false) Usuario usuario) {
        List<Mensaje> lista = chat.mensajes(usuario, estudianteId);
        lista.forEach(this::firmar);
        return lista;
    }

    @PostMapping("/{estudianteId}/mensajes")
    public Mensaje enviar(@PathVariable int estudianteId, @RequestBody MensajeRequest request,
            @RequestAttribute(name = "usuario", required = false) Usuario usuario) {
        return firmar(chat.enviar(usuario, estudianteId, request));
    }

    /**
     * Descarga de un adjunto con enlace firmado. La ruta no exige sesión (un {@code <audio>} no
     * envía cabeceras), pero sin una firma válida y vigente responde 403.
     */
    @GetMapping("/adjuntos/{archivoId}")
    public ResponseEntity<byte[]> adjunto(@PathVariable String archivoId,
                                          @RequestParam(name = "firma", required = false) String firma) {
        if (!enlaces.valida(archivoId, firma)) {
            throw ApiException.forbidden("El enlace del archivo no es válido o ya venció. Recarga la conversación.");
        }
        Adjunto adjunto = chat.descargar(archivoId);
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(adjunto.mime());
        } catch (Exception ex) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        // El audio y el PDF se pueden ver en el navegador; el Word siempre se descarga.
        String disposicion = ValidadorAdjuntos.DOCX.equals(adjunto.mime()) ? "attachment" : "inline";
        return ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.builder(disposicion)
                        .filename(adjunto.nombre(), StandardCharsets.UTF_8).build().toString())
                // El tipo se validó por contenido al subirlo y la respuesta lleva nosniff
                // (CabecerasSeguridadFilter): el navegador no lo interpreta como otra cosa.
                .body(adjunto.bytes());
    }

    private Mensaje firmar(Mensaje mensaje) {
        if (mensaje.getArchivoId() != null) {
            mensaje.setArchivoFirma(enlaces.firmar(mensaje.getArchivoId()));
        }
        return mensaje;
    }
}
