# ML Training

Python-only training platform for the transaction classifier.

## Setup

```bash
cd tools/ml-training
python -m venv .venv
. .venv/Scripts/activate
pip install -r requirements.txt
```

## Workflow

Export deterministic balanced datasets for training:

```bash
python export_dataset.py --output-dir data
```

Export production-like imbalanced datasets for final evaluation:

```bash
python export_dataset.py --profile realistic --output-dir data-realistic
```

Train and export artifacts:

```bash
python train.py --data-dir data --artifact-dir artifacts
```

Evaluate sklearn and ONNX predictions:

```bash
python evaluate.py --data-dir data --artifact-dir artifacts --split test
```

Evaluate the trained model against the realistic distribution:

```bash
python evaluate.py --data-dir data-realistic --artifact-dir artifacts --split test
```

Predict one manual transaction with the ONNX artifact:

```bash
python predict.py --amount 350 --description "coffee payment" --merchant-name "Starbucks Cafe" --mcc-code 5812
```

Compare with the sklearn pipeline:

```bash
python predict.py --runtime sklearn --amount 900 --description "taxi ride" --merchant-name "Yandex Taxi" --mcc-code 4121
```

Generated datasets and model artifacts are ignored by git.

## Dataset Source

Datasets are generated from `data_sources/transaction_catalog.yaml`, not from
`classifier-service/classifier-rules.yaml`. The catalog includes merchant names,
aliases, MCC codes, text templates, payment-provider wrappers, noisy terminal
text, mixed Russian/English descriptions, hard negatives, explicit `OTHER`
examples, and explicit `UNDEFINED` low-signal transactions.

Available export profiles:

- `balanced`: equal rows for every output label; best for baseline training.
- `realistic`: production-like class distribution; best for final evaluation.

Metrics include accuracy, macro F1, weighted F1, per-category precision/recall/F1,
confusion matrix, and confidence distribution.
