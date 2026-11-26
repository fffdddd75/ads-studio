# Ads Studio — план

Android-приложение: режет длинное видео на короткие клипы (Shorts/Reels) и автоматически
вставляет рекламные вставки. Всё считается на телефоне, без облака.

- Платформа: Android, Kotlin + Jetpack Compose
- ИИ: локально — Vosk ASR сейчас, LLM следующим этапом
- Медиа: Media3 Transformer (FFmpeg для Android больше не поставляется)

---

## 0. Статус v0.1 (что реально в коде)

Этот файл — долгосрочный план. Текущая сборка в `app/` реализует упрощённый срез:

- выбор видео, probe через `MediaMetadataRetriever`;
- звук через `MediaCodec` → WAV 16 kHz mono (без FFmpeg);
- ASR через `vosk-android` (модель small-ru, офлайн);
- подбор клипов эвристикой (`ClipPicker`), без LLM;
- рендер 9:16 + рекламный bitmap-оверлей через Media3 Transformer;
- сохранение в `Movies/AdsStudio`.

Причины замены: FFmpegKit закрыт, его AAR удалены с Maven Central и GitHub Releases;
сборка FFmpeg из исходников на телефоне неподъёмна. Media3 закрывает v1 полностью.

---

## 1. Что делает приложение (MVP)

1. Пользователь выбирает видео из галереи.
2. Приложение транскрибирует речь (локальный Whisper).
3. Локальная LLM выбирает 3–10 лучших моментов и предлагает хук/заголовок.
4. Пользователь правит границы клипов (таймлайн).
5. Рендер: 9:16, субтитры, рекламная вставка (pre-roll или оверлей), экспорт MP4.
6. Шаринг через Intent / сохранение в Movies.

Реклама на старте — из «библиотеки креативов»: пользователь добавляет свои видео/картинки
+ ссылку/CTA, приложение подставляет их по правилам (ротация, привязка к теме клипа).

> Юридическое: вставлять рекламу можно только в свой контент или контент, на который есть права.
> У YouTube/TikTok есть правила про paid promotion — это ответственность пользователя.

---

## 2. Пайплайн

```
Video URI
  │
  ├─ 1. Probe (ffprobe): длительность, разрешение, fps, аудиодорожка
  ├─ 2. Extract audio → 16 kHz mono WAV (ffmpeg)
  ├─ 3. ASR: whisper.cpp → сегменты + word-level timestamps
  ├─ 4. Scene/silence hints (ffmpeg): смены сцен, паузы, громкость
  ├─ 5. LLM: выбрать клипы + места под рекламу → строгий JSON
  ├─ 6. UI: таймлайн, правки
  └─ 7. Render (ffmpeg): cut → 9:16 → captions → ad insert → encode
```

### Шаг 3. ASR

- `whisper.cpp` с официальным Android-примером (`examples/whisper.android`) или готовой
  JNI-обёрткой `GiviMAD/whisper-jni`.
- Модели: `ggml-small` (~466 МБ, ~850 МБ RAM) для русского; `base` (~142 МБ) если мало памяти.
- Флаги: `-l ru`, `-ml 1` для word-level timestamps, VAD (`silero`) чтобы не гонять тишину.
- Выход: `List<Segment(startMs, endMs, text, words: List<Word>)`.

### Шаг 4. Подсказки без ИИ (важно для MVP)

- Смены сцен: `ffmpeg -i in.mp4 -filter:v "select='gt(scene,0.35)',showinfo" -f null -`
- Паузы: `silencedetect=n=-35dB:d=0.6`
- Громкость: `ebur128` / `volumedetect`

Это даёт 80% качества нарезки почти бесплатно. LLM нужен для смысла, а не для границ.

### Шаг 5. LLM

- `llama.cpp` через JNI, GGUF Q4_K_M.
- Кандидаты под 7 ГБ RAM: `Qwen3-1.7B`, `Qwen2.5-1.5B-Instruct`, `Gemma-3-1B-it`.
  Модель 3B+ на телефоне будет и медленной, и голодной по памяти.
- Вход: транскрипт с таймкодами (+ метки сцен). Выход — **строгий JSON**.
- Обязательно GBNF-грамматика или JSON-schema constrained decoding, иначе маленькая модель
  насочиняет мусор. Пример контракта:

```json
{
  "clips": [
    { "start": 12.4, "end": 41.0, "title": "Хук", "reason": "..." }
  ],
  "ad": { "at": 0.0, "kind": "preroll|overlay|endcard", "creative_id": "..." }
}
```

- Промпт держим коротким: маленькие модели тонут в длинных инструкциях.

### Шаг 6. Рендер

Нарезка и склейка:
```
ffmpeg -ss START -to END -i in.mp4 -c:v libx264 -c:a aac clip.mp4
```

9:16 с блюр-фоном:
```
[0:v]split[a][b];
[a]scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920,boxblur=24[bg];
[b]scale=1080:-2[fg];
[bg][fg]overlay=(W-w)/2:(H-h)/2
```

Субтитры: whisper → `.ass` (karoake-стиль по словам) → `subtitles=captions.ass`.
Пакет FFmpeg должен включать `libass`, `freetype`, `fontconfig`.

Реклама:
- pre-roll: concat клипа рекламы перед клипом (одинаковые кодек/разрешение — иначе перекодировать);
- оверлей: `overlay=...:enable='between(t,2,7)'`;
- приглушение звука под рекламу: `volume=0.25:enable='between(t,2,7)'` или `sidechaincompress`;
- end-card: последний кадр + PNG/CTA.

