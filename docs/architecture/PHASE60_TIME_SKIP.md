# Phase60 — czas czynności i orkiestracja upływu czasu

Stan implementacji: produkcyjny procesor czasu, częściowe skutki, zapis/replay i wznowienie
decyzji w aplikacji. Odbiór na urządzeniach i długie scenariusze są odłożone decyzją właściciela.
Ten dokument zastępuje historyczne opisy kolejnych, częściowych wycinków Phase60.

## Authority i zakres

Core ustala skutki i zatwierdza czas. AI interpretuje czynność i może proponować przedział
czasu, ale nie zapisuje zegara, procesów ani nagród. Jedynym miejscem zapisu pozostaje
`TurnTransaction`. Bekko oraz pamięć nadal są uruchamiane po canonical commit.

Mechanizm jest niezależny od czasowników i World Packa: trening, walka, podróż, rozmowa,
słuchanie, czytanie, praca i nowe rodzaje działań przechodzą ten sam kontrakt. Nie istnieje
przelicznik „wiadomość = jedna minuta”. Pisanie wiadomości i oczekiwanie na model nie
przesuwają świata. Czynności poza światem oraz przyszłe zamiary nie wymagają zmiany zegara.

Phase60 jest orkiestratorem, nie implementacją NPC Brain, gospodarki, rodzin, wojen czy
Living World. Nie zamyka faz 61–70 ani 72. Nie dodaje branchingu, WorkManagera ani naliczania
czasu podczas zamknięcia aplikacji.

## Produkcyjny przepływ

```
intencja i plan Phase43/44
→ autoryzowany kontekst i zweryfikowane skutki mechaniki
→ reguła czasu Core / kontrolowana estymacja
→ plan przedziałów i najbliższych terminów
→ porcjowany procesor oraz właściciele procesów
→ rozliczenie faktycznie wykonanych skutków
→ zwykłe admission PlayerDomainEngine
→ jeden TurnTransaction: skutki + zegar + procesy + raport wykonania
→ receipt, pamięć, readback i narracja
```

`ProductionTemporalMutationAssembler` jest używany w kompozycji Androida. UI i LAB Bridge
korzystają z tej samej ścieżki. `prepareEffects` zachowuje tożsamości węzłów; dopiero po
wykonaniu odcinka `admitEffects` tworzy i pieczętuje normalną komendę mechaniki.
Nie dopisujemy zmian do już zatwierdzonej propozycji.

Przed wykonaniem oraz ponownie pod blokadą commitu sprawdzane są kampania, history generation,
kolejność i fingerprint stanu. Spóźniony rezultat AI nie może zapisać się w nowszej turze.

## Jednostki i reguły czasu

- `WorldTimeTick`: podpisana liczba milisekund czasu świata; obsługuje ujemne dni.
- `ActionDuration`: nieujemny czas trwania.
- Commit order jest kolejnością historii, nie czasem świata.
- Monotoniczny czas hosta ogranicza pracę procesora, nie zmienia świata.
- Phase40 `dueOrder` zachowuje dotychczasowe znaczenie. Nie jest reinterpretowany jako ms.
- W istniejącej walce reguła `P60:COMBAT_TICK_MS_V1` ustanawia 1000 ms na tick
  harmonogramu akcji. Przeliczane są różnice początku/końca faz, nigdy absolutny commit order.
  Momenty IMPACT są zachowane: uderzenie wykonane przed przerwaniem recovery nie znika.
- Pozostałe domeny mogą dostarczać `AcceptedActionTiming`. Brak dedykowanej reguły korzysta
  z ogólnej polityki, nie wymaga listy czasowników.
- AI przekazuje wyłącznie `time_min_ms`, `time_max_ms`, `time_scope`. Kodeki pełny,
  LocalCompact i schema OpenRouter zachowują te dane. Core akceptuje wąski przedział
  o rozpiętości najwyżej 25% minimum, gdy niepewność nie przecina istotnych procesów.
  Brak czasu, nieprawidłowy przedział albo konsekwencyjna niepewność wymagają doprecyzowania.
- Warunek zakończenia „aż…” wymaga właściciela reguły; nieznana przesłanka nie staje się sukcesem.

`DURING` współdzieli przedział z kotwicą. Sekwencje sumują czas; niezależne działania mogą
zachodzić równolegle. Konflikty jawnych slotów wyłącznych są odrzucane. Zablokowany następnik
nie zużywa czasu, lecz faktycznie wykonana nieudana próba poprzedzająca go może go zużyć.

