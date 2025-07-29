# Hibiscus-Sync-Plugin für diverse Banken
Unterstützung für den Umsatz- und Saldoabruf bei folgenden Banken

 - Amazon VISA (Zinia-Bank)

## Einrichtung
Entweder das Plugin als ZIP-File aus den Github-Releases in Hibiscus importieren, oder in den Jameica-Einstellungen unter *Plugins / Repositories bearbeiten* die URL TBD ergänzen und unter *verfügbare Plugins* auswählen. Beim ersten Mal muss das Zertifikat akzeptiert werden, daher hier zum Abgleich:
- Common Name (CN) *TBD*
- Organisation (O) *TBD*
- SHA256: TBD
- SHA1: TBD
  
Konten müssen von Hand angelegt werden, dafür unter **Konten** auf **Konto manuell anlegen** gehen. Keinen Haken bei **Offline-Konto** setzen!

### Amazon VISA
hier muss nur die Kundenkennung mit dem Benutzernamen fürs Onlinebanking gefüllt und im Dropdown **Zugangsweg** *Amazon VISA (Zinia)* ausgewählt werden.
Die Unterkontonummer muss den letzten 4 Ziffern der VISA-Karte entsprechen.
Damit sollte es auch möglich sein mehrere VISA-Karten mit einem Benutzernamen zu verwalten indem man mehrere Konten anlegt, die versch. Unterkontennummern habenür erst Email, dann SMS, dann App (ja, ich kann Banking-Apps nicht leiden!). Durch Umstellen der Buchstaben sind andere Prios möglich.

## Bekannte Probleme / Einschränkungen
- Mit ist nur der Weg mittels SMS für die 90-Tage-Grenze an alten Umsätzen bekannt. Nichts anderes wurde getestet
- Mehre Karten (falls von Zinia unterstützt) wurde nicht getestet, sollte von der Idee her aber gehen.
- reine Amazon-Punkte-Transaktionen werden ignoriert
- Punkte-Sammlungen werden aktuell noch nicht z.B. im Notizfeld vermerkt

