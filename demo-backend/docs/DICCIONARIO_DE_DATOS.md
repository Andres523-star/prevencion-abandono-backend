# Diccionario de Datos · Modelo Estudiantil

| Campo | Tipo | Descripcion | Rango / Ejemplo |
| :--- | :--- | :--- | :--- |
| `id` | Integer | Identificador numerico unico | 1 .. N |
| `nombre` | String | Nombres del estudiante | Ej. 'Juan Carlos' |
| `apellido` | String | Apellidos del estudiante | Ej. 'Perez Gomez' |
| `cedula` | String | Documento de identidad | 6 a 12 digitos |
| `sede` | String | Sede de la UCC | Una de las 18 sedes |
| `riesgoScore` | Integer | Puntaje de riesgo estimado | 0 a 100 |
| `riesgoActual` | String | Nivel clasificado | 'Bajo', 'Medio', 'Alto' |
| `promedio` | Double | Promedio ponderado acumulado | 0.0 a 5.0 |
| `faltas` | Integer | Inasistencias registradas | 0 a N |
