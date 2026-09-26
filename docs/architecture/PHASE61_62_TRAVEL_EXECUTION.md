# Phase61–62 R1 — rozstrzygnięcie podróży i testy lifecycle

Status: **KANDYDAT, NIE COMPLETE**. Bazowy kod audytu: `754b333fa2235741cf6736d0e0d9cf4346bff3e8`.

## Rozpoznany brak

W bazie `NpcTravelRoutePort` trafiał tylko do affordances. Ogólny resolver `LOCATION_TRANSITION` produkował zmianę lokacji bez ponownego odczytu kontraktu trasy i bez rozliczenia `resourceCosts`. Zielony test samego kształtu kontraktu nie dowodził wykonania podróży.

## Wdrożone w tym pakiecie

`NpcMechanicalActionApplication` ma osobną, fail-closed ścieżkę podróży. Wymaga `NpcTravelRoutePort` oraz `NpcTravelActorReadPort`; nie przekazuje niezweryfikowanej podróży do ogólnego resolvera ruchu. Dotychczasowy konstruktor z dwoma argumentami pozostaje zgodny źródłowo, ale odmawia podróży bez readera. Inne rodzaje czynności zachowują poprzedni resolver.

`NpcTravelMechanics` sprawdza autoryzację konkretnej opcji, kampanię i aktora, aktualną trasę, origin/destination, capability, fingerprint kontraktu, materializację, przytomność, HEALTH oraz wszystkie dodatnie koszty zasobów. Uwzględnia wydatki z efektów staged. Ruch tego aktora w tej samej turze unieważnia stary origin; nie zgadujemy nowego punktu startowego.

Koszty każdego zasobu mają osobne verified effects. `LOCATION_TRANSITION` nadal przechodzi przez `MechanicalEffectMaterializer` do `SpatialChange`. Nie powstaje nowy writer pozycji, zasobów ani osobna baza podróży.

Wersjonowana polityka `COMPLETION_ONLY_V1`: koszt i dotarcie są rozliczane razem dopiero po pomyślnym zakończeniu. Przerwanie nie pobiera kosztu ani nie przyznaje częściowego postępu. To świadomie ograniczona polityka; nie oznacza modelowania etapów drogi lub zużycia zapasów w trakcie marszu. Takie skutki wymagają osobnego kontraktu, nie obejścia Phase60.

Metadata czasu pochodzi z nowego core-owned `P62:TRAVEL_ROUTE_MS_V1`. Oryginalny identyfikator reguły świata pozostaje w provenance. Preflight niczego nie zapisuje; completion ponownie odczytuje stan. Phase60 nadal posiada termin i pending action.

Fingerprint trasy v2 koduje pola strukturalnie, obejmuje kampanię, koszty i eligibility. Rejestr nie udostępnia mutowalnej mapy kosztów. Identyfikator opcji obejmuje również cel NPC. Zmiana tego kontraktu unieważnia stare opcje kandydata R1; nie modyfikuje eventów ani wcześniej zatwierdzonego świata.

## Dodane dowody do wykonania w CI

- `Phase62NpcTravelLifecycleTest`: rzeczywisty decision/context pipeline, nowy resolver i stary Phase50 materializer; koszty dwóch zasobów; preflight bez mutacji; pending-action codec; przedwczesny termin; ponowne zbudowanie aplikacji i wznowienie bez kolejnego wywołania AI; usunięcie drogi; zmiana kontraktu bez podbicia wersji; utrata wiedzy; utrata wykonalności; zmiana origin; staged wydatki/ruch; anulowanie i porzucona historia; zakaz fallbacku.
- `Phase62NpcTravelPersistenceTest`: zapis rzeczywiście rozstrzygniętych efektów przez wspólny `TurnTransaction`, idempotentny retry, rollback między zapisami, ponowne otwarcie pliku SQLite oraz undo i alternatywna transakcja.
- Rozszerzony `Phase62NpcTravelContractTest`: eligibility, zasoby, zakaz przejęcia gracza, izolacja kampanii i niemutowalny rejestr.

To są dodane testy, nie deklaracja ich zaliczenia. CI musi potwierdzić wynik na konkretnym SHA. Round-trip kodeka i otwarcie SQLite nie zastępują Android process-death acceptance. Dotychczasowy smoke API 28/36 testuje R0, nie pełną podróż R1.

## Otwarte zadania przed odbiorem R1

1. Podłączyć oba nowe porty do produkcyjnego wywołania `NpcMechanicalActionApplication`, nie tylko do menu opcji. Aktualny composition root nadal używa konstruktora dwuargumentowego; podróż pozostaje tam celowo zablokowana bez nowego readera.
2. Zbudować prawidłową projekcję lokacji z canonical spatial ownera. `MechanicalActorStateStore.actor()` w bazowym kodzie umieszcza w `locationRef` referencję `POSITION:<entity>`, a nie `PLACE/LOCATION:<location_uid>`. Nie wolno uznać jej za origin, zamienić na lokację gracza ani naprawiać przez zapis podczas odczytu. Affordances i completion muszą korzystać ze zgodnego, scope-checked odczytu miejsca.
3. Dostarczyć rzeczywiste kontrakty tras z właściciela świata; domyślny katalog pozostaje `NONE`. Rozstrzygnąć autoryzację dostępu konkretnego aktora do trasy, nie tylko wiedzę o destination. Obecny port katalogowy nie jest pełną polityką per-actor dostępu.
4. Zweryfikować plany wieloetapowe, alternatywy, pending action razem z canonical temporal state i Android kill/restart na produkcyjnym composition root.
5. Formalne arrival receipt wolno wydać dopiero na podstawie zatwierdzonego commitu. Verified effects i wynik `LocationReachedCriterion` przed commitem są materiałem transakcji, a nie dowodem, że świat już się zmienił.

Fazy 61–62 i R1 pozostają PARTIAL. Bez zmian zakresu 63–64/72, bez wydania APK i bez wymogu lokalnego telefonu. Integracja AI pozostaje provider-independent.
