# asr-bench

Базовый бенчмарк потокового ASR (sherpa-onnx, те же модель и декодер, что в приложении). Запускается на хосте, без телефона.

    python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
    .venv/bin/python fetch_corpus.py                 # 200 клипов Svarah (индийский английский)
    .venv/bin/python bench.py                        # таблица WER / RTF / задержка первого partial
    .venv/bin/python bench.py --check                # падает, если WER хуже baseline.json на >1 п.п.

Свой корпус: папка с `manifest.tsv` (`file<TAB>reference<TAB>accent`) и WAV 16 кГц моно, затем
`bench.py --corpus путь --save-baseline`. Новые конфиги добавляются в `CONFIGS` в `bench.py`
(держать в синхроне с `AppContainer.kt`). RTF и задержка на ноутбуке не равны телефонным.

## Soniox (облако)

    SONIOX_API_KEY=... .venv/bin/python bench.py --configs soniox

Каждый клип стримится в реальном темпе (по 0.1 с), до 4 соединений параллельно, с переводом
на русский в том же потоке. WER считается по оригинальному тексту. Колонка latency для soniox —
время от конца аудио до последнего финального слова. Отдельно печатаются время до первого слова,
время до готового перевода и объём аудио в часах (для оценки стоимости). Распознанный текст и
переводы рядом с эталоном сохраняются в `results/soniox.tsv`.
200 клипов ≈ 0.3 часа аудио ≈ $0.04–0.07.
