"""Entrenamiento fuera de línea del modelo de deserción (Fase 4).

Asistido por IA (Claude, Anthropic). Ver BITACORA_IA.md.

Entrada: el CSV seudonimizado que exporta ``POST /api/v1/ml/dataset-exports`` (solo ADMIN, auditado).
Salida: ``<version>.onnx`` y ``<version>.card.json`` para registrarlos con ``POST /api/v1/ml/models``.

Decisiones:
- Validación temporal: se entrena con los periodos anteriores y se prueba con los más recientes
  (``--test-terms``); nunca se mezclan periodos entre entrenamiento y prueba.
- Gradient boosting (LightGBM) calibrado con regresión isotónica sobre un tramo de validación
  temporal (el último periodo de entrenamiento), para que el puntaje sea una probabilidad.
- Revisión de sesgo: solo entran las variables de ``FEATURES`` (sin estrato, trabajo, personas a
  cargo, transporte, causas económicas ni datos de salud). Las columnas ``audit_*`` sirven solo para
  medir el desempeño por programa y por banda de estrato (``byProgram``, ``byGroup``).

Uso:
    python ml/train.py --dataset dataset-desercion.csv --test-terms 2026-1 --version lgbm-2026-11-04
"""

import argparse
import datetime as dt
import json
import sys

import numpy as np
import pandas as pd

# Debe coincidir con co.edu.retencion.risk.domain.MlFeatureVector.FEATURES
FEATURES = [
    "semester", "semester_average", "subjects_below_passing", "subject_count", "max_absence_ratio",
    "total_counted_absences", "subjects_at_critical_or_lost", "total_tardies", "subjects_trend_down",
    "conduct_observations", "academic_observations", "open_academic_causes", "open_personal_causes",
    "open_institutional_causes",
]
FORBIDDEN_TOKENS = {"stratum", "estrato", "works", "work", "trabaja", "dependents", "transport", "income",
                    "ingreso", "socio", "socioeconomic", "economic", "economica", "wellbeing", "bienestar",
                    "health", "salud", "gender", "genero", "sexo", "age", "edad", "audit"}
HIGH_RISK_THRESHOLD = 0.60
OUTPUT_NAME = "probabilities"


def check_features(features):
    for f in features:
        tokens = set(f.lower().replace("-", "_").replace(".", "_").split("_"))
        if tokens & FORBIDDEN_TOKENS:
            sys.exit(f"Variable prohibida por la política de sesgo: {f}")


def expected_calibration_error(y, p, bins=10):
    edges = np.linspace(0, 1, bins + 1)
    ece = 0.0
    for lo, hi in zip(edges[:-1], edges[1:]):
        mask = (p >= lo) & (p < hi) if hi < 1 else (p >= lo) & (p <= hi)
        if mask.any():
            ece += mask.mean() * abs(y[mask].mean() - p[mask].mean())
    return float(ece)


