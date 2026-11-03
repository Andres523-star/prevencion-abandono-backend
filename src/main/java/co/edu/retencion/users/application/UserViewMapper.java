package co.edu.retencion.users.application;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import co.edu.retencion.users.application.UserViews.MeView;
import co.edu.retencion.users.application.UserViews.UserDetailView;
import co.edu.retencion.users.application.UserViews.UserSummaryView;
import co.edu.retencion.users.domain.User;

/**
 * Mapeo entidad → DTO generado por MapStruct en compilación (sin reflexión en tiempo de ejecución).
 */
@Mapper
public interface UserViewMapper {

    @Mapping(target = "roles", expression = "java(user.roles())")
    UserSummaryView toSummary(User user);

    @Mapping(target = "roles", expression = "java(user.roles())")
    UserDetailView toDetail(User user);

    @Mapping(target = "roles", expression = "java(user.roles())")
    @Mapping(target = "mfa", source = "mfa")
    MeView toMe(User user, boolean mfa);
}
