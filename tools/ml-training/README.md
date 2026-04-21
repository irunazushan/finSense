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

Export a large realistic holdout dataset for training and evaluation:

```bash
python export_dataset.py --profile realistic --split-strategy holdout_merchants --output-dir data
```

Export a smaller balanced dataset for debugging:

```bash
python export_dataset.py --profile balanced --split-strategy mixed --output-dir data-balanced
```

Train and export artifacts:

```bash
python train.py --data-dir data --artifact-dir artifacts
```

Evaluate sklearn and ONNX predictions:

```bash
python evaluate.py --data-dir data --artifact-dir artifacts --split test
```

Evaluate the trained model against the balanced debug dataset:

```bash
python evaluate.py --data-dir data-balanced --artifact-dir artifacts --split test
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

Datasets are generated from `data_sources/transaction_catalog.yaml`, then aligned
to the runtime contract in `classifier-service/classifier-rules.yaml`. The
catalog now models user archetypes, city affinity, channel-specific templates,
merchant holdouts, payment-provider wrappers, noisy terminal text, mixed
keyboard and transliterated merchant variants, refunds/reversals/installments,
recurring/autopay flows, hard negatives, and explicit `UNDEFINED` low-signal
transactions.

Available export profiles:

- `balanced`: equal rows for every output label; best for debugging.
- `realistic`: production-like class distribution with multi-user behavior and
  unseen-merchant evaluation.

Current labels:

- `FOOD_AND_DRINKS`
- `TRANSPORT`
- `GROCERIES`
- `RETAIL_SHOPPING`
- `ENTERTAINMENT`
- `HEALTH`
- `BANKING_AND_FEES`
- `BILLS_AND_GOVERNMENT`
- `UNDEFINED`

Metrics include accuracy, macro F1, weighted F1, per-category precision/recall/F1,
confusion matrix, confidence distribution, and dataset metadata. Evaluation
reports include dataset identity in the filename to avoid overwriting.
