package co.edu.retencion.users.application;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import co.edu.retencion.common.error.ApiProblem;
import co.edu.retencion.common.error.ProblemCatalogContributor;

/** Prototipos de error del módulo de usuarios. */
@Component
class UsersProblems implements ProblemCatalogContributor {

    static final String IDENTITY_CONFLICT = "identity_conflict";
    static final String SELF_LOCKOUT = "self_lockout";

    @Override
    public List<ApiProblem> problems() {
        return List.of(
                new ApiProblem(IDENTITY_CONFLICT, HttpStatus.CONFLICT,
                        "El correo ya está vinculado a otra cuenta"),
                new ApiProblem(SELF_LOCKOUT, HttpStatus.CONFLICT,
                        "No puedes quitarte a ti mismo el rol de administrador ni desactivar tu cuenta"));
    }
}
