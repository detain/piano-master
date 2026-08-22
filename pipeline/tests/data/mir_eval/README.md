# mir_eval unit-test fixtures (for harness calibration)

The files in `transcription/` (`ref*.txt`, `est*.txt`, `output*.json`) are the
reference test fixtures from the [mir_eval](https://github.com/craffel/mir_eval)
project (MIT license), `tests/data/transcription/`. Each `output*.json` holds
the exact metric values that mir_eval's own test suite asserts to `1e-12`.

The harness calibration test (`tests/test_eval_harness.py::test_mir_eval_fixture_calibration`)
reproduces those reference values through `pipeline.eval.metrics`
(`note_precision_recall_f1_hz`), proving the harness's note precision/recall/F1
matches mir_eval's reference behavior exactly. This is the offline metric
calibration used by `pipeline.eval.validate_maestro` when the published
Basic-Pitch-on-MAESTRO comparison is blocked (see pipeline/README.md).