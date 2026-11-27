# Ads Studio — технический бриф для ИИ-агента

Этот файл — точка входа для другого ИИ, который продолжит работу над проектом.
Читай его целиком перед первым изменением кода. Здесь: что за продукт, что уже работает,
как устроен код, где грабли и что делать дальше.

Дата актуализации: 27.11.2026. Версия: 0.4.0 (`versionCode 6`).

---

## 1. Продукт в одном абзаце

Android-приложение для телефона: пользователь выбирает длинное видео, приложение локально
распознаёт речь, нейросеть выбирает 5 самых живых моментов, добавляет рекламную вставку,
рендерит 5 коротких MP4 (16:9 / 9:16 / 1:1, 720p) в `Movies/AdsStudio`.
Всё офлайн, без облака и без ПК. Интерфейс русский, пользователь — не технарь:
никаких ручных имён файлов, никаких настроек «для разработчика».

## 2. Жёсткие ограничения (не нарушать)

1. **Только на телефоне.** Никаких серверов, API-ключей, облачных моделей. Всё считается локально.
2. **Никакого облака в рантайме.** Сеть допустима только для скачивания моделей с HuggingFace.
3. **Пользователь не должен ничего настраивать.** Заголовки, имена файлов, границы — автоматически.
4. **Всегда должен быть результат.** Если ИИ упал — откат на быструю модель, потом на эвристику.
5. **Стабильная debug-подпись.** `app/ads-debug.p12`, alias `adsdebug`, пароль `android`.
   Менять нельзя: иначе обновление не встанет поверх.
6. **Тёмная тема принудительно**, edge-to-edge, тёмные системные бары.
7. **Язык UI и сообщений — русский**, простыми словами, без технического жаргона.
8. **arm64-v8a only** — нативные библиотеки llama.cpp собраны под него.

## 3. Стек

| Слой | Чем сделано |
|---|---|
| UI | Kotlin + Jetpack Compose, Material 3, Compose BOM 2024.06.00, Kotlin compiler ext 1.5.14 |
| Медиа | Media3 Transformer (нарезка, кроп, оверлей, энкод) |
| ASR | Vosk (`vosk-android`), модель small-ru в assets/файлах |
| Текстовый ИИ | Qwen2.5-0.5B-Instruct Q8, формат `.task` (MediaPipe LLM Inference) |
| Видео-ИИ | Qwen2.5-VL-3B Q4_K_M + mmproj Q8 через llama.cpp `llama-server` (HTTP на localhost) |
| Сборка | GitHub Actions, Gradle 8.7, JDK 17, AGP/SDK 34 |
| Хранение настроек | SharedPreferences `ads_studio` |

## 4. Карта репозитория

```
app/src/main/java/com/adsstudio/
  MainActivity.kt         точка входа, edge-to-edge
  AppViewModel.kt         весь пайплайн анализа и экспорта, состояние UI (440 строк)
  Models.kt               Utterance, SuggestedClip, OutputRatio, UiState
  VideoProbe.kt           MediaMetadataRetriever: длительность/размер/поворот
  AudioExtractor.kt       MediaCodec → WAV 16 kHz mono
  VoskAsr.kt              Vosk → List<Utterance> с прогрессом
  ClipPicker.kt           эвристика: кандидаты, оценка, добор до 5
  LlmClipSelector.kt      промпт и парсер ответа быстрой LLM
  LocalLlm.kt             обёртка MediaPipe LlmInference
  LlmModelManager.kt      скачивание/проверка .task (Qwen 0.5B)
  VisualHints.kt          подписи к кадрам для карточек клипов
  ClipExporter.kt         Media3 Transformer: нарезка + кроп + реклама + сохранение
  VlmModelManager.kt      скачивание 2 GGUF (2,6 ГБ), докачка, зеркала
  VlmServer.kt            запуск llama-server, /health, /v1/chat/completions
  VlmClipSelector.kt      коллажи кадров + промпт + парсинг номеров
  ui/App.kt               экран, карточки, диалоги (539)
  ui/StudioUi.kt          компоненты и анимации (1501)
  ui/StudioTheme.kt       цвета, шрифты Unbounded/Manrope, токены

app/src/main/jniLibs/arm64-v8a/   llama.cpp: libllama-server.so + 7 .so (~23 МБ)
app/src/main/assets/              шрифты
.github/workflows/build.yml       CI: assembleDebug → artifact ads-studio-debug
PLAN.md                           этот файл
```

