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

Export deterministic synthetic datasets:

```bash
python export_dataset.py --output-dir data
```

Train and export artifacts:

```bash
python train.py --data-dir data --artifact-dir artifacts
```

Evaluate sklearn and ONNX predictions:

```bash
python evaluate.py --data-dir data --artifact-dir artifacts --split test
```

Generated datasets and model artifacts are ignored by git.
