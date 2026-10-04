# Phase64 i odbiór implementacji

Dokument opisuje wdrożenie procesów świata po ALPHA22. Wersja robocza nie jest
jeszcze ukończoną fazą ani opublikowaną aktualizacją. Starsze kampanie zachowują
dotychczasowe zachowanie Phase60–63.

## Aktywacja i właściciele

Nowa kampania otrzymuje autorytatywną politykę `PHASE64_V1`. Jej brak oznacza brak
nowej symulacji niezależnie od wersji aplikacji. World Pack może importować
wersjonowane `background_process_definitions` z kolumnami `definition_uid`,
`definition_version`, `canonical`. Katalog neutralnych reguł Core nie przyznaje
mechaniki Naruto i sam nie rozpoczyna procesów.

Phase64 posiada definicje, instancje, zależności i dowody. Dotychczasowi właściciele
rozliczają pieniądze, unikalne przedmioty, projekty, populacje, mechanikę i wiedzę.
Zegar pozostaje w Phase60. Własna postać nie podejmuje dobrowolnych działań bez
decyzji gracza.

## Wdrożony fundament

- Additive migracja WORLD_SIMULATION, klasyfikacja i mutation guards.
- Idempotentne wersjonowanie procesu oraz powiązanie typowanych konsekwencji.
- Porządek terminów, zależności, priorytetów i UID; rezerwacje zasobów.
- Zachowanie transferów, pracy projektowej i pozostałych payloadów na ścieżce
  Phase60 do normalnego commitu, zdarzeń i replay.
- Rzeczywisty wykonawca pracy projektu, gotowość i potwierdzony wynik istniejącego
  właściciela; ukończenie wymaga dowodu z wcześniejszego commitu. Zarejestrowana
  praca obciąża rzeczywistą pulę wykonawcy, nie równoległy licznik Phase64.
- Komunikacja jako Phase37 BELIEVED/SUSPECTED, nie nowe FACT.
- Narodziny osobnej kohorty oraz mobilizacja istniejącej jednostki bez duplikacji.
- Stronicowany indeks terminów oraz wznawialne porcje po najwyżej 32 procesy lub
  50 ms pracy; obliczenia nie zatwierdzają części tury. Ograniczenie roboczego
  frontieru wynosi 4096 procesów, a limit pojedynczego polecenia nadal obowiązuje.
- Delegowanie zatwierdzonej decyzji instytucjonalnej do istniejącego wykonawcy
  Phase62, wraz z jego pending state i deadline. Reakcja pozostaje zależna od
  legalnego kontekstu i zarejestrowanych opcji NPC. Produkcyjny adapter używa
  `NpcDecisionApplication` poza odczytem bazy, a następnie ponownie sprawdza zakres,
  fingerprint i warunki przed przygotowaniem legalnego działania.
- Nieposiadany nośnik informacji wymaga istniejącego kontraktu czytania oraz
  rzeczywistej ścieżki dostępu Phase38. Wspólna lokacja lub członkostwo w organizacji
  nie zastępują kanału, disclosure i uprawnień.
- Neutralne, jawnie uruchamiane akcje zużycia własnego przedmiotu, pracy przy
  istniejącym projekcie budowy, naprawy lub badania oraz opóźnionej wiadomości do nazwanego odbiorcy.
  Wiadomość zachowuje kanał, disclosure i Phase37 BELIEVED, nie FACT.
- Dowód zakończenia rzeczywistej walki Phase62/50, bez ponownego wykonywania
  obrażeń. Nowy kontrakt odbioru v2 pozostawia wcześniejszy kontrakt v1 czytelny.
- Odczyty dostępności i uprawnień z uwzględnieniem zmian roboczych; brak zapisu
  podczas oceny.
