---
name: transcribing-audio
description: Use when a task requires STT/ASR.
when: exe.dev
---

## Steps

1. By default, save the transcript beside the original audio with the extension replaced by `.transcript.txt` (`notes/review.m4a` → `notes/review.transcript.txt`).

2. Check the input. The gpt-transcribe endpoint accepts mp3, mp4, mpeg, mpga, m4a, wav, webm, flac, and ogg, up to 25 MB. For an existing local file with a supported extension under that limit, use the fast path below immediately. Do not run `ffprobe`, inspect duration, list models, probe endpoints, load `reflection-integration`, or query available integrations first.

   Transcode unsupported or larger inputs using ffmpeg. Split and transcribe piecemeal if necessary; for better results, slightly overlap the chunks and then manually stitch together the overlapped outputs. Shelley browser screen recordings have a sibling `<recording-path>.json` sidecar with `path` and `duration_ms`. Preserve each split chunk's media start offset so chunk-relative timestamps can be rolled up to the original recording timeline.

3. Transcribe. Try `https://llm.int.exe.xyz`, then `https://openai.int.exe.xyz` only when the first returns one of the two recognized routing rejections shown below. Otherwise surface the error and stop. Do not look up integrations first.

   A JSON response format is required. For `gpt-transcribe`, optional `prompt`, `keywords[]`, and `languages[]` fields can supply known context, names, and language codes.
   ```
   transcribe() {
     output=$1
     shift
     for base in https://llm.int.exe.xyz https://openai.int.exe.xyz; do
       : > "$output"
       if status=$(curl -sS --fail-with-body -w '%{http_code}' "$base/v1/audio/transcriptions" "$@" -o "$output"); then
         return
       fi
       if { [ "$status" = 400 ] && grep -Fq 'ChatGPT subscriptions do not support transcription; use an LLM integration with managed OpenAI or BYOK' "$output"; } ||
          { [ "$status" = 403 ] && grep -Fq 'integration not found or not attached to this VM (trace: ' "$output"; }; then
         continue
       fi
       cat "$output" >&2
       return 1
     done
     cat "$output" >&2
     return 1
   }
   transcribe "$tmpdir/response.json" -F model=gpt-transcribe -F response_format=json -F "file=@$upload"
   jq -er '.text | select(type == "string")' "$tmpdir/response.json" > "$out"
   ```

   When the user asks for word or segment timestamps, run the GPT command
   above and the Whisper command below as two parallel bash tool calls in one
   response; each call defines `transcribe` and its own variables. The GPT
   transcript stays canonical; Whisper's verbose JSON supplies timing only.
   Whisper accepts `prompt` and singular `language`, not the `gpt-transcribe`
   keyword and language arrays.
   ```
   timestamp_out="${out%.transcript.txt}.timestamps.json"
   transcribe "$timestamp_out" -F model=whisper-1 -F response_format=verbose_json \
     -F 'timestamp_granularities[]=word' -F 'timestamp_granularities[]=segment' \
     -F "file=@$upload"
   jq -e '(.words | type == "array") and (.segments | type == "array")' "$timestamp_out" >/dev/null
   ```

4. Report the output paths and stop.

## Errors

- `402`: LLM credits exhausted; https://exe.dev/user/shelley.
- Transcription requires managed OpenAI or OpenAI BYOK; ChatGPT subscriptions return `400` on this path.
