# Vulkan/Xclipse: krytyczne odkrycie `-b 64 -ub 64`

Data: 2026-08-15
Urządzenie: Samsung SM-S921B
Platforma: Exynos 2400 / Xclipse, Android 16, Termux
Backend: `llama.cpp` Vulkan
Model potwierdzający: Bielik-4.5B-v3.0-Instruct Q4_K_M
Gałąź evidence: `chat7-temp-gm-benchmark`

## Wniosek

Na tym urządzeniu problem bardzo wolnego prompt processing przy Vulkanie nie wynikał przede wszystkim z długości całego kontekstu ani z braku prompt cache. Krytyczne znaczenie miał rozmiar batch/ubatch.

Profil z domyślnymi większymi batchami dawał typowo około 1-1.5 prompt tok/s dla części tur wieloturowego RPG, mimo że generacja pozostawała blisko 9-10 tok/s. Jedna tura z małą porcją promptu była szybka (~56-62 prompt tok/s), co wskazało próg związany z rozmiarem przetwarzanej porcji.

Po wymuszeniu:

```text
-b 64
-ub 64
```

prompt processing przy Vulkan/Xclipse przyspieszył do około 55-65 tok/s w 3-turowym teście diagnostycznym, bez utraty stabilności generacji.

## Najważniejsze evidence

### Przed batch64

Test: `bielik45-slot0-cache-rpg6`

- prompt TPS: 1.506, 56.257, 1.442, 1.220, 1.117, 1.093
- generation TPS: około 8.8-9.3
- typowy czas tury: około 70 s
- `cache_prompt=true` działał; cached tokens rosły do 469
- ręczne `id_slot=0` nie rozwiązało problemu

Commit evidence:
`239d4c3037bff20971a77bcd1fe16899a39e0bb4`

### Po batch64

Test: `bielik45-vulkan-batch64-diagnostic`

Parametry:

```text
-ngl 99
-c 1024
-np 1
-b 64
-ub 64
id_slot=0
cache_prompt=true
```

Wyniki 3 tur:

| Tura | prompt tokens | cached | prompt_n | prompt tok/s | gen tok/s | total ms |
|---|---:|---:|---:|---:|---:|---:|
| 1 | 97 | 0 | 97 | 63.419 | 8.829 | 7787 |
| 2 | 177 | 109 | 68 | 55.733 | 8.947 | 5169 |
| 3 | 239 | 182 | 57 | 65.457 | 8.937 | 4823 |

Commit evidence:
`a9be2ee53c0b602b70794579536a840dff0dd080`

## Znaczenie dla RPG OS

Dla lokalnego TEMP-GM na tym telefonie `-b 64 -ub 64` należy traktować jako kluczowy profil startowy dla `llama.cpp` Vulkan/Xclipse, dopóki dalsze benchmarki nie wykażą lepszej wartości.

Nie należy zakładać, że większy batch przyspiesza prefill na tym GPU. Na tej konfiguracji większy batch uruchamiał skrajnie wolną ścieżkę prompt processing.

## Następny test

Powtórzyć dokładnie 6-turowy benchmark używany wcześniej dla Qwen, ale dla Bielik-4.5B-v3.0-Instruct Q4_K_M i z zachowaniem `-b 64 -ub 64`. Porównać:

- prompt TPS per tura,
- generation TPS per tura,
- cached tokens,
- całkowity czas tury,
- pamięć RAM/swap,
- stabilność 6 kolejnych tur,
- jakość i ciągłość odpowiedzi RPG.