- Importowany kontrakt może rejestrować `activation_action_uid` oraz
  `activation_public=true`. Zwykła, zatwierdzona akcja gracza uruchamia wtedy proces
  przez tę samą ścieżkę UI/Bridge. Core podstawia tylko `@ACTOR_UID`, `@ACTOR_KIND`,
  `@TARGET_UID`, `@TARGET_KIND`; neutralny kontrakt wiadomości dopuszcza też
  dokładny literal zatwierdzonego komunikatu i UID procesu. Model nie podaje
  ceny, czasu ani nagrody.
  Rozpoczęcie wymaga potwierdzonego efektu mechanicznego i zapisuje przyszły termin.
  Obecny wariant obsługuje pojedynczą akcję inicjującą. Jawna akcja
  `CANCEL_BACKGROUND_PROCESS` przerywa własny, jeszcze nieterminowy proces i usuwa
  jego deadline bez wypłaty przyszłych skutków. Publiczna lista pokazuje tylko własne
  trwające zadania. Przycisk „Przerwij” przechodzi zwykłą ścieżkę intencji, propozycji,
  mechaniki i commitu. Dokładny selektor wiąże wersję procesu i generację historii;
  niejednoznaczna kategoria wymaga doprecyzowania, nie wyboru pierwszego procesu.

Kompletne receptury mogą trafić przez produkcyjny import definicji. Katalogi
gospodarki, organizacji i populacji przygotowują wersjonowane definicje z danych
właścicieli. Nie tworzą pieniędzy, uprawnień, tras, wiadomości ani populacji tylko
dlatego, że dana operacja istnieje. Reguła NPC wymaga jawnej polityki aktywacji,
zdolności wykonawcy i dokładnego grantu. Stały cel reguły nadal musi występować
w autoryzowanej projekcji wiedzy NPC.

## Zakres podłączeń i ograniczenia

Read-only diagnostyka LAB, własne procesy i zwykłe komunikaty UI są podłączone.
Test wspólnego composition root aplikacji potwierdził rozpoczęcie zadania,
przerwanie dokładnie wyświetlonym selektorem, cofnięcie i późniejsze rozliczenie
alternatywnej tury. Tylko granica odpowiedzi AI jest kontrolowana w tym teście;
intencja, mechanika, proces, czas i commit są rzeczywiste. Osobne testy właścicieli
na Motoroli potwierdziły obowiązek, opóźniony raport, migrację i starcie.
Testy jednostkowe obejmują wybór instytucjonalny i przechwycenie legalnego nośnika.
Nie jest to dowód jakości lub wydajności modelu generatywnego.

Wersja robocza jawnie odrzuca częściową rekrutację GROUP do UNIT, częściową migrację
oraz śmierć z puli rannych, dopóki właściwy właściciel nie zapewnia zachowania slotów.
Brak zarejestrowanej reguły lub uprawnienia zwraca typed BLOCKED. Narracja nie może
zastąpić brakującego skutku. Nie należy utożsamiać samego katalogu reguł bez
kompletu wymagań z wykonywalnym procesem ani z gotową fazą.

## Bramki przed wydaniem

Krótki zestaw lokalny obejmuje adaptery, konflikt zasobów, generację historii,
zależności, typed codecs oraz tożsamość zdarzeń. Wymagane są także testy bazy:
rollback, stary zapis, reopen, replay i undo z inną decyzją.

Następnie wymagany jest krótki scenariusz na Motoroli oraz API28/36, process-death
i izolacja release na końcowym SHA. Dostępność telefonu została potwierdzona przez
ADB. Ograniczony przebieg SQLite na Motoroli z 4 października 2026 dał 7/7:
projekt, opóźniona wiadomość, starzenie, dostawa, konflikt o ten sam towar,
rollback/retry, odtworzenie, cofnięcie i migracja starszego zapisu. Test wykrył
rzeczywisty błąd odczytu rodzaju lokacji: dostawa szukała dawnego `KIND`, zamiast
`RPGOS-WORLD:ELEMENT_KIND`. Poprawiona ścieżka czyta oba formaty.

Osobna próba oczekującego procesu przeszła po rzeczywistym `am force-stop`,
potwierdzeniu braku PID i ponownym uruchomieniu: jedno rozliczenie, bez podwójnej
konsumpcji. Dodatkowy odbiór wspólnych portów aplikacji potwierdził przerwanie
i ukończenie zadania oraz undo. Rozliczenie projektu, wiadomości i starzenia
przeszło ponownie po poprawkach rzeczywistego debetu pracy i staged odczytu zasobów.
Naprawiono również kodek zmieniający kolejność źródeł dowodu oraz niekompletne
odwołania owner payloadów; kontrola kompletności skutków nie została osłabiona.