## 5. Пайплайн (как есть в коде)

```
onVideoPicked(uri)            AppViewModel
  → VideoProbe.probe()        длительность, размер, поворот
analyze()
  → AudioExtractor.extract()  WAV 16k mono, прогресс 0..1
  → VoskAsr.transcribe()      List<Utterance>(startMs, endMs, text)
  → ClipPicker.candidates(u, 10)   до 10 кандидатов, у каждого score
  → выбор 5 клипов, три режима:
      A. llmEnabled=false            → ClipPicker.pickFromCandidates(candidates, 5)
      B. llmEnabled && !vlmEnabled   → chooseWithFastLlm() (Qwen 0.5B по тексту)
      C. llmEnabled && vlmEnabled    → VLM, при ошибке → B, при ошибке B → A
  → VisualHints.analyze()     подписи к кадрам
  → UiState(clips, notice, busy=null)
exportAll()
  → ClipExporter.export() для каждого клипа → Movies/AdsStudio
```

Контракты, на которые можно опираться:

```kotlin
data class Utterance(val startMs: Long, val endMs: Long, val text: String)
data class SuggestedClip(val startMs: Long, val endMs: Long,
                         val title: String, val text: String, val score: Float)

ClipPicker.candidates(utterances, limit): List<SuggestedClip>
ClipPicker.pickFromCandidates(candidates, maxClips): List<SuggestedClip>   // сортировка по score
ClipPicker.fillSelection(selected, all, maxClips): List<SuggestedClip>     // добор до 5 без дублей

VlmClipSelector.select(context, uri, candidates, maxClips, server,
                       onProgress, onFramesReady): List<Int>               // 1-based номера
```

## 6. Видео-ИИ: самое хрупкое место

### Файлы и размеры

| Файл в `filesDir/vlm/` | Размер |
|---|---|
| `qwen2.5-vl-3b-q4_k_m.gguf` | 1 929 901 056 |
| `qwen2.5-vl-3b-mmproj-q8_0.gguf` | 844 757 728 |

Зеркала (по порядку): `https://hf-mirror.com/ggml-org/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main/`,
затем `https://huggingface.co/ggml-org/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main/`.
Скачивание с докачкой (`Range`), проверка **точного** размера, проверка свободного места.

### Запуск сервера

`VlmServer.start()` берёт `nativeLibraryDir/libllama-server.so`, ставит `chmod +x`,
запускает процесс с окружением:

```
LD_LIBRARY_PATH=<nativeLibraryDir>
GGML_BACKEND_PATH=<nativeLibraryDir>
HOME=<filesDir>   TMPDIR=<cacheDir>   (LD_PRELOAD удаляется)
```

Флаги: `-m <model> --mmproj <mmproj> -c 6144 -np 1 -t 4 --host 127.0.0.1 --port 18081
--no-webui --jinja --image-max-tokens 384 --log-colors off`.
Ждём `/health` до 240 с. Лог — `filesDir/vlm-server.log` (в ошибку попадает хвост).

Сервер **переиспользуется** между анализами: инстанс живёт в `AppViewModel.vlmServer`,
закрывается в `onCleared()`. Не возвращай `VlmServer(app).use { }` — это убивает кэш модели.

### Запрос

OpenAI-совместимый `POST /v1/chat/completions`, `stream=false`, `temperature=0.1`,
`max_tokens=96`. Сообщение: system («ты редактор коротких видео») + user с частями
`text` / `image_url` (data:image/jpeg;base64). На каждого кандидата — коллаж 640×360:
три кадра (12%, 50%, 88% длительности) + четвёртая ячейка с номером и таймкодом.
Ответ парсится регуляркой `\d+`, фильтруется в `1..candidateCount`, берётся первые 5.

### Память

