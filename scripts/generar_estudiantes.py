#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Generador de estudiantes ficticios para la demo de PERMANENCIA (UCC).

Crea un archivo .csv con entre 600 y 1000 estudiantes por cada una de las 17
sedes de la Universidad Cooperativa de Colombia (más de 10.000 registros).

Reglas de riesgo (lógicas, no al azar):
  - Promedio bajo (< 3.0) + muchas faltas (> 12)      -> Riesgo ALTO
  - Promedio bajo-medio (< 3.4) o faltas medias (> 8) -> Riesgo MEDIO
  - El resto                                          -> Riesgo BAJO
La causa se elige coherente con el nivel de riesgo.

Uso en Mac (solo librería estándar, no hay que instalar nada):
  cd ~/Desktop/prevencion
  python3 scripts/generar_estudiantes.py
"""

import csv
import os
import random

# ------------------------------------------------------------------ #
# Configuración general (editable sin romper nada)
# ------------------------------------------------------------------ #

SEDES = [
    "Apartadó", "Arauca", "Barrancabermeja", "Bogotá", "Bucaramanga",
    "Cali", "Cartago", "El Espinal", "Ibagué", "Medellín",
    "Montería", "Neiva", "Pasto", "Pereira", "Popayán",
    "Santa Marta", "Villavicencio",
]

MIN_POR_SEDE = 600
MAX_POR_SEDE = 1000

SEED = 20261109  # semilla fija: los datos son reproducibles entre ejecuciones

# El CSV queda listo para que el backend de demo lo cargue.
ARCHIVO_SALIDA = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "demo-backend", "src", "main", "resources", "data", "estudiantes.csv",
)

# Facultades de la UCC con sus programas. Edita o agrega aquí si hace falta.
FACULTADES_PROGRAMAS = {
    "Facultad de Ingeniería": [
        "Ingeniería de Sistemas", "Ingeniería Civil", "Ingeniería Electrónica",
        "Ingeniería Industrial", "Ingeniería Ambiental",
    ],
    "Facultad de Ciencias Económicas": [
        "Administración de Empresas", "Contaduría Pública", "Economía",
        "Mercadeo", "Finanzas y Comercio Exterior",
    ],
    "Facultad de Derecho": ["Derecho", "Ciencia Política"],
    "Facultad de Salud": [
        "Medicina", "Odontología", "Enfermería", "Psicología",
        "Nutrición y Dietética",
    ],
    "Facultad de Educación y Humanidades": [
        "Licenciatura en Pedagogía", "Licenciatura en Lengua Castellana",
        "Licenciatura en Matemáticas", "Trabajo Social",
    ],
    "Facultad de Ciencias Sociales": [
        "Comunicación Social", "Publicidad y Mercadeo",
    ],
}

NOMBRES_FEMENINOS = [
    "María", "Laura", "Camila", "Valentina", "Sara", "Paula", "Daniela",
    "Mariana", "Luisa", "Natalia", "Isabella", "Sofía", "Juliana", "Ángela",
    "Carolina", "Diana", "Ana", "Gabriela", "Manuela", "Julia",
]
NOMBRES_MASCULINOS = [
    "Juan", "Sebastián", "Andrés", "Santiago", "Daniel", "Mateo", "Diego",
    "Nicolás", "Felipe", "David", "Carlos", "Óscar", "Iván", "Javier",
    "Esteban", "Camilo", "Miguel", "Alejandro", "Julián", "Pedro",
]
APPELLIDOS = [
    "Gómez", "Rodríguez", "Rincón", "García", "Muñoz", "Ibarra", "Morales",
    "Salazar", "Restrepo", "Pérez", "Martínez", "Sánchez", "Torres", "Ruiz",
    "Mendoza", "Oviedo", "Bolaños", "Cabrera", "Ramírez", "Franco",
    "Cárdenas", "Ríos", "Ortega", "Villalba", "Quintero", "Mora", "Duarte",
]

SEMESTRES = list(range(1, 11))

CAUSAS = {
    "Alto": [
        "Bajo rendimiento académico sostenido", "Inasistencias reiteradas a clase",
        "Dificultades económicas para sostener los estudios", "Situación familiar crítica",
        "Duelo o problema emocional no tratado", "Desmotivación con el programa",
    ],
    "Medio": [
        "Reprobó dos o más materias", "Trabaja y estudia en horario limitado",
        "Distancia larga del campus", "Déficit en materias de matemáticas",
        "Poca participación en la vida universitaria", "Beca o préstamo en riesgo",
    ],
    "Bajo": [
        "Sin novedades significativas", "Cambio de horario temporal",
        "Rendimiento estable y asistencia normal",
        "Recuperó las materias pendientes", "Riesgo bajo y seguimiento trimestral",
    ],
}

ENCABEZADOS = [
    "id", "nombre_completo", "cedula", "sede", "facultad",
    "programa", "semestre", "categoria_riesgo", "causa_detalle",
    "riesgo", "promedio", "faltas",
]


def calcular_riesgo(promedio, faltas, semestre):
    """Riesgo deducido de las notas y las faltas (lógica, no al azar)."""
    if promedio < 3.0 and faltas > 12:
        return "Alto"
    if promedio < 3.4 or faltas > 8:
        return "Medio"
    if semestre >= 8 and promedio < 3.6:
        return "Medio"
    return "Bajo"


def categoria_de_semaforo(riesgo):
    """Color del semáforo del panel según el nivel de riesgo."""
    return {"Alto": "Rojo", "Medio": "Amarillo", "Bajo": "Verde"}[riesgo]


def generar_filas_por_sede(sede):
    total = random.randint(MIN_POR_SEDE, MAX_POR_SEDE)
    filas = []
    for _ in range(total):
        facultad = random.choice(list(FACULTADES_PROGRAMAS))
        programa = random.choice(FACULTADES_PROGRAMAS[facultad])
        semestre = random.choice(SEMESTRES)
        # Promedios con distribución realista (más gente entre 3.0 y 4.5).
        promedio = round(random.triangular(2.4, 4.8, 3.7), 2)
        # Las faltas se concentran bajo y crecen un poco con el semestre.
        faltas = max(0, int(random.triangular(0, 26, 7)) + semestre // 3)
        riesgo = calcular_riesgo(promedio, faltas, semestre)
        filas.append({
            "sede": sede,
            "facultad": facultad,
            "programa": programa,
            "semestre": semestre,
            "categoria_riesgo": categoria_de_semaforo(riesgo),
            "causa_detalle": random.choice(CAUSAS[riesgo]),
            "riesgo": riesgo,
            "promedio": promedio,
            "faltas": faltas,
        })
    return filas


def ejecutar():
    random.seed(SEED)

    salida = os.path.abspath(ARCHIVO_SALIDA)
    os.makedirs(os.path.dirname(salida), exist_ok=True)

    # Cédulas ficticias únicas de 10 dígitos a partir de una base.
    siguiente_cedula = 10_900_000_01

    total = 0
    with open(salida, "w", encoding="utf-8", newline="") as archivo:
        escritor = csv.DictWriter(archivo, fieldnames=ENCABEZADOS)
        escritor.writeheader()
        for sede in SEDES:
            filas = generar_filas_por_sede(sede)
            for fila in filas:
                total += 1
                nombre1 = random.choice(random.choice(
                    [NOMBRES_FEMENINOS, NOMBRES_MASCULINOS]))
                nombre2 = random.choice(random.choice(
                    [NOMBRES_FEMENINOS, NOMBRES_MASCULINOS]))
                nombre = f"{nombre1} {nombre2}"
                # Evita nombres duplicados exactos "Juan Juan".
                if nombre1 == nombre2:
                    nombre2 = random.choice(random.choice(
                        [NOMBRES_FEMENINOS, NOMBRES_MASCULINOS]))
                    nombre = f"{nombre1} {nombre2}"
                apellido1 = random.choice(APPELLIDOS)
                apellido2 = random.choice(APPELLIDOS)
                escritor.writerow({
                    "id": total,
                    "nombre_completo": f"{nombre} {apellido1} {apellido2}",
                    "cedula": siguiente_cedula,
                    "sede": fila["sede"],
                    "facultad": fila["facultad"],
                    "programa": fila["programa"],
                    "semestre": fila["semestre"],
                    "categoria_riesgo": fila["categoria_riesgo"],
                    "causa_detalle": fila["causa_detalle"],
                    "riesgo": fila["riesgo"],
                    "promedio": fila["promedio"],
                    "faltas": fila["faltas"],
                })
                siguiente_cedula += 1
            print(f"  {sede}: {len(filas):4d} estudiantes")

    print(f"\nTotal generado: {total:,} estudiantes en {len(SEDES)} sedes")
    print(f"Archivo: {salida}")


if __name__ == "__main__":
    ejecutar()
