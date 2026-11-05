package co.edu.retencion.chat.application;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import co.edu.retencion.common.error.ApiProblem;
import co.edu.retencion.common.error.ProblemCatalogContributor;

/** Prototipos de error del chat. */
@Component
class ChatProblems implements ProblemCatalogContributor {

    static final String FILE_INFECTED = "chat_file_infected";
    static final String READ_ONLY = "chat_read_only";

    @Override
    public List<ApiProblem> problems() {
        return List.of(
                new ApiProblem(FILE_INFECTED, HttpStatus.UNPROCESSABLE_ENTITY, "El archivo fue rechazado por el antivirus"),
                new ApiProblem(READ_ONLY, HttpStatus.CONFLICT,
                        "La conversación es de solo lectura: el estudiante ya no está inscrito en un grupo del docente"));
    }
}
