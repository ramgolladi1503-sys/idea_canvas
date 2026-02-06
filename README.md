# Idea Canvas (IdeaCoach)

A lightweight Android app for capturing ideas fast, coaching them later, and reviewing/exporting your thinking.

## Features
- Quick text capture
- Voice notes with offline transcription (Vosk)
- Coaching questions with saved answers
- Review inbox with status (New / Reviewed / Done)
- Audio playback with waveform + scrubber
- Batch export to Markdown (saved in Downloads)

## Requirements
- Android Studio (or Gradle + JDK 17)
- A device/emulator with microphone access

## Setup
1. Open the project in Android Studio.
2. Sync Gradle.
3. Add a Vosk model:
   - Download `vosk-model-small-en-us-0.15` from the official Vosk model list.
   - Place it at:
     `app/src/main/assets/models/vosk-model-small-en-us-0.15/`
4. Run the `app` configuration.

## Notes
- The first launch after adding the model copies it into app storage on the device.
- Exports are written to Downloads (or app storage if Downloads is unavailable).

## Project structure (high level)
- `app/src/main/java/com/madhuram/ideacoach/MainActivity.kt` – UI + flow
- `app/src/main/java/com/madhuram/ideacoach/data/` – Room + DataStore
- `app/src/main/java/com/madhuram/ideacoach/transcription/` – Vosk integration
- `app/src/main/java/com/madhuram/ideacoach/audio/` – recording/playback
