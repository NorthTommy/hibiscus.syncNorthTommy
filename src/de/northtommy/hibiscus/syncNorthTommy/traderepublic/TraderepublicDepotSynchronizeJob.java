package de.northtommy.hibiscus.syncNorthTommy.traderepublic;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.rmi.RemoteException;
import java.text.ParseException;

import org.json.JSONArray;
import org.json.JSONObject;

import de.willuhn.datasource.rmi.DBIterator;
import de.willuhn.jameica.hbci.Settings;
import de.willuhn.jameica.hbci.messaging.ObjectChangedMessage;
import de.willuhn.jameica.hbci.messaging.ObjectDeletedMessage;
import de.willuhn.jameica.hbci.rmi.Konto;
import de.willuhn.jameica.hbci.rmi.Umsatz;
import de.willuhn.jameica.system.Application;
import de.willuhn.jameica.messaging.MessageBus;
import de.willuhn.logging.Level;
import de.willuhn.jameica.hbci.messaging.ImportMessage;
import de.willuhn.jameica.hbci.messaging.SaldoMessage;
import de.willuhn.util.ApplicationException;

class TraderepublicDepotSynchronizeJob {

	private final TraderepublicSynchronizeJobKontoauszug owner;

	TraderepublicDepotSynchronizeJob(TraderepublicSynchronizeJobKontoauszug owner) {
		this.owner = owner;
	}

	boolean process(Konto konto, boolean fetchSaldo, boolean fetchUmsatz,
			TraderepublicWebSocket socket) throws Exception {
		owner.log(Level.INFO, "Depotdaten werden abgerufen...");
		boolean saveDebug = "true".equals(konto.getMeta(TraderepublicSynchronizeBackend.META_JSONDATA, "true"));

		if (fetchSaldo) {
			handlePortfolio(konto, socket, saveDebug);
		}

		if (fetchUmsatz) {
			handleTransactions(konto, socket.getAccountTransactions(), saveDebug);
		}
		return true;
	}

	private void handlePortfolio(Konto konto, TraderepublicWebSocket socket, boolean saveDebug)
			throws Exception, RemoteException, ApplicationException {
		var compactPortfolio = socket.getCompactPortfolio();
		if (compactPortfolio == null) {
			owner.log(Level.ERROR, "Kein Portfolio vom WebSocket empfangen.");
		}
		if (saveDebug) {
			Path p = Path.of(Application.getConfig().getWorkDir(), "TR-portfolio.json");
			owner.log(Level.INFO, "Speichere Portolio in " + p.toFile());
			saveRawJsonFile(p, compactPortfolio);
		}
		JSONArray positions = compactPortfolio.optJSONArray("positions");
		if (positions == null) {
			owner.log(Level.WARN, "Positionen in Portfolio leer.");
			return;
		}

		ArrayList<Map<String, Object>> bestand = new ArrayList<>();
		double sum = 0.0;
		for (int i = 0; i < positions.length(); i++) {
			JSONObject position = positions.getJSONObject(i);
			String instrumentId = position.optString("instrumentId");
			JSONObject instrumentDetails = socket.getPortfolioInstrumentDetails(instrumentId);
			String instrumentName = instrumentId;
			String wkn = "";
			if (instrumentDetails != null) {
				instrumentName = instrumentDetails.optString("name",
						instrumentDetails.optString("shortName", instrumentId));
				wkn = instrumentDetails.optString("wkn", "");
			}
			Double anzahl = parseDouble(position.optString("netSize", "0"));
			JSONObject ticker = socket.getPortfolioTicker(instrumentId);
			JSONObject tickerLast = ticker != null ? ticker.optJSONObject("last") : null;
			Double kurs = parseDouble(tickerLast != null ? tickerLast.optString("price", null) : null);
			String kurswaehrung = TraderepublicTransactionAnalyzer.firstNonBlank(
					tickerLast != null ? tickerLast.optString("currency", null) : null,
					ticker != null ? ticker.optString("currency", null) : null,
					position.optString("currency", null),
					instrumentDetails != null ? instrumentDetails.optString("currencyId", null) : null,
					"EUR");
			sum += kurs * anzahl;
			HashMap<String, Object> b = new HashMap<>();
			b.put("konto", konto);
			b.put("isin", instrumentId);
			b.put("wkn", wkn);
			b.put("name", instrumentName);
			b.put("anzahl", anzahl);
			b.put("kurs", kurs);
			b.put("kursw", kurswaehrung);
			b.put("wert", ((kurs != null) && (anzahl != null) ? kurs * anzahl : null));
			b.put("wertw", kurswaehrung);
			b.put("datum", new Date());
			String tickerTime = TraderepublicTransactionAnalyzer.firstNonBlank(
					tickerLast != null ? tickerLast.optString("time", null) : null,
					ticker != null ? ticker.optString("time", null) : null);
			if (tickerTime != null) {
				try {
					b.put("bewertungszeitpunkt", parseTickerTime(tickerTime));
				} catch (Exception e) {
					owner.log(Level.DEBUG,
							"Konnte Bewertungszeitpunkt nicht parsen für " + instrumentId + ": " + tickerTime);
				}
			}
			bestand.add(b);
		}

		Map<String, Object> portfolio = new HashMap<>();
		portfolio.put("konto", konto);
		portfolio.put("portfolio", bestand);
		MessageBus.sendSync("depotviewer.portfolio", portfolio);

		konto.setSaldo(sum);
		konto.setSaldoAvailable(0);
		konto.store();
		Application.getMessagingFactory().sendMessage(new SaldoMessage(konto));

	}