Aktywny czat dostarczył przez LAB rzeczywiste odpowiedzi do tury oczekiwania,
odzyskania narracji po commicie, cofnięcia, restartu i odmiennej decyzji. Odpowiedź
po odmiennej decyzji nie użyła naprawy ani narracji zastępczej. Mechanika została
zatwierdzona tylko raz. Bielik i Bekko nie uczestniczyły w tym odbiorze.

Końcowy zbiorczy odbiór Motoroli zaliczył 12/12 scenariuszy. Osobna próba po
rzeczywistym zamknięciu procesu zaliczyła seed i resume, po jednym teście każdy.
Lokalny ukierunkowany zestaw JVM zaliczył 267 testów. CI pierwszego SHA potwierdziło
debug/lab JVM i izolację publicznego APK. Dwie dodatkowe kontrole ujawniły brak
nowych plików w zamkniętym rejestrze writerów i stary oczekiwany fingerprint
manifestu migracji; rejestr i fingerprint uzupełniono bez wyłączania kontroli.

API36 zaliczyło pierwszy przebieg. API28 wykryło niedostępną na starszym Androidzie
metodę `BigInteger.longValueExact()` w odczycie zasobu. Odczyt zasobów i polityka
ceny używają teraz istniejącego, zakresowo sprawdzanego konwertera Core.
Odczyt jednostek nie obcina części ułamkowej: brak dokładnej reprezentacji zwraca
niedostępność. Dedykowany test JVM zaliczył 23/23 przypadki, w tym ułamki, wartości
nieskończone i przekroczenie zakresu. Dodano też krótki test tych konwersji na
Androidzie, bez osłabienia kontroli przepełnienia i zaokrąglania ceny.
Na Motoroli ponowienie rozliczenia właścicieli z undo oraz nowy test konwersji
zakończyły się wynikiem 2/2; istniejąca instalacja użytkownika nie była zmieniana.

Exact-SHA CI API28/36 i wszystkie wymagane bramki końcowego commitu nadal
warunkują scalenie oraz publikację. Planowany numer aktualizacji to
`1.3.0-alpha23-world64`, kod 163. Po poprawce zgodności ponawiane są wyłącznie
dotknięte kroki lokalnego odbioru oraz końcowe kontrole CI.

Dedykowane CI poprawki zgodności zaliczyło debug/lab, API28/36, rzeczywiste
zatrzymanie procesu oraz izolację release. Istniejące pełne CI ujawniło dodatkowo
niezarejestrowanych konsumentów chronionych danych i błędny fixture podróży
w historycznej próbie 100 tur. Rejestr uzupełniono po audycie właścicieli, bez
osłabienia kontroli. Ścieżka szpiegostwa nie może już zastępować brakującego
przechwycenia Phase38 samym posiadaniem dokumentu. Wymagane są rzeczywiste,
aktualne dowody etapów dostępu, w tym zrozumienia.

Fixture 100 tur materializuje dwa widoczne miejsca przez zwykłą transakcję.
Phase63 wyprowadza legalne dwukierunkowe połączenia, a Phase37 zapisuje ich
obserwacje. Każda tura musi rzeczywiście zmienić lokację i naliczyć czas trasy.
Setup jest osobnym commitem; wszystkie 100 tur rozgrywki pozostają w teście.
Po cofnięciu próba wybiera inny cel. Krótka bramka sprawdza tylko dwuturowy
wariant tego samego fixture; pełna próba pozostaje w dotychczasowym CI pamięci.

Po poprawkach ukierunkowany JVM zaliczył 77/77 testów granic dostępu i informacji.
Na Motoroli zaliczyły osobno test posiadanego dokumentu bez dowodu zrozumienia
oraz krótki ruch z reopen, undo i innym celem. Ta druga próba sprawdza także
efekt `LOCATION_TRANSITION`: dawny fixture używał `MOVEMENT`, czyli ruchu po
współrzędnych bez zmiany lokacji. Końcowe CI pełnych 100 tur nadal musi potwierdzić
całą historię; krótki wariant nie jest jego zamiennikiem.

Nie uruchamiamy w tym odbiorze 100 tur AI, dużego Bielika, szerokiego A/B ani
benchmarków miliona rekordów. Historyczna regresja jest naprawiana przez legalny
fixture, nie teleportację, pomijanie tur ani usunięcie testu. Wynik pełnego CI
poprawki nadal jest wymagany przed wydaniem. Fazy 65–70 i branching 72 pozostają
poza tym zakresem.
