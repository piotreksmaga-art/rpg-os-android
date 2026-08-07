# RPG OS Visual Generator v1

Kinds:
- scene
- location
- character

Scene:
Uses the current ContextBundle so time, scene state and relevant NPCs can influence the prompt.

Character:
Uses explicit traits, equipment and continuity notes.

Location:
Uses location description and current campaign era.

Storage:
Generated PNG files are saved to Android MediaStore:
`Pictures/RPG OS`

Security:
The Android application never calls OpenAI directly and never stores the OpenAI API key.
