package co.edu.retencion.chat.api;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import co.edu.retencion.audit.Audited;
import co.edu.retencion.audit.SensitiveRead;
import co.edu.retencion.chat.application.ChatAttachmentService;
import co.edu.retencion.chat.application.ChatService;
import co.edu.retencion.chat.application.ChatViews.AttachmentView;
import co.edu.retencion.chat.application.ChatViews.ConversationView;
import co.edu.retencion.chat.application.ChatViews.MessageView;
import co.edu.retencion.chat.application.ChatViews.ReportView;
import co.edu.retencion.chat.application.StudentContextService;
import co.edu.retencion.chat.domain.ChatMessage;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.common.web.CursorPage;

/**
 * Chat docente-estudiante por REST (historial, adjuntos, lectura, borrado, reportes y vista
 * dividida). El envío también existe por STOMP ({@code /app/chat.send}).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Chat")
public class ChatController {

    private final ChatService chat;
    private final ChatAttachmentService attachments;
    private final StudentContextService context;

    public ChatController(ChatService chat, ChatAttachmentService attachments, StudentContextService context) {
        this.chat = chat;
        this.attachments = attachments;
        this.context = context;
    }

    public record OpenRequest(@NotNull UUID studentId, UUID groupId) {
    }

    public record SendRequest(@NotNull ChatMessage.Type type, @Size(max = 8000) String text, UUID attachmentId) {
        @Override
        public String toString() {
            return "SendRequest[type=" + type + "]";
        }
    }

    public record ReportRequest(@NotBlank @Size(max = 1000) String reason) {
        @Override
        public String toString() {
            return "ReportRequest[***]";
        }
    }

    @PostMapping("/chats")
    @PreAuthorize("hasRole('DOCENTE')")
    @Audited(action = "CHAT_OPEN", entity = "Student", entityId = "#request.studentId")
    @Operation(summary = "Abrir chat con un estudiante de mis grupos (devuelve el existente o lo crea)")
    public ConversationView open(@Valid @RequestBody OpenRequest request) {
        return chat.open(CurrentUser.require(), request.studentId(), request.groupId());
    }

    @GetMapping("/chats")
    @PreAuthorize("hasAnyRole('DOCENTE','ESTUDIANTE')")
    @Operation(summary = "Mis conversaciones con no leídos")
    public List<ConversationView> mine() {
        return chat.mine(CurrentUser.require());
    }

    @GetMapping("/chats/{id}/messages")
    @Operation(summary = "Historial paginado (keyset, del más reciente al más antiguo)")
    public CursorPage<MessageView> history(@PathVariable UUID id, @RequestParam(required = false) UUID before,
                                           @RequestParam(required = false) Integer size) {
        return chat.history(CurrentUser.require(), id, before, size);
    }

    @PostMapping("/chats/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    @Audited(action = "CHAT_MESSAGE_SEND", entity = "Conversation", entityId = "#id")
    @Operation(summary = "Envía un mensaje (alternativa REST a /app/chat.send)")
    public MessageView send(@PathVariable UUID id, @Valid @RequestBody SendRequest request) {
        return chat.send(CurrentUser.require(), id, request.type(), request.text(), request.attachmentId());
    }

    @PostMapping("/chats/{id}/read")
    @Audited(action = "CHAT_READ_RECEIPT", entity = "Conversation", entityId = "#id")
    @Operation(summary = "Confirmación de lectura")
    public ResponseEntity<Void> read(@PathVariable UUID id) {
        chat.markRead(CurrentUser.require(), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/chats/{id}/messages/{messageId}")
    @Audited(action = "CHAT_MESSAGE_DELETE", entity = "ChatMessage", entityId = "#messageId")
    @Operation(summary = "Elimina un mensaje propio (borrado lógico)")
    public MessageView delete(@PathVariable UUID id, @PathVariable UUID messageId) {
        return chat.delete(CurrentUser.require(), id, messageId);
    }

    @PostMapping("/chats/{id}/messages/{messageId}/report")
    @ResponseStatus(HttpStatus.CREATED)
    @Audited(action = "CHAT_MESSAGE_REPORT", entity = "ChatMessage", entityId = "#messageId")
    @Operation(summary = "Reporta un mensaje inapropiado")
    public ReportView report(@PathVariable UUID id, @PathVariable UUID messageId,
                             @Valid @RequestBody ReportRequest request) {
        return chat.report(CurrentUser.require(), id, messageId, request.reason());
    }

    @PostMapping(value = "/chats/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Audited(action = "CHAT_ATTACHMENT_UPLOAD", entity = "Conversation", entityId = "#id")
    @Operation(summary = "Sube un adjunto antes de enviarlo (PDF, .docx o audio); devuelve attachmentId")
    public AttachmentView upload(@PathVariable UUID id, @RequestPart("file") MultipartFile file,
                                 @RequestParam(required = false) Integer durationSeconds) throws IOException {
        return attachments.upload(CurrentUser.require(), id, file.getOriginalFilename(), file.getBytes(), durationSeconds);
    }

    @GetMapping("/chat-attachments/{id}/download-url")
    @SensitiveRead(entity = "ChatAttachment", entityId = "#id", requireReason = false)
    @Operation(summary = "URL prefirmada de 5 minutos (solo participantes)")
    public ChatAttachmentService.DownloadView download(@PathVariable UUID id) {
        return attachments.downloadUrl(CurrentUser.require(), id);
    }

    @GetMapping("/chats/{id}/student-context")
    @PreAuthorize("hasAnyRole('DOCENTE','ADMIN')")
    @SensitiveRead(entity = "StudentIdentity", entityId = "#id", requireReason = false)
    @Operation(summary = "Perfil del estudiante y series de riesgo para la vista dividida del chat")
    public StudentContextService.StudentContext studentContext(@PathVariable UUID id) {
        return context.context(CurrentUser.require(), id);
    }

    @GetMapping("/admin/chats/{id}/messages")
    @PreAuthorize("hasRole('ADMIN')")
    @SensitiveRead(entity = "ChatMessage", entityId = "#id")
    @Operation(summary = "Lectura de ADMIN (requiere X-Access-Reason; queda en la auditoría)")
    public CursorPage<MessageView> adminHistory(@PathVariable UUID id, @RequestParam(required = false) UUID before,
                                                @RequestParam(required = false) Integer size) {
        return chat.adminHistory(id, before, size);
    }

    @GetMapping("/admin/chat-reports")
    @PreAuthorize("hasRole('ADMIN')")
    @SensitiveRead(entity = "ChatMessageReport", requireReason = false)
    @Operation(summary = "Reportes de mensajes abiertos")
    public List<ReportView> reports() {
        return chat.openReports();
    }
}
