# Clone space AI control

Bliss runs cloned Android apps on a hidden virtual display owned by a Shizuku
(`uid 2000`) service. The AI tab in each clone app drives that display with the
same observe → decide → act loop:

```
screenshot -> on-device OCR -> model picks ONE JSON action -> shell injects it
```

The physical screen is never the input target: every touch/key event is tagged
with the virtual display id.

## Requirements

1. Shizuku installed and running (wireless debugging, or adb once per boot).
2. Shizuku permission granted to Bliss (`Tools -> Clone -> Enable Shizuku`).
3. A clone app (Apps tab) installed in the isolated `Bliss` Android user.
4. An OpenAI-compatible chat-completions endpoint:
   - cloud: `https://api.openai.com/v1` (or any compatible provider), API key,
     a tool/JSON-capable model such as `gpt-4o-mini`;
   - local: `http://127.0.0.1:8080/v1` (llama.cpp, vLLM, Ollama OpenAI proxy).
     Plain `http://` endpoints do not require an API key.

## Running a task

1. Apps -> pick a clone app -> **AI**.
2. **Change AI settings**: base URL, key (if cloud), model, max steps.
3. Optional **Vision**: sends the screenshot as an `image_url` part. Enable only
   for multimodal models; leave off for text-only models.
4. Enter a concrete goal, e.g.:
   `Open Settings, turn on Wi-Fi, connect to "Home", then come back`.
5. **Run AI**. The tab shows the step number, OCR line count, the last action
   and every action/result. **Stop AI** cancels an in-flight model call.

## How observations are shaped

OCR lines are numbered and include display-pixel bounds:

```
0 | Wi-Fi | 210,340 | 40,320,340,40
1 | Connected | 210,420 | 40,400,340,40
```

The model can therefore use `{"action":"tap_text","text":"Wi-Fi"}` or
`{"action":"tap","index":0}` instead of guessing coordinates. Raw
`{"action":"tap","x":120,"y":640}` is still accepted.

Supported actions: `tap_text`, `tap` (index or x/y), `long_press`, `swipe`
(direction/distance), `input_text`, `key` (back/home/enter/delete/tab/search),
`launch`, `wait`, `finish`.

## Limits

- Text injection goes through `KeyCharacterMap`, which is reliable for Latin
  scripts. For other scripts, prefer on-screen choices.
- OCR depends on ML Kit's bundled Latin model; enable Vision for icon-heavy or
  non-Latin screens.
- The model cannot read Android's accessibility tree in this round; it sees the
  screen through OCR (and optionally the screenshot).
- The clone app must be running on the virtual display; the AI starts it if
  needed and re-launches up to twice if it disappears.
