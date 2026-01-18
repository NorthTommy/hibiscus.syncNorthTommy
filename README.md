# Hibiscus-Sync-Plugin für diverse Banken
Unterstützung für den Umsatz- und Saldoabruf bei folgenden Banken

 - Amazon VISA (Zinia-Bank)
 - TradeRepublic

## Einrichtung
Entweder das Plugin als ZIP-File aus den Github-Releases in Hibiscus importieren, oder in den Jameica-Einstellungen unter *Plugins / Repositories bearbeiten* die URL *https://northtommy.linkpc.net/hibiscus/* ergänzen und unter *verfügbare Plugins* auswählen. Beim ersten Mal muss das Zertifikat akzeptiert werden, daher hier zum Abgleich:
- Common Name (CN) *northtommy.linkpc.net*
- Organisation (O) --
- SHA256: 64:DA:3A:D5:C4:C4:77:20:ED:9B:04:A4:E5:AF:2D:9D:A6:91:0A:D1:6E:06:E4:48:64:56:AC:B3:53:DC:CC:E5
- SHA1: D5:68:6A:7C:88:07:10:B2:98:75:4F:09:D7:E4:F7:07:0E:5C:D9:AD
  
Konten müssen von Hand angelegt werden, dafür unter **Konten** auf **Konto manuell anlegen** gehen. Keinen Haken bei **Offline-Konto** setzen!

### Amazon VISA
Hier muss die Kundenkennung mit dem Benutzernamen fürs Onlinebanking gefüllt werden. Im Dropdown **Zugangsweg** *Amazon VISA (Zinia)* ausgewählen.
Die IBAN muss korrekt gesetzt sein, wodurch sich die BLZ von 50034200 ergeben muss (wird wenn leer, bei der Eingabe der IBAN automatisch gesetzt).

Tipp: Die Kreditkarten-IBAN findet man auf der Zinia-Web-Page unter dem Menüpunkt: "Finanzierung">"Geld einzahlen:">"Internes Bankkonto".
Nicht verwechseln mit der IBAN des Referenz-Giro-Kontos oder der IBAN mit der Zinia die Lastschrift-Abbuchung macht !

Die Unterkontonummer muss den letzten 4 Ziffern der VISA-Karte entsprechen.

#### Bekannte Probleme / Einschränkungen
- Mir ist nur der Weg mittels SMS für die 90-Tage-Grenze an alten Umsätzen bekannt. Nichts anderes wurde getestet.
- Mehrere Karten (falls von Zinia unterstützt) wurde nicht getestet, gerne testen und rückmelden (mittels Unterkonten sollte es con der Idee gehen, solange die abrufbaren Transaktionen alle Karten beinhalten).
- Amazon-Punkte-Sammlungen werden aktuell nicht z.B. im Notizfeld vermerkt
- Reine Amazon-Punkte-Transaktionen (0,- EUR) werden ignoriert
- Zwischensaldo jeder Buchung ist nur eine Momentanaufnahme zum Zeitpunkt der Synchonisierung und ergebt keinen Anspruch auf Korrektheit/Aktualisierung wenn nachträglich (Valuta neuer) Umsätze abgerechnet (Autorisierungen) oder gelöscht (Reservierungen) werden. 

### TradeRepublic
Hier muss die Kundenkennung mit der verknüpften Telefonnummer im Format +49xxxx (f. Dtl). übereinstimmen. Im Dropdown **Zugangsweg** *Traderepublic* ausgewählen.
Die IBAN muss korrekt gesetzt sein, wodurch sich die BLZ von 10012345 ergeben muss (wird wenn leer, bei der Eingabe der IBAN automatisch gesetzt).
Die damit gesetzte Kontonummer ist ebenfalls wichtig, damit der Saldo-Abgleich funktioniert.

Hinweis: Bei dem Support von TradeRepublic soll es NICHT um eine Depotunterstützung gehen. Vielmehr soll das Konto selbst samt allen Transaktionen synchronisiert werden können.
Dass dabei Depot-Bewegungen auftrauchen ist dem geschuldet, dass das Konto neben üblichen Bewegungen eben auch als Verrechnungskonto für das Depot dient.

#### Bekannte Probleme / Einschränkungen
- Einige der Umsatztypen können noch unbekannt sein (dann wird bei "Art" als "Sonstiges (<type>)" eingetragen). In dem Falle gerne ein passenden Hinweis mit dem Typen und um welche Art von Buchung es sich handelt.
