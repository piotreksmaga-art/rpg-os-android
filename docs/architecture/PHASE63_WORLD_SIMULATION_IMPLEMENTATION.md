# Phase63 — świat, materializacja i LOD

Stan: **implementacja i krótki odbiór Motoroli zakończone; końcowe CI i wydanie oczekują**, gałąź `codex/phase63-complete`, baza
ALPHA21 `9bdc44c8d71b3e41566d69a240e5534cb44141d9`. Dokument nie stanowi
zgody na merge ani potwierdzenia końcowych bramek wydania.

## Właściciele danych

| Dane | Właściciel / klasyfikacja |
| --- | --- |
| Tożsamość, nazwa, właściwości i relacje elementu | canonical truth records |
| Ziarno, wersjonowane reguły, połączenia, manifest i lineage populacji | WORLD_SIMULATION / AUTHORITATIVE |
| Zasoby, obrażenia, wyposażenie, anonimowa liczebność | Phase50 oraz istniejący właściciel danego komponentu |
| Czas, rozpoczęte procesy i terminy | Phase60 |
| Wiedza konkretnej osoby | Phase37; istnienie nie oznacza znajomości |
| Wybór LOD, projekcje Bekko, checkpoint obliczeń | CACHE / REBUILDABLE |

`WorldSimulationChange` korzysta ze zwykłego `TurnTransaction`, mutation guards,
Event Store, typed codecs/applier i replay. Puste tabele rozszerzeń nie zmieniają
historycznego digestu. Dane po zapisaniu są uwzględniane w canonical hash.
`HistoryGenerationUid` odrzuca stare zadania; nie zmienia tożsamości ani ziarna świata.
Odczyt starej kampanii przygotowuje tylko kandydat szkieletu. Pierwszy zapis następuje
w legalnej transakcji działania, nie przy odczycie.

## Kampania własnego świata

Kreator i LAB używają tej samej ścieżki `CAMPAIGN_NATIVE`: nazwa, opis, epoka,
miejsce startowe. Powstaje nowa baza neutralnego Core w stagingu, a nie kopia
kampanii Naruto. Weryfikacja i baseline poprzedzają aktywację. Nieudany bootstrap
zachowuje poprzedni wybór kampanii. Nie używamy fikcyjnego World Packa ani unbound
do obejścia authority. Starsze kampanie zachowują źródło i fingerprint reguł v1.

Neutralny katalog nie nadaje chakry, klanów ani magii. Opis nie przyznaje specjalnej
zdolności. Opcjonalny `WORLD_DRAFT/BOOTSTRAP` interpretuje własny tekst gracza;
nie może zmienić podanej nazwy, epoki, opisu ani miejsca. Niedostępny provider
pozostawia uczciwy neutralny/nieznany wariant, bez cichej zmiany modelu.

Jawne rozpoznanie typowego osiedla ziemskiego może wybrać wersjonowany preset
makroregionów. Nie dotyczy nieznanych, kosmicznych, podziemnych czy wykluczających
teren założeń. To ograniczony zarejestrowany preset, **nie pełny generator dowolnej
geografii na podstawie prozy**. World Pack może dostarczyć własne definicje v2 przez
opcjonalną tabelę `phase63_world_generation_definitions`; pusty jawny katalog nie
uruchamia zastępczych wymyślonych reguł. Zapisany szkielet nie jest przepisywany.

## Rozwiązywanie odniesień i podróż

Exact tożsamość i legalny kontekst → structured canonical reads → autoryzowane
kandydatury Bekko z rehydration → stabilny latent slot → opcjonalne źródło zewnętrzne.
Slot wiąże region, otwartą kategorię/rolę i ordinal; SHA-256 oraz oddzielne ziarna
mechaniki, wyglądu i osobowości nie zależą od kolejności pytań ani poziomu gracza.
Wielokrotne odniesienie nie przelosowuje istniejących komponentów. Jawna liczba
osób rozwija tożsamości, nie mnoży samowolnie działań.

