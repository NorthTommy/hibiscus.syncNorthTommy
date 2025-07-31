# Hibiscus-Sync-Plugin für diverse Banken
Unterstützung für den Umsatz- und Saldoabruf bei folgenden Banken

 - Amazon VISA (Zinia-Bank)

## Einrichtung
Entweder das Plugin als ZIP-File aus den Github-Releases in Hibiscus importieren, oder in den Jameica-Einstellungen unter *Plugins / Repositories bearbeiten* die URL *https://northtommy.linkpc.net/hibiscus/* ergänzen und unter *verfügbare Plugins* auswählen. Beim ersten Mal muss das Zertifikat akzeptiert werden, daher hier zum Abgleich:
- Common Name (CN) *northtommy.linkpc.net*
- Organisation (O) --
- SHA256: 51:79:3A:DE:97:A5:05:06:6A:94:AB:60:2E:20:70:07:6C:06:3C:39:5F:73:EA:AB:7C:E4:63:40:F5:13:7C:4E
- SHA1: 5D:AA:B3:18:BD:97:8C:02:07:DF:A2:6B:4A:3B:76:FC:0A:63:5A:7F
  
Konten müssen von Hand angelegt werden, dafür unter **Konten** auf **Konto manuell anlegen** gehen. Keinen Haken bei **Offline-Konto** setzen!

### Amazon VISA
Hier muss nur die Kundenkennung mit dem Benutzernamen fürs Onlinebanking gefüllt und im Dropdown **Zugangsweg** *Amazon VISA (Zinia)* ausgewählt werden.
Die IBAN muss korrekt gesetzt sein, wodurch sich die BLZ von 50034200 ergeben muss (wird wenn leer, bei der Eingabe der IBAN automatisch gesetzt).

Die Unterkontonummer muss den letzten 4 Ziffern der VISA-Karte entsprechen.

## Bekannte Probleme / Einschränkungen
- Mir ist nur der Weg mittels SMS für die 90-Tage-Grenze an alten Umsätzen bekannt. Nichts anderes wurde getestet.
- Mehrere Karten (falls von Zinia unterstützt) wurde nicht getestet, gerne testen und rückmelden (mittels Unterkonten sollte es con der Idee gehen, solange die abrufbaren Transaktionen alle Karten beinhalten).
- Amazon-Punkte-Sammlungen werden aktuell nicht z.B. im Notizfeld vermerkt
- Reine Amazon-Punkte-Transaktionen (0,- EUR) werden ignoriert
- Zwischensaldo jeder Buchung ist nur eine Momentanaufnahme zum Zeitpunkt der Synchonisierung und ergebt keinen Anspruch auf Korrektheit/Aktualisierung wenn nachträglich (Valuta neuer) Umsätze abgerechnet (Autorisierungen) oder gelöscht (Reservierungen) werden. 