def metrics(y, p):
    from sklearn.metrics import brier_score_loss, recall_score, roc_auc_score
    from sklearn.calibration import calibration_curve

    out = {"n": int(len(y)), "positives": int(y.sum())}
    if len(np.unique(y)) < 2:
        out["auc"] = None
        return out
    frac_pos, mean_pred = calibration_curve(y, p, n_bins=10, strategy="quantile")
    out.update({
        "auc": round(float(roc_auc_score(y, p)), 4),
        "recallHighRisk": round(float(recall_score(y, (p >= HIGH_RISK_THRESHOLD).astype(int))), 4),
        "brier": round(float(brier_score_loss(y, p)), 4),
        "ece": round(expected_calibration_error(y, p), 4),
        "calibration": [{"predicted": round(float(a), 4), "observed": round(float(b), 4)}
                        for a, b in zip(mean_pred, frac_pos)],
    })
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dataset", required=True)
    parser.add_argument("--test-terms", required=True, nargs="+", help="periodos más recientes para la prueba")
    parser.add_argument("--version", required=True, help="p. ej. lgbm-2026-11-04 (minúsculas, números, . _ -)")
    parser.add_argument("--out", default=".")
    parser.add_argument("--n-estimators", type=int, default=300)
    args = parser.parse_args()

    import lightgbm as lgb
    from onnxmltools.convert.lightgbm.operator_converters.LightGbm import convert_lightgbm
    from skl2onnx import convert_sklearn, update_registered_converter
    from skl2onnx.common.data_types import FloatTensorType
    from skl2onnx.common.shape_calculator import calculate_linear_classifier_output_shapes
    from sklearn.calibration import CalibratedClassifierCV
    from sklearn.model_selection import PredefinedSplit

    check_features(FEATURES)
    df = pd.read_csv(args.dataset)
    terms = list(dict.fromkeys(df["term_code"]))  # el CSV viene en orden cronológico
    test_terms = [t for t in terms if t in set(args.test_terms)]
    train_terms = [t for t in terms if t not in set(args.test_terms)]
    if not test_terms or len(train_terms) < 2:
        sys.exit("Se necesitan al menos 2 periodos de entrenamiento y 1 de prueba")
    if terms.index(test_terms[0]) < terms.index(train_terms[-1]):
        sys.exit("Validación temporal: los periodos de prueba deben ser posteriores a los de entrenamiento")

    calib_terms = train_terms[-1:]
    X = df[FEATURES].astype("float32")
    y = df["label_dropout"].astype(int)
    train = df["term_code"].isin(train_terms)
    test = df["term_code"].isin(test_terms)

    booster = lgb.LGBMClassifier(n_estimators=args.n_estimators, learning_rate=0.05, num_leaves=31, min_child_samples=40,
                                 subsample=0.8, subsample_freq=1, colsample_bytree=0.9, random_state=42,
                                 verbose=-1)
    # Un único pliegue temporal: el booster se ajusta con los periodos anteriores y la calibración
    # isotónica con el último periodo de entrenamiento (nunca con datos de prueba).
    test_fold = np.where(df.loc[train, "term_code"].isin(calib_terms), 0, -1)
    model = CalibratedClassifierCV(booster, method="isotonic", cv=PredefinedSplit(test_fold))
    model.fit(X[train].to_numpy(), y[train].to_numpy())

    p = model.predict_proba(X[test].to_numpy())[:, 1]
    yt = y[test].to_numpy()
    report = metrics(yt, p)
    test_df = df[test]
    report["byProgram"] = {prog: metrics(yt[(test_df["audit_program"] == prog).to_numpy()],
                                         p[(test_df["audit_program"] == prog).to_numpy()])
                           for prog in sorted(test_df["audit_program"].unique())}
    report["byGroup"] = {band: metrics(yt[(test_df["audit_stratum_band"] == band).to_numpy()],
                                       p[(test_df["audit_stratum_band"] == band).to_numpy()])
                         for band in sorted(test_df["audit_stratum_band"].unique()) if band != "SIN_DATO"}

    update_registered_converter(lgb.LGBMClassifier, "LightGbmLGBMClassifier",
                                calculate_linear_classifier_output_shapes, convert_lightgbm,
                                options={"nocl": [True, False], "zipmap": [True, False, "columns"]})
    onx = convert_sklearn(model, initial_types=[("features", FloatTensorType([None, len(FEATURES)]))],
                          options={id(model): {"zipmap": False}},
                          target_opset={"": 17, "ai.onnx.ml": 3})
    onnx_path = f"{args.out}/{args.version}.onnx"
    with open(onnx_path, "wb") as fh:
        fh.write(onx.SerializeToString())

    card = {
        "version": args.version,
        "algorithm": "lightgbm+isotonic",
        "trainedAt": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "trainingTerms": train_terms,
        "testTerms": test_terms,
        "features": FEATURES,
        "metrics": report,
        "outputName": OUTPUT_NAME,
    }
    with open(f"{args.out}/{args.version}.card.json", "w", encoding="utf-8") as fh:
        json.dump(card, fh, ensure_ascii=False, indent=2)
    print(json.dumps({k: report.get(k) for k in ("auc", "recallHighRisk", "brier", "ece")}, indent=2))


if __name__ == "__main__":
    main()
