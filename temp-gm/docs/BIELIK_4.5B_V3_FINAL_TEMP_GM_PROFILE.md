# RPG OS — Finalny profil testowego TEMP-GM

Status: **zatwierdzony profil roboczy**
Data: 2026-08-15

## Model

- Model: **Bielik 4.5B v3**
- Kwantyzacja: **Q4_K_M**
- Backend: **Vulkan / llama.cpp**
- Rola: **testowy TEMP-GM dla RPG OS**

## Finalna konfiguracja

```text
Bielik 4.5B v3 Q4_K_M
Backend: Vulkan
CTX: 8192
KV cache K: f16
KV cache V: f16
Batch: 64
Ubatch: 64
Parallel slots: 1
```

Odpowiadające parametry llama.cpp:

```text
-c 8192
-ctk f16
-ctv f16
-b 64
-ub 64
-np 1
```

W testach używany był pełny offload Vulkan (`-ngl 99`) oraz `GGML_VK_DISABLE_OCP_FP4=1`.

## Uzasadnienie wyboru

Profil CTX=8192 + KV=f16 przeszedł pełny 15-turowy benchmark i został wybrany jako profil produkcyjny/testowy TEMP-GM.

CTX=8192 jest natywnym limitem kontekstu badanego modelu. Próba ustawienia CTX=12288 została przez llama.cpp ograniczona do 8192, dlatego zwykłe zwiększanie `-c` ponad 8192 nie zwiększa rzeczywistego kontekstu tego modelu bez osobnych technik skalowania RoPE.

KV=f32 również przeszedł 15/15 tur, ale był wolniejszy i zwiększał presję na RAM. W teście CTX=8192 + KV=f32 uzyskano średnio 8.524 tok/s, medianę 8.561 tok/s, a po załadowaniu modelu pozostawało około 588 MiB dostępnego RAM. Z tego powodu f32 zachowujemy jako udokumentowany wariant eksperymentalny, a f16 jako profil docelowy.

## Decyzja projektowa

**Domyślny testowy MG / TEMP-GM RPG OS:**

`Bielik 4.5B v3 Q4_K_M + Vulkan + CTX=8192 + KV=f16 + -b 64 -ub 64 + -np 1`

Nie zmieniać tego profilu bez nowego benchmarku porównawczego i udokumentowania wyniku w repozytorium.

## Evidence

Repo zawiera surowe raporty benchmarków urządzenia, w tym testy CTX=8192 KV=f16, CTX=8192 KV=f32 oraz próbę CTX=12288. Evidence należy traktować jako podstawę do przyszłych porównań konfiguracji TEMP-GM.
