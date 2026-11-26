# Ads Studio

Приложение для Android: режет видео на вертикальные клипы 9:16 и вставляет рекламу.
Всё считается на телефоне, без сервера и без интернета (кроме первой загрузки модели).

## Что уже умеет (v0.1)

- выбрать видео из галереи;
- распознать русскую речь офлайн (Vosk);
- подобрать 3–5 связных моментов по 8–65 секунд;
- отрендерить клип 1080×1920 с кропом и рекламной картинкой-оверлеем;
- сохранить результат в `Movies/AdsStudio` и поделиться.

## Чего пока нет

- LLM для умного выбора моментов (следующий шаг);
- вжигание субтитров в видео;
- видео-реклама pre-roll (пока только картинка-оверлей);
- размытый фон вместо кропа.

## Почему не FFmpeg

FFmpegKit официально закрыт в 2025-м: AAR удалены с Maven Central и из GitHub Releases.
Собирать FFmpeg через NDK на телефоне — часы компиляции. Поэтому рендер сделан на
**Media3 Transformer** (официальный Google, аппаратный H.264/AAC). Функционал для v1 тот же:
резка, кроп, оверлеи, звук.

## Сборка без ПК (AndroidIDE)

1. В Termux один раз:
   ```bash
   termux-setup-storage
   ```
2. Скопировать проект в общую память:
   ```bash
   mkdir -p ~/storage/shared/AndroidIDEProjects
   cp -r "$HOME/ads studio" ~/storage/shared/AndroidIDEProjects/
   ```
3. AndroidIDE → **Open Project** → `AndroidIDEProjects/ads studio`.
4. Дождаться Gradle sync (первый раз 5–15 минут) и нажать **Run**.

Gradle 8.7, AGP 8.4.2, Kotlin 1.9.24, compileSdk 34, minSdk 29.

## Сборка в облаке (GitHub Actions)

В `.github/workflows/build.yml` лежит готовый workflow. Пуш в GitHub → вкладка Actions →
артефакт `ads-studio-debug` → скачать APK.

## Модель распознавания

Скачивается при первом нажатии «Распознать речь»:
`vosk-model-small-ru-0.22` (~45 МБ) с alphacephei.com, распаковывается в `filesDir/asr`.
Можно скачать заранее и положить туда же — приложение найдёт папку с `am/final.mdl`.

## Структура

```
app/src/main/java/com/adsstudio/
  MainActivity.kt      точка входа
  ui/App.kt            весь Compose-интерфейс
  AppViewModel.kt      состояние и сценарий
  VideoProbe.kt        длительность/разрешение
  AudioExtractor.kt    MediaCodec -> WAV 16 kHz mono
  VoskAsr.kt           речь -> фразы с таймкодами
  ClipPicker.kt        эвристический подбор моментов
  ClipExporter.kt      Media3: 9:16 + оверлей + экспорт
  Gallery.kt           сохранение в Movies
  ModelManager.kt      скачивание и распаковка модели
```

## Следующие шаги

1. Вжигание субтитров (ASS/оверлеи Media3).
2. Локальная LLM для выбора моментов (MediaPipe LLM / llama.cpp).
3. Видео-реклама pre-roll и end-card.
4. Фон-блюр и авто-кроп по лицу.
