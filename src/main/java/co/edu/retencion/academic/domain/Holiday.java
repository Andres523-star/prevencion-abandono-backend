package co.edu.retencion.academic.domain;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Día festivo: no se generan sesiones y las programadas se cancelan. */
@Entity
@Table(name = "holidays")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Holiday {

    @Id
    @Column(name = "holiday_date")
    private LocalDate date;

    @Column(nullable = false)
    private String name;

    public Holiday(LocalDate date, String name) {
        this.date = date;
        this.name = name;
    }
}
