# RPG OS Elemental 132

Cel: zmiana technologii renderowania pięciu żywiołów z proceduralnych linii Canvas na przygotowane tekstury VFX z przezroczystością, przy zachowaniu istniejącej orbity i animacji.

Założenia:
- geometria orbity pozostaje bez zmian,
- angle i tempo obrotu pozostają bez zmian,
- introAnimation, pulse, logo i pozostały UI pozostają bez zmian,
- zmienia się wyłącznie warstwa wizualna żywiołów,
- docelowo 5 sektorów VFX: wiatr, ogień, woda, ziemia, piorun.

VersionCode: 132
VersionName: 1.2.0-alpha5-elemental132

Implementacja: pięć plików PNG VFX w drawable-nodpi; każdy sprite jest przesuwany po oryginalnej elipsie i ustawiany stycznie do toru. Proceduralny renderer żywiołów nie jest już używany przez ElementalOrbit.