## Skutki odcinka i przerwania

`Phase60SegmentEffects` rozlicza zweryfikowane skutki według rzeczywistego przedziału.
Domyślne skutki są atomowe, na końcu działania. Tylko jawna reguła właściciela może wybrać
`PROPORTIONAL` dla zasobu lub liczbowego tracka. `Phase60Accrual` używa różnic skumulowanych
liczb całkowitych; wynik, także ujemny koszt, nie zależy od podziału na porcje.

Nie skaluje się automatycznie przedmiotów, trafień, przemieszczenia, opowieści NPC ani nagród.
Dotychczasowy dyskretny punkt `TRAINING:GENERAL` za ukończoną próbę pozostaje dyskretny.
Phase60 nie wymyśla stawki XP/min ani nie przyznaje dodatkowego skilla za sam upływ czasu.
Niezależnie od nagrody zapisywany jest faktycznie wykonany czas czynności.

Wielokrotne delty tej samej scalar row są sumowane przed admission, z kontrolą overflow.
`Phase60EffectSettlement` sprawdza, czy admission nie zgubił skutku; przy przerwaniu nie
może dołożyć niewyliczonych efektów końcowych.

`TemporalStateChange` zawiera przyczynę zatrzymania oraz kanoniczny raport:
węzeł, początek, planowany koniec, wykonany czas, COMPLETED / INTERRUPTED / NOT_STARTED.
Raport jest weryfikowany względem faktycznie osiągniętej chwili i zachowywany w replay.
Readback narracji korzysta z zapisanego raportu, nie z wcześniejszej obietnicy AI.

## Procesy i terminy

`WorldProcessOwnerPort` jest deterministyczny i czysty. Dostaje scope, przedział,
własne czynności, należne terminy i poprzedni stan. Zwraca wyłącznie typowane kandydaty,
nowy stan oraz przyszłe terminy oceny. Nie może zwrócić własnej zmiany globalnego zegara.

Procesor odwiedza granice akcji i najbliższe terminy. Każdy zarejestrowany lub zapisany
owner musi uczestniczyć także wtedy, gdy nie ma swojej czynności pierwszoplanowej.
Brak ownera, wersji, adaptera skutku, niewłaściwy termin czy powtórzony UID oznacza
typed failure bez commitu, a nie pozornie udaną symulację.

Właściciel `P60:CONDITION_EXPIRY` jest podłączany dla istniejącego stanu tego procesu.
Ocena wygaszenia jest przypięta do konkretnych UID zastosowań warunku. Ponowne nałożenie
nie może zostać skasowane przez stary deadline. Zmieniony zbiór zastosowań wymaga
rozstrzygnięcia właściciela, zamiast usunięcia wszystkich pasujących stanów.

Rejestracja terminu przez `Phase60ScheduledConditions.schedule` jest czystą operacją
przygotowania temporal state przez resolver Core. Wynik musi wejść do tego samego sealed
changesetu co efekt domeny. Nie jest to publiczny zapis SQL ani pole odpowiedzi modelu.
Obecne bezterminowe warunki pozostają bezterminowe: nie nadano im arbitralnego TTL.
Nowa domena musi jawnie dostarczyć czas życia, adapter i regułę rozstrzygnięcia.

Background effects nie są automatycznie publiczną wiedzą. Zmiany procesów dotyczące
ukrytych/innych aktorów nie trafiają bezpośrednio do player readback. Niezwiązane wygaszenie
u innego aktora samo nie generuje decyzji gracza. Zmiana dotycząca gracza lub celu jego
działania może zakończyć odcinek i oddać mu decyzję.

## Persistence, retry, restart i undo

`phase60_temporal_state` jest autorytatywną tabelą: wersja, zegar, stany ownerów,
przyszłe terminy i transaction UID. Jest w inwentarzu Phase36, mutation guards, digest
i replay/undo. Pusta, nowo dodana tabela nie zmienia dawnych digestów; niepusta jest hashowana.
Stare kalendarze nie są przepisywane podczas odczytu. Projekcja UI/ContextBuilder czyta
nowy zegar, a przy braku temporal row używa legacy. Przy zmianie dnia nie zgaduje sezonu
ani lokalnej nazwy roku; takie nazwy wymagają adaptera kalendarza.