Topologia jest grafem połączeń i hierarchią kotwic, nie obowiązkowym silnikiem 3D.
Containment nie jest trasą. Wybór istniejącej instancji używa legalnego czasu/kosztów
trasy i stabilnego UID; nie tworzy bliższego zamiennika. Trasa jest ponownie sprawdzana
na aktualnym stanie aktora. PC i NPC korzystają z tego samego źródła połączeń.
Odległy latentny cel nie nadaje wiedzy o drodze. Brak wiedzy/połączenia zwraca UNKNOWN
lub doprecyzowanie, nie dowód nieistnienia ani teleportację.

Materiał `WORLD_DRAFT/ELEMENT` pozostaje propozycją bez canonical UID, statystyk,
kosztów i wyników. Internetowy Scout wymaga odrębnej zgody (domyślnie wyłączonej),
ma jedno zapytanie na odniesienie, do pięciu kandydatów i wspólny budżet pięciu sekund.
Nie wysyła historii kampanii ani sam nie materializuje wyników.

## Populacja i LOD

- LOD0: agregaty; LOD1: jednostki/formacje; LOD2: wyróżnione osoby; LOD3: interakcja indywidualna i aktywna postać.
- Sloty manifestu są trwałe. Wyodrębnienie odejmuje anonimowego członka i przenosi
  jego rzeczywiste zasoby, wyposażenie, warunki oraz straty atomowo i idempotentnie.
- Zero anonimowych członków jest legalne. Nazwane osoby pozostają nazwane także
  przy uproszczonym przetwarzaniu; nie są ponownie losowane.
- Stary agregat może otrzymać manifest w następnej legalnej transakcji. Przygotowanie
  kandydata nie zapisuje lineage podczas odczytu.
- Wyróżniony NPC ma osobny Phase61 Brain. Decyzje i zakończenie rozpoczętych działań
  pozostają przy Phase62/60. Phase63 nie steruje dobrowolnymi czynnościami PC.
- AOE używa Phase50 z rozłączną anonimową częścią oraz legalnie ujawnionymi named
  members; nie dodaje ich ponownie do anonimowej liczebności i nie liczy obrażeń drugi raz.

Roboczy frontier odczytuje odniesienia interakcji i pending processes, nie całą
populację. Potrzebne uzupełnienia mechaniki są zarejestrowanym ownerem Phase60.
Typed world changes zachowują tożsamość transferu; nie są scalane w tekstowy efekt.
Uzupełnienia do 256 aktorów korzystają z zawieszenia istniejącego procesora Phase60
i porcji do 32. Ponowna próba odtwarza przygotowanie z kanonicznego wejścia, a nie
przyjmuje zawartości checkpointu jako prawdy. Anulowanie lub zmiana generacji
odrzuca całość; nie zatwierdzamy części porcji. Czas hosta nie wyznacza czasu świata.
Zarejestrowane rodzaje pracy tego bloku to uzupełnienie brakującej mechaniki przez
Phase63/50, zakończenie rozpoczętych czynności przez Phase62 oraz terminy warunków
Phase60, także dla agregatów. LOD0–1 nie wywołuje modelu dla anonimowych członków.
Nowe decyzje dotyczą jedynie autoryzowanego frontiera interakcji; brak reguły procesu
nie jest zastępowany dowolną symulacją. Gospodarka, wojny i demografia pozostają
w Phase64, a nie w tym właścicielu LOD.
Zwykłe oglądanie ma neutralną regułę czasu Core, ale nadal nie nadaje wiedzy bez
oddzielnego dowodu percepcji/acquisition. Jawny czas użytkownika pozostaje oddzielny.

Produkcja wymaga poprawnego, zgodnego z kampanią źródła reguł również przy błędzie
odczytu manifestu. Nie uruchamia zastępczego trybu unbound. Niedostępność jest
typowanym błędem przed uruchomieniem dostawcy i przed zmianą zapisu.

## Wyniki oraz pozostałe bramki

Lokalne testy celowane potwierdzają m.in. additive digest, guards, rollback/retry,
stale generation, native staging i neutralny katalog, deterministyczne sloty,
autoryzowane trasy, lineage, konserwację zasobów/wyposażenia i pusty agregat.
Krótki test bez AI obejmuje 10 tys. slotów oraz dużą populację; nie jest benchmarkiem
natywnego inference. Nie zastępuje końcowego CI.

