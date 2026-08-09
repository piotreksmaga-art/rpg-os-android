# RPG OS — PHASE 3 PLAYER STATE CONTRACT

Status: COMPLETE

## Cel
Faza 3 miała usunąć heurystyczną tożsamość gracza i wprowadzić jeden kanoniczny kontrakt Player State rozdzielający dane na PERSISTENT, DERIVED i RUNTIME, bez przedwczesnego wdrażania dynamicznych definicji statystyk ani DerivedValueResolver.

## Wdrożona tożsamość gracza
Dodano `ActivePlayerRef(campaignId, playerUid)` oraz `ActivePlayerStore`.

Każda kampania posiada własny persisted rekord w `active_player_ref`. `campaignId` jest częścią tożsamości, więc taki sam legacy `playerUid` w dwóch kampaniach nie oznacza wspólnego stanu.

`ActivePlayerStore.set()` jest jedyną kontrolowaną metodą trwałej zmiany aktywnego gracza w tej warstwie. `CampaignRepository` udostępnia `activePlayerRef()` oraz `setActivePlayer(playerUid)`.

## Migracja legacy
Migracja `RPGOS-3.0-PLAYER-STATE` jest addytywna i tworzy tabelę `active_player_ref` bez niszczenia istniejących danych.

Dla starej kampanii bez jawnego Player UID wykonywany jest jednorazowy deterministyczny seed z istniejących tabel. Po zapisaniu UID normalny runtime nie używa już heurystyki do wyboru postaci.

Kolejność legacy seed pozostaje świadomie zgodna z wcześniejszym zachowaniem, ale jest deterministyczna przez dodatkowe sortowanie UID. Migracja nie tworzy nowej postaci ani nie zmienia istniejących UID.

## Player State Contract
Dodano:
- `PlayerStateClass.PERSISTENT`
- `PlayerStateClass.DERIVED`
- `PlayerStateClass.RUNTIME`
- `PlayerStateSnapshot`
- `PlayerStatePolicy`
- `PlayerStateStore`

`PlayerStateStore` składa stan wyłącznie dla `ActivePlayerRef` z istniejących tabel kampanii.

PERSISTENT obejmuje istniejące trwałe dane, m.in. legacy status trwały, `character_stats`, `character_skills`, `character_techniques`, finance state, memberships i goals.

RUNTIME obejmuje bieżącą pozycję, aktywne obrażenia oraz legacy pola typu current resource/fatigue/cooldown/pain/bleeding.

DERIVED zawiera tylko wartości, które istniejący schemat już jawnie opisuje jako derived/effective/max/regeneration/net-worth/combat-rating. Faza 3 NIE oblicza nowych derived values. To pozostaje odpowiedzialnością późniejszego `DerivedValueResolver`.

## Integracja repository/runtime
`CampaignRepository` udostępnia teraz:
- `activePlayerRef()`
- `setActivePlayer(playerUid)`
- `playerState()`

`UnifiedGameRepository` prowadzi te operacje przez `LocalGameStore` i `ActivePlayerStore`.

`LocalGameStore.buildContext()` dołącza do `ContextBundle` osobną sekcję `player_state` oraz metadata `active_player_uid`.

`JsonCodec` serializuje `player_state` jako osobną strukturę, więc AI nie musi rekonstruować podziału stanu z płaskiego `player_status`.

Backend GM otrzymał jawne zasady:
- `player_state.active_player` identyfikuje kontrolowaną postać,
- `persistent` jest trwałym authoritative character data,
- `runtime` jest stanem chwilowym,
- `derived` jest read-only/rebuildable,
- tymczasowy runtime penalty nie może być interpretowany jako trwała regresja.

## Usunięte rozbieżności readerów
`ContextBuilder` korzysta z persisted `ActivePlayerStore`, a nie z `resolvePlayerUid()`.

`CharacterPanelReader` jest filtrowany po aktywnym Player UID tam, gdzie legacy tabela posiada `entity_uid`.

`LocalGameStore.status()` pobiera lokalizację tylko dla aktywnego gracza, zamiast pierwszego rekordu `entity_positions`.

CharacterPanelSnapshot pozostaje legacy presentation/read model v1 i NIE stał się Source of Truth. Jego pełna przebudowa pozostaje Fazą 24/25.

## Testy
`PlayerStatePolicyTest` sprawdza:
- wymagane `campaignId`,
- wymagane `playerUid`,
- klasyfikację runtime current resources,
- klasyfikację derived/effective values,
- domyślną klasyfikację durable fields jako persistent,
- zachowanie trzech osobnych sekcji w projekcji do ContextBundle.

Istniejące Active Campaign i Campaign Truth tests nadal przechodzą.

## CI
Pierwszy blok Fazy 3 (`ActivePlayerRef`, migracja, filtering, tests) przeszedł JVM tests i signed APK w Build #90.

Pełny PlayerStateStore + repository/context integration przeszedł JVM tests i signed APK w Build #98.

Końcowy kontrakt kontrolowanej zmiany aktywnego gracza przeszedł JVM tests i signed APK w Build #103.

## Ważny incydent kontroli zakresu
Podczas edycji backend prompt kontrola diffu wykryła przypadkowe nadpisanie dalszej części `backend/app.py`. Zmiana została natychmiast odwrócona przed zamknięciem fazy. Porównanie z baseline potwierdziło, że finalnie backend różni się w tym zakresie wyłącznie dwiema zamierzonymi liniami promptu dotyczącymi `player_state`.

To potwierdza działanie zasady repository-first / small safe change / inspect diff before declaring DONE.

## Czego Faza 3 celowo NIE robi
Faza 3 nie implementuje:
- `StatDefinition` / `ResourceDefinition`,
- base/permanent/equipment/injury/temp modifier model,
- `DerivedValueResolver`,
- Talent/Potential,
- PlayerCommand / PlayerChangeSet,
- PlayerDomainEngine,
- ProgressionEngine,
- CharacterPanelSnapshot v2.

Te elementy pozostają kolejnymi zależnościami roadmapy.

## Definicja DONE
Faza 3 jest COMPLETE, ponieważ:
- istnieje jawny persisted ActivePlayerRef per campaign,
- stara kampania jest seedowana addytywnie bez tworzenia nowej postaci,
- runtime nie zgaduje już Player UID przy każdej turze,
- istnieje formalny Persistent/Derived/Runtime contract,
- istnieje PlayerStateStore składający dane aktywnej postaci,
- repository udostępnia odczyt i kontrolowaną zmianę aktywnego gracza,
- ContextBundle i backend używają nowego kontraktu,
- legacy CharacterPanel/Status są filtrowane według tego samego UID,
- testy JVM przechodzą,
- signed release build przechodzi,
- nie wprowadzono równoległej bazy Player State,
- nie zmieniono istniejących danych postaci poza addytywną migracją tożsamości.

Następna najwcześniejsza zależność: **4. Dynamic StatDefinition/PlayerStat + ResourceDefinition/PlayerResource**.
