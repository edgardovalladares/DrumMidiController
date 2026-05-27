# Drum MIDI Controller

Android drum-pad MIDI controller with local drum samples and General MIDI drum notes on channel 10.

## Features

- Fullscreen landscape drum-kit interface.
- Local audio preview using bundled WAV samples.
- MIDI output to connected Android MIDI devices.
- MIDI target selector with live device status.
- Master volume and velocity sensitivity sliders.
- Open hi-hat choke when closed hi-hat is triggered.
- Gradle Wrapper included for reproducible builds.

## Build

Install Android Studio or a JDK plus Android SDK, then run:

```powershell
.\gradlew.bat assembleDebug
```

The debug APK will be generated under `app/build/outputs/apk/debug/`.