Jedna długa czynność jest jedną turą do końca lub istotnej decyzji. Yield obliczeniowy nie
tworzy commitu. Limit to najwyżej 256 granic i 500 ms kooperacyjnej pracy w porcji, z limitem
całego zadania. Synchroniczny owner musi sam przestrzegać bounded work; nie jest przerywany
w połowie wywołania. Anulowanie przed commitem nie zapisuje czasu ani skutków.

Repozytorium publikuje punkty odtworzenia Undo dopiero po wyjściu z zewnętrznego
`CampaignRuntimeLifecycleLock.withTurn`. Kontrola scope i canonical commit pozostają
razem pod blokadą tury; późniejszy snapshot uzyskuje własny recovery lock. Wykonywanie
administracyjnego odzyskiwania wewnątrz gameplay authority pozostaje zabronione.

Checkpointy w prywatnym `noBackupFilesDir/temporal-checkpoints` są CACHE/REBUILDABLE:
AtomicFile, checksum, format v2, limit 16 MiB, scope i fingerprint specyfikacji.
Nie należą do backupów ani canonical hash. Przy ponownym wywołaniu executor odtwarza
obliczenia z canonical wejścia i reguł; nie ufa zapisanym kandydatom jako prawdzie.
Zmieniona specyfikacja jest odrzucana i usuwana. Receipt zapobiega drugiemu commitowi.

Aplikacja zachowuje także mały marker niedokończonej decyzji gracza w `pending-actions`.
Po śmierci procesu UI proponuje „Wznów niedokończoną turę”. Jest to jawne ponowienie tekstu
przez normalną interpretację, mechanikę i admission, z nową tożsamością próby — nie replay
odpowiedzi AI i nie gwarancja identycznego losowania modelu. Tura niezatwierdzona nie
miała jeszcze canonical skutków. Znane zakończenie/anulowanie usuwa marker.

Po commicie obowiązuje odrębny receipt-backed recovery narracji: nie powtarza mechaniki.
Zmiana generacji, digestu, kolejności albo znaleziony receipt wyłącza ofertę ponowienia.
Undo/restore czyści marker i checkpointy danej kampanii, pozostawiając ręczne backupy
oraz dane innych kampanii. Stare zadanie nadal nie przejdzie kontroli scope przy commicie.

LAB `GET_TURN_STATE` pokazuje zegar, wersję, generację, liczbę procesów/terminów oraz
niedokończony tekst. Wznowienie przez bridge używa zwykłej komendy wysłania tego tekstu.
Nie dodano osobnej ścieżki mutacji ani publicznego serwera do release APK.

## Weryfikacja i granice evidence

Dotychczasowe wycinki: 39 testów Phase60 GREEN dnia 2026-09-13.
Rozszerzona integracja 2026-09-14: 50 testów Phase60, 19 testów Phase48–54 RepairPlan
i 5 testów schematu OpenRouter — 74/74 GREEN. Wśród nich jest rzeczywisty
`ProductionTemporalMutationAssembler → PlayerDomainEngine → TurnTransaction`,
częściowy trening, koszt, przerwanie po 20%, retry i canonical clock.

Sprawdzono także: checkpoint corruption/replay, zasadę CACHE != authority, warunki
blocked/after-success, współbieżność, moment trafienia, wygaszenie przypięte do zastosowania,
coalescing, stop/report codec, stare payloady bez czasu, rollback, migrację pustego zegara,
undo przywracające stan/terminy i alternatywną turę.

Końcowy przebieg 2026-09-14: **85/85 testów GREEN** (61 przypadków Phase60, 19 mechaniki,
5 schematu OpenRouter), kompilacja debug i labDebug GREEN. Ostatni przebieg trwał 1 min 1 s
z kompilacją testów; same testy około 16 s. Markery wznowienia sprawdzono na API 28 i 34.
W testach plików na Windowsie tylko prywatny prymityw rename AtomicFile jest zastąpiony
prawdziwym atomic replace odpowiadającym semantyce POSIX Androida. Bez tego hostowy
File.renameTo pozostawiał .new obok starego pliku. Kod AtomicFile aplikacji nie został
zmieniony; test nadal wykonuje rzeczywiste odczyty, zapisy, nadpisanie i czyszczenie plików.
Nie wykonywano realnej inferencji modeli, emulatora, testu na Motoroli, macierzy API 28–36,
100 tur ani testów wydajności. Jest to evidence JVM/Robolectric oraz kompilacja, nie
potwierdzenie jakości AI lub pełnej rozgrywki na telefonie. Nie opublikowano APK Phase60.
