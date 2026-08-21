# engine — C++20 audio engine

The platform-portable core of KeyQuest's real-time audio: microphone input, DSP, ML
transcription, MIDI decode, soundfont synth, and metronome. Only the Oboe I/O and
LiteRT bindings are platform code, so the engine can be reused by a future iOS port
(plan §4, §5.1).

## Layout (plan §5.1)

```
engine/
  src/
    io/OboeInput.cpp          # exclusive low-latency mic stream, 16kHz mono resample tap
    io/OboeOutput.cpp         # output mixer stream (metronome, stems, keyboard synth)
    dsp/RingBuffer.h
    dsp/OnsetDetector.cpp     # spectral-flux onset detection (cheap, always-on)
    dsp/Features.cpp          # STFT / mel / CQT frames for the model
    ml/Transcriber.cpp        # LiteRT session: frames → {pitch, onset, offset, velocity}
    midi/MidiDecoder.cpp      # raw MIDI bytes → NoteEvent (also handles running status)
    synth/SoundFontSynth.cpp  # TinySoundFont piano for touch keyboard + note previews
    metronome/Metronome.cpp   # sample-accurate click scheduled on output callback
    Engine.cpp                # lifecycle, config, event queue to JNI
  include/engine/NoteEvent.h
  test/                       # offline harness: WAV in → events out (runs on host, CI)
```

Current state: scaffold with a stub `engine_core` static library (`engine::version()`)
and a plain-assert host test. Catch2 and the Oboe/DSP pieces arrive in the P0.2 spike.

## Build and test (host, no device — this is what CI runs)

```bash
cmake -S engine -B engine/build -DCMAKE_BUILD_TYPE=Debug
cmake --build engine/build
ctest --test-dir engine/build --output-on-failure
```

Requires CMake 3.28+ and a C++20 compiler. Warnings are errors for GCC/Clang.