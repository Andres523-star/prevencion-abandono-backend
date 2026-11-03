package co.edu.retencion.settings.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/** Repositorio de parámetros. Interno del módulo settings. */
public interface AppSettingRepository extends JpaRepository<AppSetting, String> {

    List<AppSetting> findAllByOrderByKeyAsc();
}
