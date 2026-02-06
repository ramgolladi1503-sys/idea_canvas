# Idea Canvas (IdeaCoach)

A lightweight Android app for capturing ideas fast, coaching them later, and reviewing/exporting your thinking.

## Branding
- **Name:** Idea Canvas
- **Tagline:** Capture fast. Coach later.
- **Voice:** Calm, focused, and pragmatic.
- **Visuals:** Clean surfaces, warm neutrals, subtle accents.

If you want a logo or wordmark, tell me your preferred style (minimal, geometric, handwritten, etc.).

## Features
- Quick text capture
- Voice notes with offline transcription (Vosk)
- Coaching questions with saved answers
- Review inbox with status (New / Reviewed / Done)
- Audio playback with waveform + scrubber
- Batch export to Markdown (saved in Downloads)

## Screenshots
Add screenshots at:
- `docs/screenshots/home.png`
- `docs/screenshots/capture.png`
- `docs/screenshots/review.png`

Then update this section:
![Home](docs/screenshots/home.png)
![Capture](docs/screenshots/capture.png)
![Review](docs/screenshots/review.png)

## Requirements
- Android Studio (or Gradle + JDK 17)
- A device/emulator with microphone access

## Install & Run
1. Clone the repo and open it in Android Studio.
2. Let Gradle sync finish.
3. Add a Vosk model:
   - Download `vosk-model-small-en-us-0.15` from the official Vosk model list.
   - Place it at `app/src/main/assets/models/vosk-model-small-en-us-0.15/`.
4. Select a device/emulator and run the `app` configuration.

### CLI build (optional)
```
./gradlew :app:assembleDebug
```

## Notes
- The first launch after adding the model copies it into app storage on the device.
- Exports are written to Downloads (or app storage if Downloads is unavailable).

## Roadmap
- Cloud sync + multi-device support
- Smarter coach prompts that adapt to idea category
- One-tap share/export to Notion/Docs
- Background transcription queue
- On-device model management (download/remove)

## Project structure (high level)
- `app/src/main/java/com/madhuram/ideacoach/MainActivity.kt` – UI + flow
- `app/src/main/java/com/madhuram/ideacoach/data/` – Room + DataStore
- `app/src/main/java/com/madhuram/ideacoach/transcription/` – Vosk integration
- `app/src/main/java/com/madhuram/ideacoach/audio/` – recording/playback
