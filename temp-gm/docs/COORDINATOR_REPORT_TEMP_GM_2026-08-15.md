# Raport dla koordynatora — CHAT-7 / TEMP-GM

Data: 2026-08-15
Status: profil testowego MG ustalony i udokumentowany
Gałąź robocza: `chat7-temp-gm-benchmark`

## 1. Cel prac

Celem serii testów było dobranie stabilnego lokalnego modelu TEMP-GM dla RPG OS na urządzeniu Android z wykorzystaniem `llama.cpp` i backendu Vulkan. Priorytetami były: stabilność, sensowna prędkość generowania, możliwie duży natywny kontekst oraz przewidywalne zużycie RAM.

## 2. Model docelowy

Po serii benchmarków wybrano:

`Bielik 4.5B v3 Q4_K_M + Vulkan + CTX=8192 + KV=f16 + -b 64 -ub 64 + -np 1`

W testach używany był pełny offload Vulkan (`-ngl 99`) oraz `GGML_VK_DISABLE_OCP_FP4=1`.

## 3. Najważniejsze wyniki

### CTX=8192 + KV=f16

Profil przeszedł pełny benchmark 15/15 tur bez OOM i bez truncation. Średnia generacji wyniosła około 8.74 tok/s, mediana około 8.91 tok/s, a mediana czasu tury około 4.14 s. Profil został uznany za najlepszy balans jakości, pamięci i wydajności.

### CTX=8192 + KV=f32

Wariant również przeszedł 15/15 tur, ale był wolniejszy i bardziej obciążał RAM. Zmierzono:

- GEN_MEAN: 8.524 tok/s
- GEN_MEDIAN: 8.561 tok/s
- TOTAL_MEDIAN_MS: 4159 ms
- dostępny RAM po załadowaniu modelu: około 588 MiB
- dostępny RAM po teście przy działającym serwerze: około 763 MiB

Wniosek: `f32` nie uzasadnia dodatkowego kosztu RAM i spadku TPS. Zachowano go wyłącznie jako wariant eksperymentalny.

### Próba CTX=12288

`llama.cpp` zgłosił, że model ma natywny kontekst treningowy 8192:

`n_ctx_seq (12288) > n_ctx_train (8192)`

Następnie slot został automatycznie ograniczony do:

`n_ctx_slot = 8192`

Wniosek: dla tego modelu 8192 jest natywnym praktycznym maksimum bez osobnego eksperymentu z RoPE/YaRN lub innym skalowaniem kontekstu.

## 4. Testy KV

Przetestowano co najmniej:

- KV `q8_0` — stabilne, ale brak przewagi nad f16 dla docelowego profilu;
- KV `f16` — najlepszy kompromis;
- KV `f32` — stabilne, ale wolniejsze i cięższe pamięciowo.

Docelowa decyzja: **KV=f16**.

## 5. Test większego modelu

Przetestowano próbę uruchomienia `Bielik-Minitron-7B-v3.0-Instruct Q4_K_M` na Vulkanie. Model powodował ubijanie Termuxa / niepraktyczną presję pamięciową. Próby z większą pamięcią wirtualną nie rozwiązały problemu w sposób akceptowalny. Model 7B został usunięty z telefonu i nie jest kandydatem produkcyjnym dla tego urządzenia.

## 6. Pamięć i profil urządzenia

Dla Bielika 4.5B przy CTX=8192 + KV=f16 system pozostawał stabilny. Dla KV=f32 margines pamięci spadł do około 588 MiB `available` po załadowaniu modelu, co dodatkowo przemawia za f16.

Szacowany koszt samego KV cache dla 4.5B przy f16 i CTX=8192 wynosi około 480 MiB. Podwojenie precyzji do f32 zwiększa koszt KV mniej więcej dwukrotnie.

## 7. Decyzja końcowa

Zatwierdzony testowy TEMP-GM RPG OS:

```text
Model: Bielik 4.5B v3 Q4_K_M
Backend: Vulkan / llama.cpp
CTX: 8192
KV K: f16
KV V: f16
Batch: 64
Ubatch: 64
Parallel slots: 1
NGL: 99
```

Parametry uruchomieniowe:

```text
-c 8192
-ctk f16
-ctv f16
-b 64
-ub 64
-np 1
-ngl 99
```

Profilu nie należy zmieniać bez nowego benchmarku porównawczego i zapisania evidence w repozytorium.

## 8. Evidence i dokumentacja

Finalny profil jest zapisany w:

`temp-gm/docs/BIELIK_4.5B_V3_FINAL_TEMP_GM_PROFILE.md`

Surowe wyniki testów znajdują się pod:

`temp-gm/results/device/`

Kluczowe evidence obejmuje między innymi testy:

- CTX=8192 + KV=f16,
- CTX=8192 + KV=f32,
- próbę CTX=12288 z automatycznym capem do 8192.

## 9. Rekomendacja dla koordynatora

Traktować powyższy profil jako aktualny referencyjny lokalny TEMP-GM dla dalszej integracji RPG OS. Następny etap powinien dotyczyć integracji tego profilu z warstwą pamięci długoterminowej RPG OS, Save/Chronicle oraz bridge/providerem, a nie dalszego zwiększania parametrów modelu na tym urządzeniu.