Веса ~2,8 ГБ + KV-кэш под 6144 токена. На устройстве 8 ГБ физической + 8 ГБ swap.
При нехватке памяти Android убьёт процесс — это ловится как ошибка и уходит в откат.
Если начнутся OOM: `-c 4096`, `--cache-type-k q8_0 --cache-type-v q8_0`, `--no-mmap`,
либо квант Q3_K_M.

## 7. Что проверено, а что нет

Проверено на устройстве:
- `llama-server` из этой сборки стартует и отвечает по HTTP;
- Qwen2.5-VL-3B корректно описывает коллаж кадров (проверено на тестовой картинке);
- APK 0.4.0 собирается в CI (run 45944931501) и ставится поверх 0.3.1;
- эвристика и быстрая модель работают с 0.3.0.

НЕ проверено:
- скорость полного анализа в нативном приложении;
- качество выбора моментов на реальном русском видео;
- поведение под давлением памяти во время инференса.

**Важная оговорка про замеры.** Тесты в Termux идут через proot: каждый page fault
трапуется в трейсер, поэтому mmap-веса читаются с флеша и скорость падает в разы
(наблюдалось ~1 токен/с). Эти цифры нельзя переносить на приложение. Мерить только APK.

## 8. Грабли, на которые уже наступали

- `const val TOTAL_BYTES = MODEL_BYTES + MMPROJ_BYTES` **до** объявления этих констант
  не компилируется («must be initialized»). Объявлять после.
- `jniLibs.useLegacyPackaging = true` + `android:extractNativeLibs="true"` обязательны,
  иначе `.so` не распакуются и `llama-server` не запустится.
- `usesCleartextTraffic="true"` нужен для HTTP на 127.0.0.1 (Android 9+ блокирует cleartext).
- `LD_PRELOAD` в Termux ломает запуск — в приложении переменная удаляется из окружения.
- Запуск бинарника возможен только из `nativeLibraryDir` (там разрешён exec).
- Сборка падает, если добавлять `--log-file` (конфликтует с redirect в ProcessBuilder).
- Артефакт CI качается медленно; логи упавшей джобы отдают `BlobNotFound`, пока джоба не завершилась.
- Git push без токена падает (`could not read Username`) — пушить HTTPS-URL с PAT,
  токен в репозиторий не коммитить.

## 9. GitHub, сборка и доставка APK

### Репозиторий

- URL: `https://github.com/fffdddd75/ads-studio`
- Ветка по умолчанию: `main`, репозиторий **публичный**
- Рабочая копия на телефоне: `/data/data/com.termux/files/home/ads studio`
- Токен (PAT) в репозиторий **не коммитить**. Он нужен только для `git push` и GitHub API,
  подставляется в команду из локального окружения/сессии.

### Как устроена сборка

`.github/workflows/build.yml`, имя workflow — `build`.

Триггеры:
- `push` в любую ветку (основной путь — push в `main`);
- `workflow_dispatch` (ручной запуск кнопкой в вебе или через API).

Шаги: checkout → JDK 17 (temurin) → Android SDK (`platforms;android-34 build-tools;34.0.0`)
→ Gradle 8.7 → `gradle assembleDebug --no-daemon` → upload-artifact.

Результат: артефакт **`ads-studio-debug`** (zip ~32 МБ), внутри `app-debug.apk` (~53 МБ).
Сборка идёт ~4–6 минут. Отдельного релиза нет — забираем артефакт.

### Команды (нужен PAT в переменной)

```bash
# 1. Запушить изменения
git add -A && git commit -m "..." \
  && git push https://<user>:<PAT>@github.com/fffdddd75/ads-studio.git main

# 2. Посмотреть последние запуски
curl -s -H "Authorization: token $PAT" \
  "https://api.github.com/repos/fffdddd75/ads-studio/actions/runs?per_page=3" \
  | python3 -c "import json,sys;[print(r['id'],r['head_sha'][:7],r['status'],r['conclusion']) for r in json.load(sys.stdin)['workflow_runs']]"

# 3. Артефакты успешного запуска (подставить RUN_ID)
curl -s -H "Authorization: token $PAT" \
  "https://api.github.com/repos/fffdddd75/ads-studio/actions/runs/$RUN_ID/artifacts" \
  | python3 -c "import json,sys;[print(a['id'],a['name'],a['size_in_bytes']) for a in json.load(sys.stdin)['artifacts']]"

# 4. Скачать и распаковать
curl -sL -H "Authorization: token $PAT" \
  "https://api.github.com/repos/fffdddd75/ads-studio/actions/artifacts/$ARTIFACT_ID/zip" -o apk.zip
unzip -o apk.zip -d apk_out

# 5. Отдать пользователю
cp apk_out/app-debug.apk /storage/emulated/0/Download/AdsStudio-<версия>.apk
cp apk_out/app-debug.apk /storage/emulated/0/Download/AdsStudio.apk
```

