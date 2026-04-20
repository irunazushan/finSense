from __future__ import annotations

from pathlib import Path


ML_TRAINING_DIR = Path(__file__).resolve().parents[1]
TOOLS_DIR = ML_TRAINING_DIR.parent
PROJECT_ROOT = TOOLS_DIR.parent

TRANSACTION_TESTER_DIR = TOOLS_DIR / "transaction-tester"
DATA_SOURCES_DIR = ML_TRAINING_DIR / "data_sources"
TRANSACTION_CATALOG_PATH = DATA_SOURCES_DIR / "transaction_catalog.yaml"
RULES_PATH = PROJECT_ROOT / "classifier-service" / "classifier-rules.yaml"
ENUM_PATH = (
    PROJECT_ROOT
    / "classifier-service"
    / "src"
    / "main"
    / "java"
    / "com"
    / "finsense"
    / "classifier"
    / "model"
    / "TransactionCategory.java"
)