Кодирование: H.264 + AAC, `-preset veryfast`, `-crf 23`, `+faststart`.

---

## 3. Как именно «встроить ИИ» в Android

Два нативных движка + один менеджер моделей. Никаких серверов.

```
core/ai/
  ModelStore.kt        // скачивание GGUF/ggml, проверка sha256, выбор модели
  WhisperEngine.kt     // JNI: init / transcribe(wav) -> segments
  LlmEngine.kt         // JNI: load / complete(prompt, grammar) -> токены
  PromptBuilder.kt     // транскрипт + сцены -> промпт
  JsonGuard.kt         // GBNF-грамматика / парсинг ответа
```

Интерфейс, к которому всё сводится:

```kotlin
interface LocalAsr {
    fun transcribe(wavPath: String, lang: String = "ru"): List<Segment>
}

interface LocalLlm {
    fun load(modelPath: String, contextSize: Int = 4096)
    fun complete(prompt: String, grammar: String?, maxTokens: Int = 768,
                 onToken: (String) -> Unit): String
    fun close()
}
```

JNI-часть — тонкая: грузим `System.loadLibrary("whisper")` / `("llama")`, передаём путь к
модели и колбэк. Вся бизнес-логика остаётся в Kotlin.

### Память — главный риск

На твоём устройстве ~7.3 ГБ всего и ~2.2 ГБ свободно. Whisper small (~0.85 ГБ) + LLM 1.7B
(~1.3 ГБ) одновременно не влезут. Поэтому **загрузка последовательная**:

1. Whisper: загрузили → транскрибировали → `free()`.
2. LLM: загрузили → выбрали клипы → `free()`.
3. FFmpeg: рендер.

Плюс: `largeHeap`, foreground service, отключение превью на время инференса.

### Производительность (реалистично)

- Whisper small на 6 ядрах: примерно 0.5–1.5× реального времени. 10-минутное видео = 7–20 мин.
- LLM 1.7B Q4: ~8–15 токенов/с, ответ на 300 токенов ≈ 30 сек.
- Рендер 1080×1920: ~1–3× длительности клипа.
- Итог: длинное видео — это «поставил и ушёл». Нужен WorkManager + прогресс в нотификации.

---

## 4. Стек и зависимости

| Слой | Выбор | Комментарий |
|---|---|---|
| UI | Compose + Material 3 | таймлайн рисуем на Canvas |
| Превью | Media3 ExoPlayer | без перекодирования |
| Медиа | FFmpegKitNext | FFmpegKit официально закрыт (2025); FFmpegKitNext — продолжение от автора, только из исходников |
| ASR | whisper.cpp / whisper-jni | MIT |
| LLM | llama.cpp JNI | MIT |
| Фон | WorkManager + foreground service | цепочка шагов, восстановление после kill |
| БД | Room | Project, Clip, AdCreative, Render |
| Модели | HuggingFace + sha256 | не бандлить в APK, качать по Wi-Fi |

FFmpeg-пакет собираем с `--enable-libass --enable-libfreetype --enable-libfontconfig`,
кодеки `x264`, `aac`. GPL-пакеты → приложение становится GPL; для закрытого продукта
оставляем LGPL-сборку (без x264, через MediaCodec).

---

## 5. Порядок работ

**Этап 0 — каркас (1–2 дня)**
- Gradle-проект, Compose, навигация, импорт видео, превью.
- FFmpeg probe + нарезка одного клипа по ручным таймкодам.

**Этап 1 — нарезка без ИИ**
- Сцены + паузы, автоподбор границ, 9:16 + блюр-фон, экспорт.
- Это уже рабочее приложение.

**Этап 2 — ASR**
- whisper.cpp, транскрипт, субтитры ASS, выбор клипов по ключевым словам.

**Этап 3 — LLM**
- llama.cpp, JSON-грамматика, выбор моментов + заголовки.

**Этап 4 — реклама**
- Библиотека креативов, pre-roll/оверлей/end-card, ротация, аналитика показов.

**Этап 5 — полировка**
- Face-tracking кроп (ML Kit), авто-хуки, шаблоны, очередь рендера.

---

## 6. Сборка без ПК

Устройство: только телефон, 7.3 ГБ RAM, 168 ГБ свободно.

### Вариант A — AndroidIDE (основной)

Приложение-IDE для Android: внутри JDK, Gradle и Android SDK.
Ставится из F-Droid или GitHub Releases, проект открывается прямо с телефона,
Build → APK → Install. Подходит для Kotlin + Compose. Медленно, но работает.

### Вариант B — GitHub Actions (облако)

Код лежит в репозитории, APK собирает бесплатный раннер. Телефон не напрягается.

```yaml
name: build
on: [push, workflow_dispatch]
jobs:
  apk:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - uses: android-actions/setup-android@v3
      - run: ./gradlew assembleDebug
      - uses: actions/upload-artifact@v4
        with:
          name: app-debug
          path: app/build/outputs/apk/debug/*.apk
```

### Вариант C — Termux + proot Ubuntu + SDK

Максимальный контроль, но aapt2/glibc и лицензии SDK делают это болью.
Только если A и B не зашли.

---

## 7. Открытые вопросы

- Язык интерфейса: русский / английский?
- Рекламные креативы: только свои файлы или ещё генерация картинки/текста локальной моделью?
- Нужен ли водяной знак и брендинг?
- Мониторизация/подписка или полностью бесплатно?
