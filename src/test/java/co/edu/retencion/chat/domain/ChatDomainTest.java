package co.edu.retencion.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.support.OfficeFiles;

class ChatDomainTest {

    static final Instant NOW = Instant.parse("2026-11-05T15:00:00Z");
    static final long MAX = 10L * 1024 * 1024;

    @Test
    void allowsPdfDocxAndAudioByMagicBytes() {
        assertThat(ChatFileTypes.requireAllowed("tarea.pdf", OfficeFiles.PDF, MAX)).isEqualTo(ChatFileTypes.Kind.PDF);
        assertThat(ChatFileTypes.requireAllowed("ensayo.docx", OfficeFiles.docx(), MAX)).isEqualTo(ChatFileTypes.Kind.DOCX);
        assertThat(ChatFileTypes.requireAllowed("nota.ogg", OfficeFiles.OGG, MAX).messageType())
                .isEqualTo(ChatMessage.Type.AUDIO);
        assertThat(ChatFileTypes.requireAllowed("nota.mp3", OfficeFiles.MP3, MAX)).isEqualTo(ChatFileTypes.Kind.MP3);
        byte[] webm = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 1, 2};
        assertThat(ChatFileTypes.requireAllowed("voz.webm", webm, MAX)).isEqualTo(ChatFileTypes.Kind.WEBM);
        byte[] m4a = {0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '};
        assertThat(ChatFileTypes.requireAllowed("voz.m4a", m4a, MAX)).isEqualTo(ChatFileTypes.Kind.M4A);
    }

    @Test
    void rejectsDocMacrosFakeExtensionsAndUnknownTypes() {
        assertThatThrownBy(() -> ChatFileTypes.requireAllowed("viejo.doc", OfficeFiles.OLE_DOC, MAX))
                .isInstanceOf(DomainException.class).hasMessageContaining(".doc");
        assertThatThrownBy(() -> ChatFileTypes.requireAllowed("renombrado.docx", OfficeFiles.OLE_DOC, MAX))
                .hasMessageContaining(".doc");
        assertThatThrownBy(() -> ChatFileTypes.requireAllowed("macro.docm", OfficeFiles.docx(), MAX))
                .hasMessageContaining("macros");
        assertThatThrownBy(() -> ChatFileTypes.requireAllowed("macro.docx", OfficeFiles.docxWithMacros(), MAX))
                .hasMessageContaining("macros");
        assertThatThrownBy(() -> ChatFileTypes.requireAllowed("macro.docx", OfficeFiles.docmContentType(), MAX))
                .hasMessageContaining("macros");
        assertThatThrownBy(() -> ChatFileTypes.requireAllowed("factura.docx", OfficeFiles.PDF, MAX))
                .hasMessageContaining("extensión");
        assertThatThrownBy(() -> ChatFileTypes.requireAllowed("virus.pdf", OfficeFiles.EICAR, MAX))
                .hasMessageContaining("no permitido");
        assertThatThrownBy(() -> ChatFileTypes.requireAllowed("sin-extension", OfficeFiles.PDF, MAX))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> ChatFileTypes.requireAllowed("grande.pdf", OfficeFiles.PDF, 5))
                .hasMessageContaining("tamaño");
        assertThatThrownBy(() -> ChatFileTypes.requireAllowed("vacio.pdf", new byte[0], MAX))
                .hasMessageContaining("vacío");
    }

    @Test
    void sanitizesFileNames() {
        assertThat(ChatFileTypes.safeName("C:\\Users\\ana\\<tarea>.pdf")).isEqualTo("_tarea_.pdf");
        assertThat(ChatFileTypes.safeName("../../etc/passwd")).isEqualTo("passwd");
        assertThat(ChatFileTypes.safeName(null)).isEqualTo("archivo");
        assertThat(ChatFileTypes.safeName("x".repeat(200) + ".pdf")).hasSize(120).endsWith(".pdf");
    }

    @Test
    void messagesAttachmentsAndReports() {
        UUID conv = UUID.randomUUID();
        UUID sender = UUID.randomUUID();
        assertThatThrownBy(() -> ChatMessage.send(conv, sender, ChatMessage.Type.TEXTO, " ", null, 10, NOW))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> ChatMessage.send(conv, sender, ChatMessage.Type.TEXTO, "x".repeat(11), null, 10, NOW))
                .hasMessageContaining("10");
        assertThatThrownBy(() -> ChatMessage.send(conv, sender, ChatMessage.Type.AUDIO, null, null, 10, NOW))
                .hasMessageContaining("adjunto");
        ChatMessage m = ChatMessage.send(conv, sender, ChatMessage.Type.TEXTO, " hola ", null, 10, NOW);
        assertThat(m.getText()).isEqualTo("hola");
        assertThatThrownBy(() -> m.deleteBy(UUID.randomUUID(), NOW)).isInstanceOf(DomainException.class);
        m.deleteBy(sender, NOW);
        assertThat(m.visible()).isFalse();
        m.purge(NOW);
        assertThat(m.getText()).isNull();

        ChatAttachment a = ChatAttachment.uploaded(conv, sender, "a.pdf", "pdf", "application/pdf", 10, null, "h",
                ChatAttachment.ScanStatus.ERROR, NOW);
        assertThat(a.getObjectKey()).startsWith("chat/").doesNotContain(".");
        assertThatThrownBy(() -> a.attachTo(UUID.randomUUID(), sender, conv)).hasMessageContaining("antivirus");
        ChatAttachment clean = ChatAttachment.uploaded(conv, sender, "a.ogg", "ogg", "audio/ogg", 10, 5, "h",
                ChatAttachment.ScanStatus.LIMPIO, NOW);
        assertThat(clean.isAudio()).isTrue();
        assertThatThrownBy(() -> clean.attachTo(UUID.randomUUID(), UUID.randomUUID(), conv))
                .hasMessageContaining("no pertenece");
        clean.attachTo(UUID.randomUUID(), sender, conv);
        assertThatThrownBy(() -> clean.attachTo(UUID.randomUUID(), sender, conv)).hasMessageContaining("ya se envió");
        clean.delete(NOW);
        assertThat(clean.getDeletedAt()).isEqualTo(NOW);

        assertThatThrownBy(() -> MessageReport.of(m.getId(), sender, "", NOW)).isInstanceOf(DomainException.class);
        assertThat(MessageReport.of(m.getId(), sender, " ofensivo ", NOW).getReason()).isEqualTo("ofensivo");

        Conversation c = Conversation.teacherStudent(sender, UUID.randomUUID(), null, NOW);
        assertThat(c.getPairKey()).isEqualTo(Conversation.pairKey(sender, c.getStudentId()));
        c.messageAt(NOW);
        assertThat(c.getLastMessageAt()).isEqualTo(NOW);
        Participant p = Participant.of(c.getId(), sender, Participant.Role.DOCENTE, NOW);
        p.read(NOW);
        assertThat(p.getLastReadAt()).isEqualTo(NOW);
        assertThat(new Participant.Key()).isNotNull();
    }
}