Логи упавшей джобы:

```bash
curl -sL -H "Authorization: token $PAT" \
  "https://api.github.com/repos/fffdddd75/ads-studio/actions/jobs/$JOB_ID/logs" \
  | grep -nE "e: |error:|FAILURE:|What went wrong"
```

ID джобы берётся из
`https://api.github.com/repos/fffdddd75/ads-studio/actions/runs/$RUN_ID/jobs`.

### Правила релиза

- Поднять `versionName` и `versionCode` в `app/build.gradle.kts` **до** пуша.
- Имя файла для пользователя: `AdsStudio-<versionName>.apk`, плюс копия `AdsStudio.apk`.
- Ставить поверх предыдущей версии (подпись стабильная, данные сохраняются).
- Не отдавать APK, пока CI не сказал `completed success`.

### Типовые проблемы

| Симптом | Причина / что делать |
|---|---|
| `could not read Username for 'https://github.com'` | пушить URL с PAT, а не `origin` без кредов |
| логи джобы отдают `BlobNotFound` | джоба ещё не завершилась, подождать |
| `e: ... must be initialized` | forward-reference констант в `companion object` |
| артефакт качается по 5–10 минут | GitHub медленный, качать в фоне с `--retry`, не убивать |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | сменилась подпись — проверить `ads-debug.p12` |
| сборка зелёная, но APK не ставится | проверить ABI (только arm64-v8a) и `minSdk` |

## 10. Следующие задачи (по приоритету)

1. **Замер и скорость VLM.** Получить от пользователя время анализа 10-минутного видео.
   Если > 90 с — один общий коллаж на всех 6 кандидатов вместо 6 картинок,
   `--image-max-tokens 256`, `-c 4096`. Критерий: анализ не дольше ~60 с на 10-мин видео.
2. **Качество выбора.** Сравнить с быстрой моделью на 2–3 реальных видео.
   Крутить промпт (жёстче отсекать «говорящую голову», приветствия, воду),
   число кандидатов (6→8), 3 кадра → 2. Критерий: пользователь говорит «моменты норм».
3. **Диагностика.** Показывать хвост `vlm-server.log` в тексте ошибки, добавить кнопку
   «повторить только ИИ» без повторного ASR.
4. **0.5 — реклама.** Библиотека креативов (Room), pre-roll / оверлей / end-card,
   ротация, приглушение звука под вставку.
5. **0.6 — субтитры.** Пословные таймкоды Vosk → ASS, караоке-стиль, авто-хук первой фразой.
6. **0.7 — умный кроп.** ML Kit face detection → кроп 9:16 по лицу со сглаживанием.

## 11. Стиль работы с этим проектом

- Пользователь нетерпелив: сначала рабочий APK, потом улучшения. Не устраивать рефакторинг ради рефакторинга.
- Любое изменение проверять сборкой в CI, не «на глаз».
- Объяснения — коротко и по-русски, без терминов. Пользователь не читает код.
- Не ломать обратную совместимость настроек: ключи `llm_enabled`, `vlm_enabled` в prefs.
- Не добавлять разрешения и зависимости без необходимости: APK уже 53 МБ.
- Если меняешь поведение ИИ — всегда оставляй откат, пользователь не должен увидеть пустой экран.

## 12. Открытые вопросы к владельцу

- Видео-ИИ включён по умолчанию или только по выбору?
- Всегда 5 клипов или дать выбор 3/5/10?
- Реклама: только свои файлы или ещё генерация картинки локальной моделью?
- Нужен водяной знак / брендинг?
