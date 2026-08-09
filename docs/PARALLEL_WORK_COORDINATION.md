# RPG OS — PARALLEL WORK COORDINATION

Status: CANONICAL OPERATIONAL PROTOCOL

Ten dokument definiuje sposób równoległej pracy wielu sesji/chatów nad jednym repozytorium RPG OS.

## 1. Model pracy

Jedna sesja pełni rolę COORDINATOR. Pozostałe sesje są WORKERS realizującymi jawnie przydzielone, możliwie rozłączne zadania.

Repozytorium `master` pozostaje wspólnym technicznym źródłem prawdy. Dokument MASTER i roadmapa definiują architekturę i kolejność zależności.

Koordynator nie zakłada, że posiada bieżący stan innych sesji. Stan pracy potwierdza przez repozytorium, commity, CI oraz jawne raporty.

## 2. Role

### COORDINATOR
Odpowiada za:
- odczyt MASTER, roadmapy i tego dokumentu,
- sprawdzanie aktualnego master i CI,
- dzielenie pracy na niezależne WORK ITEMS,
- kontrolę zależności,
- przydzielanie zakresów i ownership plików/subsystemów,
- wykrywanie konfliktów pomiędzy workerami,
- kontrolę statusów i dowodów DONE,
- decyzję, które zadanie może rozpocząć się następne,
- audyt wyników przed uznaniem fazy za COMPLETE,
- aktualizację roadmapy i rejestru koordynacji.

### WORKER
Odpowiada za:
- pracę wyłącznie w przydzielonym zakresie,
- ponowne sprawdzenie master przed rozpoczęciem implementacji,
- niewchodzenie w pliki/subsystemy zarezerwowane dla innego aktywnego WORK ITEM,
- małe, kontrolowane zmiany,
- testy, build i CI odpowiednie do zakresu,
- raport zawierający commit SHA, zmienione pliki, testy, CI i otwarte problemy,
- zatrzymanie pracy i zgłoszenie konfliktu, jeśli zadanie wymaga naruszenia cudzej rezerwacji.

## 3. Work Item

Każde równoległe zadanie otrzymuje stabilny identyfikator:

`WORK-YYYYMMDD-NNN`

Minimalny rekord:

- workId
- title
- ownerRole / worker label
- phase
- status
- objective
- allowedScope
- reservedFilesOrSubsystems
- forbiddenScope
- dependencies
- baselineCommit
- resultCommit
- ciStatus
- startedAt
- completedAt
- notes / blockers

Dozwolone statusy:

`PLANNED | READY | ACTIVE | BLOCKED | REVIEW | COMPLETE | CANCELLED`

## 4. Zasada rezerwacji zakresu

Dwa aktywne WORK ITEMS nie mogą równocześnie modyfikować tego samego authoritative subsystemu lub tych samych plików bez jawnej zgody koordynatora.

Rezerwacja może dotyczyć:
- konkretnych plików,
- katalogu,
- migracji/schema,
- repository API,
- subsystemu domenowego,
- dokumentu kanonicznego.

Jeżeli zakresy zaczynają się nakładać, drugi worker zatrzymuje implementację i zgłasza konflikt.

## 5. Baseline freshness

Przed rozpoczęciem kodowania worker musi sprawdzić aktualny `master`.

Jeżeli `master` zmienił się od `baselineCommit`:
1. sprawdź nowe commity,
2. oceń wpływ na przydzielony zakres,
3. zaktualizuj baseline,
4. dopiero potem kontynuuj.

Nie implementujemy na założeniu, że stan sprzed kilku commitów nadal jest aktualny.

## 6. Zależności i równoległość

Równolegle wolno wykonywać tylko zadania, których zależności na to pozwalają.

Przykład poprawny:
- Worker A: hardening istniejącego Player State,
- Worker B: read-only audyt przyszłej Fazy 4,
- Worker C: niezależny zestaw testów/integrity, o ile nie edytuje plików A.

Przykład zabroniony bez koordynacji:
- Worker A zmienia `CampaignRepository`,
- Worker B równocześnie zmienia ten sam kontrakt dla innej fazy.

Najwcześniejsza brakująca zależność nadal ma pierwszeństwo nad numerem roadmapy.

## 7. Zasady zapisu do master

- Nie resetuj master.
- Nie force-pushuj historii bez jawnego polecenia użytkownika.
- Nie usuwaj cudzych poprawnych zmian.
- Nie rozwiązuj konfliktu przez ślepe nadpisanie całego pliku.
- Przed zapisem ponownie sprawdź aktualną wersję modyfikowanego pliku.
- Jeżeli plik zmienił się od czasu odczytu, wykonaj ponowną analizę/merge zamiast nadpisania.
- Każdy commit powinien odpowiadać jednemu logicznemu zakresowi.

## 8. Dokumenty kanoniczne