	private void handleTransactions(Konto konto, ArrayList<TraderepublicWebSocket.Transaction> transactions,
			boolean saveDebug) throws Exception {
		double calculatedSaldo = konto.getSaldo();
		ArrayList<Umsatz> duplicatesRxNotBooked = new ArrayList<Umsatz>();
		ArrayList<HashMap<String, Object>> transactionen = new ArrayList<>();

		if (saveDebug) {
			owner.log(Level.DEBUG, "Transaktionen von TR erhalten: " + transactions.size());
			Path p = Path.of(Application.getConfig().getWorkDir(), "TR-Transactions.zip");
			OutputStream f = Files.newOutputStream(p, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
			try (ZipOutputStream zos = new ZipOutputStream(f)) {
				for (int i = 0; i < transactions.size(); i++) {
					var t = transactions.get(i);
					System.out.println(t.getClass());
					String id = TraderepublicTransactionAnalyzer.getId(t.details);

					ZipEntry zipEntry = new ZipEntry(id + "-d.json");
					zos.putNextEntry(zipEntry);
					byte[] data = t.details.toString().getBytes(StandardCharsets.UTF_8);
					zos.write(data, 0, data.length);
					zos.closeEntry();

					zipEntry = new ZipEntry(id + "-t.json");
					zos.putNextEntry(zipEntry);
					data = t.transaction.toString().getBytes(StandardCharsets.UTF_8);
					zos.write(data, 0, data.length);
					zos.closeEntry();
				}
			}
		}
		owner.log(Level.DEBUG, "Transaktionen von TR erhalten: " + transactions.size());
		for (int i = 0; i < transactions.size(); i++) {
			var transaction = transactions.get(i).transaction;
			var transactionDetail = transactions.get(i).details;
			if (!TraderepublicTransactionAnalyzer.knownEventType(transaction)) {
				owner.log(Level.ERROR,
						"Unbekannter Event-Typ: " + TraderepublicTransactionAnalyzer.getEventType(transaction) + " ("
								+ TraderepublicTransactionAnalyzer.getId(transactionDetail) + ")");
				continue;
			}
			if (!TraderepublicTransactionAnalyzer.relevantEventType(transaction)) {
				continue;
			}
			String id = TraderepublicTransactionAnalyzer.getId(transactionDetail);
			String eventType = TraderepublicTransactionAnalyzer.getSection(transaction, "status").toString();
			if (!eventType.equals("EXECUTED")) {
				continue;
			}
			try {
				HashMap<String, Object> t = TraderepublicTransactionAnalyzer.parseTransaction(transaction, transactionDetail);
				if (t == null) {
					owner.log(Level.WARN, "Ignoriere Transaction mit Status " + eventType + " (" + id  + ")");
					continue;
				}
				t.put("konto", konto);
				transactionen.add(t);
			} catch (ParseException e) {
				owner.log(Level.ERROR,"Fehler beim Parsen der Transaction: " + id  + " => " + e);
			}
		}

		Map<String, Object> depotTransactionen = new HashMap<>();
		depotTransactionen.put("konto", konto);
		depotTransactionen.put("transactions", transactionen);
		MessageBus.sendSync("depotviewer.transaction", depotTransactionen);

	}

	private Double parseDouble(String value) {
		if ((value == null) || value.isBlank()) {
			return null;
		}
		return Double.valueOf(value);
	}

	private Date parseTickerTime(String value) {
		if (value.matches("\\d+")) {
			return new Date(Long.parseLong(value));
		}
		return Date.from(java.time.Instant.parse(value));
	}

	private void saveRawJsonFile(Path path, Object obj) throws IOException {
		if (obj == null) {
			return;
		}
		Files.writeString(path, obj.toString(), StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
	}

}
