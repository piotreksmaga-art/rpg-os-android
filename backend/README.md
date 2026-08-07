# RPG OS Backend v0.3

Run locally:

1. create virtualenv
2. install `requirements.txt`
3. set `OPENAI_API_KEY`
4. `uvicorn app:app --reload --port 8000`

The backend uses OpenAI Structured Outputs and returns a strict RPG OS turn JSON object.

Never put `OPENAI_API_KEY` into the Android project.

## Image generation
`POST /v1/images/generate`

The backend uses the OpenAI Images API and returns PNG data as base64.
The Android client saves it to the system gallery under `Pictures/RPG OS`.
