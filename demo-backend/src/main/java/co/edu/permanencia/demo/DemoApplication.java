package co.edu.permanencia.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Backend de demostración de Permanencia.
 *
 * <p>Guarda los 600 estudiantes de prueba, las alertas y el chat en memoria para que el demo
 * arranque sin base de datos ni servicios externos. La autenticación también es simplificada:
 * está pensada para una presentación académica, no para producción.</p>
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
