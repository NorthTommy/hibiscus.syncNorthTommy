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
Die Kontonummer ist ebenfalls wichtig, damit der Saldo-Abgleich funktioniert. Idealerweise ergibt sie sich direkt aus der IBAN. Hinweis: In einigen Fällen ist als Kontonummer die 10-stellige Depotnummer zu nutzen, die sich in den letzten Ziffern von der Verrechnungs-/Girokontonummer unterscheidet. Also mal probieren welche Nummer man hat. Auf der Desktop-Seite kann man unter Profil>Settings>Accounts beide Nummern einsehen. 

Hinweis: Bei dem Support von TradeRepublic soll es NICHT um eine Depotunterstützung gehen. Vielmehr soll das Konto selbst samt allen Transaktionen synchronisiert werden können.
Dass dabei Depot-Bewegungen auftrauchen ist dem geschuldet, dass das Konto neben üblichen Bewegungen eben auch als Verrechnungskonto für das Depot dient.

#### Bekannte Probleme / Einschränkungen
- Einige der Umsatztypen können noch unbekannt sein (dann wird bei "Art" als "Sonstiges (<type>)" eingetragen). In dem Falle gerne ein passenden Hinweis mit dem Typen und um welche Art von Buchung es sich handelt.

### Wichtig für den Datenschutz auf dem eigenen Rechner
Da es sich immer noch um eine 0.x Version handelt, die auch released immer noch Entwicklungszustand hat (dazu ändern die Banken zu oft ihre Interfaces), werden rel. viele Daten
via DEBUG-Level beim Abrufen gelogged. Dies schließt auch Teile von Zugangsdaten (z.B. in Form von URL-Parameters von https-calls (ja das machen einige Banken wirklich)) und Transaktionsdaten in Form raw-JSON ein.
Entgegen der üblichen Sicherheit von Jameica der die Datenbank verschlüsselt, liegen logs frei auf dem Rechner.
Also bitte beachten wenn ihr DEBUG logleven anhabt, dann ist es eben auch debug samt Daten. Ausschalten und alte logs sicher löschen.
Dann wird nur noch der Ablauf des Plugins gelogged.

## Developer
Folge der [offiziellen Anleitung](https://www.willuhn.de/wiki/doku.php?id=develop:eclipse) für Entwickler um Jameica Plugins zu bauen.

Du brauchst das Plugin für Hibiscus und eben dieses hier. Dazu jeweils die Git-Hub Projekte analog zum Jameica Projekt clonen und in Eclipse importieren.
Um die Plugins dann auch beim Start zu laden, folge auch hier der Anleitung für das Example-Projekt.
In der Datei cfg/de.willuhn.jameica.system.Config.properties im Benutzerverzeichnis für Jameica sollte dann mindestens etwas wie:<br>
jameica.plugin.dir.0=../hibiscus.syncNorthTommy<br>
jameica.plugin.dir.1=../SyncusGnampfus
drin stehen (je nachdem wie die Projekte genannt wurden).

### Erster Build des Plugin - Abhängigkeiten auflösen
Einmalig (oder bei jeder Änderung der Abhängigkeiten die mittels ANT aufgelöst werden) muss man über die build.xml das all-Target ausführen.
Danach kann dann je nach Vorliebe erstmal nur das jar-Target genutzt werden um schneller zu bauen und zu debuggen.

### Debug/Run (Launcher vs. manuell)
Debug/Run erfolgt dann immer über die Run-Konfiguration von Jameica Main (siehe dazu die offizielle Anleitung).
Für Tests ein ganzes Plugin als Zip zu bauen, kann man auch zip-Target nutzen - dieses kann man dann auch in einer "echten" Instanz von Jameica importieren ohne testen (ohne es über Eclipse zu starten).<br>
Es ist empfehlenswert immer nur einen Build im release-ordner zu belassen, damit beim Starten wirklich der aktuelle Build genutzt wird.
Der Build-Launcher für dieses Plugin ist aktuell auf clean und jar eingestellt und kann genutzt werden (Default in Eclipse ist Auto-Build, sodass "Build" einzeln ausgegraut ist) - hier gilt nach Vorliebe Eclipse nutzen.
Um andere ANT-Targets auszuführen: rechte Maustaste auf build/build.xml RunAs > Ant Build... und dann das/die Target(s) auswählen.
