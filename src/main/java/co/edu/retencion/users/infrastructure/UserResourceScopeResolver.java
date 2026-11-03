package co.edu.retencion.users.infrastructure;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.common.security.authorization.ResourceScope;
import co.edu.retencion.common.security.authorization.ResourceScopeResolver;
import co.edu.retencion.users.domain.UserRepository;

/**
 * Atributos ABAC de un usuario: pertenece a sus programas y es titular de su propio registro.
 */
@Component
class UserResourceScopeResolver implements ResourceScopeResolver {

    static final String TYPE = "USER";

    private final UserRepository users;

    UserResourceScopeResolver(UserRepository users) {
        this.users = users;
    }

    @Override
    public String resourceType() {
        return TYPE;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ResourceScope> resolve(UUID resourceId) {
        return users.findById(resourceId)
                .map(u -> new ResourceScope(TYPE, u.getId(), Set.copyOf(u.getProgramIds()), Set.of(), u.getId()));
    }
}