Na oddzielnej instalacji Motoroli/Android14 rzeczywiście przeszły: nowy własny świat,
neutralna postać, lokalna materializacja miejsca i podróż, restart/cofnięcie tej tury,
rozmowa z nauczycielem oraz ponowne spotkanie tej samej osoby. Narracja po awarii
odzyskuje istniejący commit, bez drugiej mechaniki. Odbiór wykonywano ręcznie przez
aktywny czat LAB, nie przez Bielika. Te wyniki nie potwierdzają jego jakości.

Druga nowa kampania urządzenia potwierdziła wydzielenie jednego mieszkańca z grupy
12 osób, zachowanie podziału 11 anonimowych i jednej nazwanej po restarcie, a także
cofnięcie wydzielenia. Alternatywna decyzja wybrała drugi slot, z inną trwałą
tożsamością; cofnięta osoba i jej Brain nie pozostały aktywne. Rozmowa z drugim
mieszkańcem zachowała jego UID, zatwierdziła 30 sekund i dostarczyła narrację bez
naprawy i fallbacku. Następnie LOD diagnostyczny wrócił do agregatu bez anonimizacji
nazwanej osoby.

Po naprawie przycinania opcji do budżetu mobilnego ponowiono krok poznawczy i decyzję
NPC z terminową odpowiedzią ręcznego LAB. Zarejestrowany odpoczynek rozpoczął się
i zakończył po upływie sześciu minut czasu kampanii. Restart zachował ukończenie,
a cofnięcie przywróciło rozpoczętą czynność; alternatywne oczekiwanie przez jedną
minutę nie wypłaciło usuniętego zakończenia. Cel opisowy pozostał aktywny, gdyż
zakończenie planu nie stanowi dowodu osiągnięcia niepowiązanego celu domenowego.
Nie było cichej zmiany providera ani narracji awaryjnej w tych ponowionych krokach.
Wcześniejszy timeout pozostał jawnym wynikiem nieudanego przebiegu, nie zaliczeniem.

Publiczny kreator własnego świata, losowanie neutralnej postaci, osobne zatwierdzenie
i wejście przez listę kampanii do ekranu gry również przeszły na Motoroli. Odległe
morze w górskim presecie zostało kandydatem wymagającym podróży. Brak wiedzy o trasie
zablokował działanie przed commitem: nie zmieniono geografii, pozycji ani czasu.
Ponowienie na poprawionym APK zachowało pierwotny powód `KNOWN_ROUTE_REQUIRED`
również po naprawie propozycji. Lista miejsc zachowuje kotwicę startową i bieżącą
przy ponad 500 wpisach, a status postaci pokazuje publiczną nazwę miejsca.

Wspólny odbiór Android SQLite świata, populacji, rollbacku, retry, podróży,
domen czynności, granic wiedzy NPC, pamięci i cofnięcia przeszedł na urządzeniu:
13 testów w 176,555 sekundy. Dwa oddzielne testy zapisu rozpoczętej podróży,
zatrzymania procesu i wznowienia również przeszły. Starsze regresje produkcyjne stu deterministycznych tur
oraz walki z cofnięciem również przeszły lokalnie. Nie są to sto tur generatywnych
ani dowód jakości inference. We wcześniejszym przebiegu rola Directora była lokalna;
nie zaliczamy tej odpowiedzi jako dowodu pracy aktywnego czatu ani jakości Bielika.

Otwarte przed oznaczeniem DONE i wydaniem:

- JVM debug/lab, API28/36, process-death, memory/undo/NPC i release isolation na finalnym SHA;
- review, PR/merge oraz podpisany exact-SHA APK, checksum i manifest aktualizacji.

Nie włączamy do ukończenia Phase64 (gospodarka, wojny, demografia i globalne
organizacje), faz narracyjnych 65–70 ani branchingu Phase72. Testy 100 tur z AI,
duży Bielik, szerokie A/B i długie benchmarki modeli pozostają odłożone.
Prywatnych transkryptów, promptów i failure bundles nie publikujemy.
