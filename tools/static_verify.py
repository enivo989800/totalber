#!/usr/bin/env python3
"""Environment-independent structural checks for the third-phase source tree."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
required = [
    "settings.gradle.kts",
    "build.gradle.kts",
    "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/com/build/lotteryai/MainActivity.kt",
    "app/src/main/java/com/build/lotteryai/model/DrawRecord.kt",
    "app/src/main/java/com/build/lotteryai/core/PredictionEngine.kt",
    "app/src/main/java/com/build/lotteryai/core/BacktestEngine.kt",
    "app/src/main/java/com/build/lotteryai/core/ExternalCandidateParser.kt",
    "app/src/main/java/com/build/lotteryai/data/LocalLotteryDb.kt",
    "app/src/test/java/com/build/lotteryai/core/BacktestEngineTest.kt",
    "app/src/test/java/com/build/lotteryai/core/ExternalCandidateParserTest.kt",
    "docs/design-reference/android-home-reference.png",
    "docs/design-reference/android-auxiliary-screens-reference.png",
    "app/src/main/java/com/build/lotteryai/ui/FrequencyBarChartView.kt",
    "docs/planning/PLAN_PHASE1_数据源与产品规划.md",
    "docs/planning/PLAN_PHASE2_扩展需求与BUILD规格.md",
    "docs/planning/PLAN_PHASE3_界面达标与GitHub构建闭环.md",
    "docs/planning/AUDIT_v1.2.0_PLAN与界面完整性.md",
    "app/src/main/java/com/build/lotteryai/data/OfficialLotteryRepository.kt",
    "app/src/main/java/com/build/lotteryai/ai/ApiConfigStore.kt",
    "app/src/main/java/com/build/lotteryai/ai/OpenAiCompatibleClient.kt",
    "app/src/main/java/com/build/lotteryai/export/TxtSnapshot.kt",
    ".github/workflows/android.yml",
]
errors = []
for rel in required:
    if not (ROOT / rel).is_file():
        errors.append(f"missing required file: {rel}")

manifest_path = ROOT / "app/src/main/AndroidManifest.xml"
if manifest_path.exists():
    try:
        manifest = ET.parse(manifest_path).getroot()
        android_ns = "{http://schemas.android.com/apk/res/android}"
        perms = {item.attrib.get(android_ns + "name") for item in manifest.findall("uses-permission")}
        if "android.permission.INTERNET" not in perms:
            errors.append("INTERNET permission is missing")
        app = manifest.find("application")
        if app is None or app.attrib.get(android_ns + "allowBackup") != "false":
            errors.append("application must disable automatic backup for local secrets")
        if app is None or app.attrib.get(android_ns + "usesCleartextTraffic") != "false":
            errors.append("cleartext HTTP should remain disabled")
    except ET.ParseError as exc:
        errors.append(f"manifest XML parse failed: {exc}")

repo = (ROOT / "app/src/main/java/com/build/lotteryai/data/OfficialLotteryRepository.kt").read_text(encoding="utf-8") if (ROOT / "app/src/main/java/com/build/lotteryai/data/OfficialLotteryRepository.kt").exists() else ""
if "https://www.cwl.gov.cn/" not in repo or "findDrawNotice" not in repo:
    errors.append("official lottery source endpoint is not configured")
if "limit in 1..1000" not in repo:
    errors.append("official source request range check is missing")
if "缺少 state 字段" not in repo:
    errors.append("official source parser must reject malformed responses without a state field")

secret_store = (ROOT / "app/src/main/java/com/build/lotteryai/ai/ApiConfigStore.kt").read_text(encoding="utf-8") if (ROOT / "app/src/main/java/com/build/lotteryai/ai/ApiConfigStore.kt").exists() else ""
if "AndroidKeyStore" not in secret_store or "AES/GCM/NoPadding" not in secret_store:
    errors.append("API Key encryption implementation is missing")

snapshot = (ROOT / "app/src/main/java/com/build/lotteryai/export/TxtSnapshot.kt").read_text(encoding="utf-8") if (ROOT / "app/src/main/java/com/build/lotteryai/export/TxtSnapshot.kt").exists() else ""
if "requestJson" not in snapshot or "portablePrompt" not in snapshot:
    errors.append("TXT snapshot must include request JSON and portable prompt")
if "Authorization" not in snapshot or "API Key" not in snapshot:
    errors.append("TXT exporter should explicitly document exclusion of credentials")

prediction = (ROOT / "app/src/main/java/com/build/lotteryai/core/PredictionEngine.kt").read_text(encoding="utf-8") if (ROOT / "app/src/main/java/com/build/lotteryai/core/PredictionEngine.kt").exists() else ""
for marker in ["RANDOM", "STATISTICAL", "STRUCTURE", "require(count in 1..100)"]:
    if marker not in prediction:
        errors.append(f"prediction engine marker missing: {marker}")



# Phase-two feature markers and non-destructive database migration checks.
db_source = (ROOT / "app/src/main/java/com/build/lotteryai/data/LocalLotteryDb.kt").read_text(encoding="utf-8") if (ROOT / "app/src/main/java/com/build/lotteryai/data/LocalLotteryDb.kt").exists() else ""
for marker in ["DB_VERSION = 3", "onUpgrade", "backtest_runs", "parsed_candidates_json", "exportBackupJson", "mergeBackupJson", "SQLiteDatabase.CONFLICT_IGNORE"]:
    if marker not in db_source:
        errors.append(f"local DB phase-two marker missing: {marker}")

main_source = (ROOT / "app/src/main/java/com/build/lotteryai/MainActivity.kt").read_text(encoding="utf-8") if (ROOT / "app/src/main/java/com/build/lotteryai/MainActivity.kt").exists() else ""
for marker in ["Screen.BACKTEST", "runBacktest()", "confirmBackfill()", "exportBackup()", "importBackup()", "exportRequest(request)", "ExternalCandidateParser.parse", "showCustomDataWindowDialog()", "dataWindowOptions()"]:
    if marker not in main_source:
        errors.append(f"MainActivity phase-two feature marker missing: {marker}")

backtest = (ROOT / "app/src/main/java/com/build/lotteryai/core/BacktestEngine.kt").read_text(encoding="utf-8") if (ROOT / "app/src/main/java/com/build/lotteryai/core/BacktestEngine.kt").exists() else ""
if "targetIndex - trainingWindow" not in backtest or "chronological[targetIndex]" not in backtest:
    errors.append("walk-forward backtest must train only on records before each test draw")

api_client = (ROOT / "app/src/main/java/com/build/lotteryai/ai/OpenAiCompatibleClient.kt").read_text(encoding="utf-8") if (ROOT / "app/src/main/java/com/build/lotteryai/ai/OpenAiCompatibleClient.kt").exists() else ""
for marker in ["MODE_CHAT_COMPLETIONS", "MODE_RESPONSES", "output_text", "choices"]:
    if marker not in api_client:
        errors.append(f"AI API mode/response compatibility marker missing: {marker}")



workflow_path = ROOT / ".github/workflows/android.yml"
if workflow_path.exists():
    workflow_text = workflow_path.read_text(encoding="utf-8")
    for marker in [
        "runs-on: ubuntu-24.04",
        "actions/checkout@v5",
        "actions/setup-java@v5",
        "android-actions/setup-android@v3",
        "gradle/actions/setup-gradle@v4",
        ":app:testDebugUnitTest",
        ":app:assembleDebug",
        "lottery-ai-debug-apk",
    ]:
        if marker not in workflow_text:
            errors.append(f"GitHub Actions workflow marker missing: {marker}")

# Phase-three UI structure and local task history checks.
chart_source = (ROOT / "app/src/main/java/com/build/lotteryai/ui/FrequencyBarChartView.kt").read_text(encoding="utf-8") if (ROOT / "app/src/main/java/com/build/lotteryai/ui/FrequencyBarChartView.kt").exists() else ""
for marker in ["class FrequencyBarChartView", "override fun onDraw", "chartValues", "contentDescription"]:
    if marker not in chart_source:
        errors.append(f"native frequency chart marker missing: {marker}")
for marker in ["Screen.TASKS -> buildTasks(body)", "private fun buildTasks", "private fun homeShortcutRow", "FrequencyBarChartView", "drawsVisibleCount", "db.predictionTasks(100)"]:
    if marker not in main_source:
        errors.append(f"phase-three native UI/task marker missing: {marker}")
if "fun predictionTasks(limit: Int = 50)" not in db_source:
    errors.append("local prediction-task history query is missing")

if not errors:
    print("PASSED: phase-two data/AI controls and phase-three native dashboard charts, task management, history loading, and plan-document checks.")

if errors:
    for error in errors:
        print("FAILED:", error)
    sys.exit(1)

print("PASSED: project structure, manifest, official source reference, local API-key encryption, TXT snapshot safeguards, and prediction strategy markers.")
print("NOTE: these are static checks only; they do not replace Android compilation, device testing, official-source network verification, or live AI API testing.")