`RPG_OS_MASTER_ARCHITECTURE.md`, `RPG_OS_IMPLEMENTATION_ROADMAP.md` oraz `PARALLEL_WORK_COORDINATION.md` są obszarem koordynowanym.

Worker nie zmienia statusu całej fazy na COMPLETE bez dowodów i bez sprawdzenia, czy inne WORK ITEMS tej fazy są zakończone.

Koordynator jest preferowanym właścicielem aktualizacji globalnych statusów roadmapy podczas pracy równoległej.

## 9. Definition of Done dla Work Item

WORK ITEM może otrzymać COMPLETE tylko gdy:
- przydzielony zakres został faktycznie wykonany,
- integracja działa,
- wymagane testy przeszły,
- build/CI jest zielony lub jawnie udokumentowano niezależną awarię baseline,
- nie pozostał nierozwiązany konflikt z równoległą pracą,
- resultCommit jest znany,
- raport końcowy został przekazany koordynatorowi.

COMPLETE pojedynczego WORK ITEM nie oznacza automatycznie COMPLETE całej fazy.

## 10. Raport Workera

Każdy worker raportuje:
1. workId,
2. baselineCommit,
3. resultCommit,
4. co istniało przed zmianą,
5. co zmieniono,
6. listę zmienionych plików,
7. migracje/schema changes,
8. testy,
9. build/CI,
10. wpływ na istniejące kampanie,
11. wykryte konflikty lub ryzyka,
12. otwarte TODO,
13. czy zakres wymaga kolejnego WORK ITEM.

## 11. Konflikt i blokada

Jeżeli worker wykryje:
- równoległą zmianę tego samego pliku,
- zmianę kontraktu zależności,
- nową migrację kolidującą z jego migracją,
- zmianę master unieważniającą jego założenia,
- konieczność wejścia w forbiddenScope,

to ustawia zadanie jako BLOCKED i nie wykonuje agresywnego merge/resetu.

Koordynator wybiera kolejność integracji albo redefiniuje zakres.

## 12. Audyty read-only

Koordynator może przydzielać równoległe audyty przyszłych faz. Audyt read-only:
- może czytać cały repozytorium,
- nie może implementować przyszłej fazy,
- nie rezerwuje plików do zapisu, chyba że tworzy wyłącznie własny raport audytowy,
- nie może zmienić authoritative state ani statusu fazy bez koordynacji.

Pozwala to przygotować kolejne zadania bez łamania zależności roadmapy.

## 13. Rejestr aktywnej pracy

Bieżący rejestr znajduje się w sekcji `ACTIVE WORK REGISTER` tego dokumentu. Koordynator aktualizuje go przy rozpoczęciu, blokadzie, review i zakończeniu zadania.

### ACTIVE WORK REGISTER

| Work ID | Owner | Phase | Status | Scope | Reserved subsystem/files | Baseline | Result | CI |
|---|---|---:|---|---|---|---|---|---|
| — | — | — | — | Brak zarejestrowanych zadań po utworzeniu protokołu | — | — | — | — |

## 14. Protokół startu workera

Każda nowa sesja wykonawcza:
1. przeczytaj `docs/RPG_OS_MASTER_ARCHITECTURE.md`,
2. przeczytaj `docs/RPG_OS_IMPLEMENTATION_ROADMAP.md`,
3. przeczytaj `docs/PARALLEL_WORK_COORDINATION.md`,
4. odczytaj swój WORK ITEM,
5. sprawdź aktualny master i baseline,
6. sprawdź rezerwacje innych aktywnych zadań,
7. sprawdź ostatnie commity i CI,
8. potwierdź zależności,
9. pracuj tylko w allowedScope,
10. przed zapisem ponownie sprawdź master/plik,
11. testuj i raportuj wynik.

## 15. Protokół koordynatora

Przed przydzieleniem pracy:
1. sprawdź master i CI,
2. sprawdź roadmapę i otwarte audyty,
3. znajdź najwcześniejszą brakującą zależność,
4. rozbij ją na możliwie niezależne zadania,
5. wykryj nakładające się pliki/subsystemy,
6. nadaj WORK IDs,
7. określ allowed/forbidden scope,
8. zapisz baseline,
9. dopiero wtedy uruchom równoległą pracę.

Po wynikach workerów:
1. sprawdź result commits,
2. sprawdź diffy i CI,
3. sprawdź konflikty między wynikami,
4. wykonaj audyt integracyjny,
5. zaktualizuj statusy,
6. dopiero po spełnieniu globalnej Definition of Done oznacz fazę COMPLETE.

## 16. Zasada nadrzędna

Równoległość służy skróceniu czasu pracy, ale nigdy kosztem integralności kampanii lub repozytorium.

W razie konfliktu priorytet jest następujący:

`DATA INTEGRITY > CAMPAIGN CONTINUITY > CORRECT ARCHITECTURE > SAFE INTEGRATION > PARALLEL SPEED`.

Jeżeli bezpieczna równoległość nie jest możliwa, zadania wykonujemy sekwencyjnie.
