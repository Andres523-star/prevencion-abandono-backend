package co.edu.retencion.common.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Autenticación de Spring Security cuyo principal es {@link AuthenticatedUser}.
 */
public class PlatformAuthentication extends AbstractAuthenticationToken {

    private final AuthenticatedUser user;
    private final transient Jwt token;

    public PlatformAuthentication(AuthenticatedUser user, Jwt token) {
        super(user.roles().stream().map(r -> new SimpleGrantedAuthority(r.authority())).toList());
        this.user = user;
        this.token = token;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return token;
    }

    @Override
    public AuthenticatedUser getPrincipal() {
        return user;
    }

    @Override
    public String getName() {
        return user.userId().toString();
    }
}
