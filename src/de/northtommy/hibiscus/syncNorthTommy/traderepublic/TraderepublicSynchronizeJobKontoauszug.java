package de.northtommy.hibiscus.syncNorthTommy.traderepublic;


import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Resource;

import org.htmlunit.HttpMethod;
import org.json.JSONObject;

import de.northtommy.hibiscus.syncNorthTommy.KeyValue;
import de.northtommy.hibiscus.syncNorthTommy.SyncNTSynchronizeJob;
import de.northtommy.hibiscus.syncNorthTommy.SyncNTSynchronizeJobKontoauszug;
import de.northtommy.hibiscus.syncNorthTommy.SyncNTSynchronizeJobKontoauszugLoggerI;
import de.northtommy.hibiscus.syncNorthTommy.WebResult;
import de.northtommy.hibiscus.syncNorthTommy.traderepublic.TraderepublicWebSocket.RxState;
import de.willuhn.datasource.rmi.DBIterator;
import de.willuhn.jameica.hbci.Settings;
import de.willuhn.jameica.hbci.messaging.ObjectChangedMessage;
import de.willuhn.jameica.hbci.messaging.ObjectDeletedMessage;
import de.willuhn.jameica.hbci.messaging.SaldoMessage;
import de.willuhn.jameica.hbci.rmi.Konto;
import de.willuhn.jameica.hbci.rmi.Umsatz;
import de.willuhn.jameica.hbci.synchronize.SynchronizeBackend;
import de.willuhn.jameica.system.Application;
import de.willuhn.logging.Level;
import de.willuhn.util.ApplicationException;

// Spezifisch, eigentliche Implementierung

public class TraderepublicSynchronizeJobKontoauszug extends SyncNTSynchronizeJobKontoauszug implements SyncNTSynchronizeJob , SyncNTSynchronizeJobKontoauszugLoggerI
{

	private static final String TRADEREP_PLAYWRIGTH_LOGIN = "https://app.traderepublic.com/login";
	
	private static final String TRADEREP_LOGIN_URL = "https://api.traderepublic.com/api/v2/auth/web/login";
	private static final String TRADEREP_LOGIN_PROCESS_URL = "https://api.traderepublic.com/api/v2/auth/web/login/processes/";
	// previously /api/v2/auth/account - no longer used by the web app; this call is made directly after login confirmation
	private static final String TRADEREP_ACCOUNT_URL = "https://api.traderepublic.com/api/v1/account-manager/accounts/switchable";
	private static final String TRADEREP_WSS_URL = "wss://api.traderepublic.com/";
	private static final String TRADEREP_LOGOUT_URL = "https://api.traderepublic.com/api/v1/auth/web/logout";
	
	// fallback if the web app version could not be taken from the x-tr-app-version header
	private static final String TRADEREP_DEFAULT_CLIENT_VERSION = "14.23.3";
	// polling interval of the login process (web app polls every ~2.3s)
	private static final int LOGIN_POLL_INTERVAL_MS = 2000;
	// max. time for the user to confirm the login in the app (server side expiry is ~2min)
	private static final int LOGIN_CONFIRM_TIMEOUT_MS = 150000;
	
	private PlayWrightRunnerThread pwrt = null;
	
	/**
	 * cookies received via set-cookie (name -> value) during login, sent with all further requests
	 * (without aws-waf-token, which is always appended with the current value)
	 */
	private final Map<String, String> sessionCookies = new LinkedHashMap<>();
	
	private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
	

	@Resource
	private TraderepublicSynchronizeBackend backend = null;

	@Override
	protected SynchronizeBackend getBackend() { return backend; }

	@Override
	public boolean process(Konto konto, boolean fetchSaldo, boolean fetchUmsatz, DBIterator<Umsatz> umsaetze, String user, String passwort) throws Exception
	{
		try {
			return processInternal(konto, fetchSaldo, fetchUmsatz, umsaetze, user, passwort);
		} finally {
			// browser must also be closed if login fails (before the websocket part with its own cleanup)
			stopBrowser();
		}
	}

	private void stopBrowser() {
		try {
			if ((pwrt != null) && pwrt.isAlive()) {
				log(Level.INFO, "Stop PlayWright in background ...");
				pwrt.stopRunning();
				pwrt.join(10000);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private boolean processInternal(Konto konto, boolean fetchSaldo, boolean fetchUmsatz, DBIterator<Umsatz> umsaetze, String user, String passwort) throws Exception
	{
		int trialCount = 0;
		WebResult response = null;

		boolean headlessBrowser = "false".equals(konto.getMeta(TraderepublicSynchronizeBackend.META_NOTHEADLESS, "false"));
		String firefoxPath = konto.getMeta(TraderepublicSynchronizeBackend.META_FIREFOXPATH,  null);
		
		this.pwrt = new PlayWrightRunnerThread(this, firefoxPath, headlessBrowser, webClient, TRADEREP_PLAYWRIGTH_LOGIN);
		sessionCookies.clear();

		ArrayList<KeyValue<String, String>> headers = new ArrayList<>();
		
		log(Level.INFO, "Warte auf AWS WAF token und Ger\u00E4tedaten vom Browser");
		this.pwrt.start();
		
		// waiting until playwright thread got a first token and the x-tr headers of the web app
		int pwrtTimeout = 120; /* times 1s -> 2min */
		while (pwrt.isAlive() && !pwrt.isReady() && (pwrtTimeout-- > 0)) {
			Thread.sleep(1000);
			log(Level.INFO, ".");
		}
		if (!pwrt.isAlive()) {
			log(Level.WARN, "unable to start playwright with background browser");
			throw new ApplicationException("Fehler beim Starten von Playwright fuer AWS WAF token");
		}
		if (isBlank(pwrt.awsWafToken)) {
			log(Level.WARN, "got no AWS WAF token in time");
			throw new ApplicationException("Zeitueberschreitung AWS WAF token");
		}
		if (!pwrt.isReady()) {
			// login is tried anyway - server may answer with an error or another login flow
			log(Level.WARN, "got no x-tr-device-info from web app in time - login will probably fail");
		}
		log(Level.DEBUG, "x-tr headers: " + pwrt.trHeaders.keySet());
		
		// got the AWS key maybe within unstable time at beginning
		// to move our retries to a stable key update interval, we wait a moment here
		Thread.sleep(1000);
		
		// add default headers for any communication
		headers.add(new KeyValue<String, String>("Accept", "*/*"));
		headers.add(new KeyValue<String, String>("Accept-Language", "en"));
		headers.add(new KeyValue<String, String>("Accept-Encoding", "gzip, deflate, br, zstd"));
		// x-tr-platform, x-tr-app-version, x-tr-device-info as sent by the web app
		for (var trHeader : pwrt.trHeaders.entrySet()) {
			headers.add(new KeyValue<String, String>(trHeader.getKey(), trHeader.getValue()));
		}
		if (!pwrt.trHeaders.containsKey("x-tr-platform")) {
			headers.add(new KeyValue<String, String>("x-tr-platform", "web"));
		}

		
		// Login step 1: phone number + PIN -> processId, confirmation in the app is requested by the server
		
		var loginBody = new JSONObject();
		loginBody.put("phoneNumber", normalizePhoneNumber(user));
		loginBody.put("pin", passwort);
		
		trialCount = 0;
		response = null;
		do {
			log(Level.DEBUG, "(re-)try login Step 1");
			updateRequestAuthHeaders(headers);
			
			response = doRequest(TRADEREP_LOGIN_URL, HttpMethod.POST, headers, "application/json", 
					loginBody.toString());
			
			trialCount++;
			if (response.getHttpStatus() != 200) {
				// try with new AWS WAF token after a moment
				Thread.sleep(300);
			}
		} while ((response.getHttpStatus() != 200) && (trialCount < 3));
		
		if (response.getHttpStatus() != 200) {
			log(Level.DEBUG, "login Step 1 response: " + response.getContent());
			throw new ApplicationException("Login Step 1 fehlgeschlagen (HTTP " + response.getHttpStatus() + ")");
		}
		collectSessionCookies(response, "Login Step 1");
		
		var json = response.getJSONObject();
		log(Level.DEBUG, "Login step 1 response: " + json);
		String processId = json.optString("processId");
		if (processId.isBlank()) {
			// other login flows (e.g. SMS/OTP, device registration) are not supported
			throw new ApplicationException("Login Step 1: keine processId erhalten (nicht unterst\u00FCtzter Login-Ablauf)");
		}
		log(Level.DEBUG, "Login Step 1 processId: " + processId);
		
		
		// Login step 2: poll login process until the user confirmed in the app
		
		log(Level.INFO, "Bitte den Login in der Traderepublic App best\u00E4tigen");
		// NOTE: if notifyUser() is modal, polling continues after the dialog was closed
		Application.getCallback().notifyUser("Bitte bestaetige den Login mittels der Traderepublic App");
		
		long pollStart = System.currentTimeMillis();
		String loginStatus = "PENDING";
		while (loginStatus.equalsIgnoreCase("PENDING")) {
			if ((System.currentTimeMillis() - pollStart) > LOGIN_CONFIRM_TIMEOUT_MS) {
				throw new ApplicationException("Zeitueberschreitung Login-Best\u00E4tigung in der App");
			}
			Thread.sleep(LOGIN_POLL_INTERVAL_MS);
			
			updateRequestAuthHeaders(headers);
			response = doRequest(TRADEREP_LOGIN_PROCESS_URL + processId, 
					HttpMethod.GET, headers, "application/json", null);
			if (response.getHttpStatus() != 200) {
				// single failed poll (e.g. WAF token just changing) is tolerated, the timeout limits retries
				log(Level.DEBUG, "Login Step 2 poll HTTP " + response.getHttpStatus() + ": " + response.getContent());
				continue;
			}
			collectSessionCookies(response, "Login Step 2");
			json = response.getJSONObject();
			loginStatus = json.optString("status", "PENDING");
			log(Level.DEBUG, "Login Step 2 poll status: " + loginStatus);
			
			if (!json.isNull("requiredAction")) {
				// e.g. further verification step - not supported
				log(Level.DEBUG, "Login Step 2 response: " + json);
				throw new ApplicationException("Login erfordert weitere Aktion (nicht unterst\u00FCtzt): " + json.get("requiredAction"));
			}
		}
		// web app treats CONFIRMED and COMPLETED as success
		if (!loginStatus.equalsIgnoreCase("CONFIRMED") && !loginStatus.equalsIgnoreCase("COMPLETED")) {
			log(Level.DEBUG, "Login Step 2 response: " + json);
			throw new ApplicationException("Login nicht best\u00E4tigt (Status " + loginStatus + ")");
		}
		
		log(Level.DEBUG, "Login session cookies: " + sessionCookies.keySet());
		if (!sessionCookies.containsKey("tr_session")) {
			log(Level.WARN, "no tr_session cookie received - further requests will probably fail");
		}
		
		updatePercentComplete(5);
		log(Level.INFO, "Login erfolgreich.");

		
		
		log(Level.INFO, "Hole Kontodaten.");
		
		trialCount = 0;
		response = null;
		do {
			log(Level.DEBUG, "(re-)try getting account data");
			updateRequestAuthHeaders(headers);
			
			response = doRequest(TRADEREP_ACCOUNT_URL, 
					HttpMethod.GET, headers, "application/json", 
					null);
			
			trialCount++;
			if (response.getHttpStatus() != 200) {
				// try with new AWS WAF token after a moment
				Thread.sleep(300);
			}
		} while ((response.getHttpStatus() != 200) && (trialCount < 3));
		
		if (response.getHttpStatus() != 200) {
			log(Level.DEBUG, "Response: " + response.getContent());
			throw new ApplicationException("Holen der Kontodaten fehlgeschlagen");
		}
		collectSessionCookies(response, "Account");
		
		json = response.getJSONObject();
		log(Level.DEBUG, "Account Response: " + json);
		
		
		log(Level.INFO, "Saldo und Ums\u00E4tze werden abgerufen...");
		
		Date untilDate = null;
		umsaetze.begin();
		if (umsaetze.hasNext()) {
			untilDate = umsaetze.next().getDatum();
			while (umsaetze.hasNext()) {
				var u = umsaetze.next();
				if (u.hasFlag(Umsatz.FLAG_NOTBOOKED)) {
					untilDate = u.getDatum();
				}
			}
			var gc = GregorianCalendar.getInstance();
			gc.setTime(untilDate);
			// to be sure
			gc.add(GregorianCalendar.DAY_OF_MONTH, -10);
		}
		
		String destUri = TRADEREP_WSS_URL;
        WebSocketClient client = new WebSocketClient();
		client.getHttpClient().setRequestBufferSize(64 * 1024);
		String clientVersion = pwrt.trHeaders.getOrDefault("x-tr-app-version", TRADEREP_DEFAULT_CLIENT_VERSION);
		log(Level.DEBUG, "WSS client version: " + clientVersion);
		TraderepublicWebSocket socket = new TraderepublicWebSocket(this, clientVersion, untilDate);

        try {
        		String awsWafToken = pwrt.awsWafToken;
        		
            client.start();

            URI echoUri = new URI(destUri);
            ClientUpgradeRequest request = new ClientUpgradeRequest();

            // Add headers
            request.setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0)");
            request.setHeader("Origin", "https://app.traderepublic.com");
            request.setHeader("Accept-Language", "en-US,en;q=0.5");
            request.setHeader("Accept-Encoding", "gzip, deflate, br, zstd");
            request.setHeader("Sec-WebSocket-Extensions", "permessage-deflate");
            request.setHeader("Sec-WebSocket-Version", "13");
            request.setHeader("Sec-WebSocket-Key", "62Uxbl7YDtD8trmqke4KYg==");  // TODO random
            request.setHeader("Sec-Fetch-Dest", "empty");
            request.setHeader("Sec-Fetch-Mode", "websocket");
            request.setHeader("Sec-Fetch-Site", "same-site");
            request.setHeader("Pragma", "no-cache");
            request.setHeader("Cache-Control", "no-cache");
            request.setHeader("Connection", "keep-alive, Upgrade");
            request.setHeader("Upgrade", "websocket");
            request.setHeader("Host", "api.traderepublic.com");
            
            request.setHeader("x-aws-waf-token", awsWafToken);
            
            // Add cookies as header (session cookies from login + current AWS WAF token)
            request.setHeader("Cookie", buildCookieHeader(awsWafToken));

            client.connect(socket, echoUri, request);
            log(Level.DEBUG, "WSS Connecting to: " + echoUri);

            long syncStart = System.currentTimeMillis();
            
            while ((socket.getRxState() != TraderepublicWebSocket.RxState.FINISHED) && (socket.getRxState() != TraderepublicWebSocket.RxState.ERROR) && ((System.currentTimeMillis() - syncStart) < 60000)) {
            	Thread.sleep(1000);
            }
            socket.awaitClose(2, TimeUnit.SECONDS);
            
            var e = socket.getErrorException();
            if ( e != null) {
            	throw e;
            }
            if (socket.getRxState() != RxState.FINISHED) {
            	throw new ApplicationException("Synchronisation Timeout");
            }
            
            
            if (fetchSaldo) {
            	boolean foundAccount = false;
            	var accountsCash = socket.getAccountsCash();
            	for (int i=0; i<accountsCash.length(); i++) {
            		JSONObject account = accountsCash.getJSONObject(i);
            		var accountNo = account.getString("accountNumber");
            		if (accountNo.endsWith(konto.getKontonummer())) {
            			log(Level.INFO, "Saldo f\u00FCr aktuelle Kontonummer gefunden");
            			konto.setSaldo(account.getDouble("amount"));
            			konto.store();
    					Application.getMessagingFactory().sendMessage(new SaldoMessage(konto));
    					foundAccount = true;
    					break;
            		} else {
            			log(Level.INFO, "Saldo f\u00FCr weitere Kontonummer gefunden (wird ignoriert): " + accountNo);
            		}
            	}
            	var accountsAvailableCash = socket.getAccountsAvailableCash();
            	for (int i=0; i<accountsAvailableCash.length(); i++) {
            		JSONObject account = accountsAvailableCash.getJSONObject(i);
            		var accountNo = account.getString("accountNumber");
            		if (accountNo.endsWith(konto.getKontonummer())) {
            			log(Level.INFO, "Verf\u00FCgbares Saldo f\u00FCr aktuelle Kontonummer gefunden");
            			konto.setSaldoAvailable(account.getDouble("amount"));
            			konto.store();
    					Application.getMessagingFactory().sendMessage(new SaldoMessage(konto));
    					foundAccount = true;
    					break;
            		} else {
            			log(Level.INFO, "Verf\u00FCgbares Saldo f\u00FCr weitere Kontonummer gefunden (wird ignoriert): " + accountNo);
            		}
            	}
            	if (foundAccount) {
            		log(Level.INFO, "Saldo abrufen erfolgreich.");
            	} else {
            		throw new ApplicationException("Keine Saldo-Information zu der Kontonummer gefunden.");
            	}
            }
            	
            if (fetchUmsatz) {
            	
            	ArrayList<TraderepublicWebSocket.Transaction> transactions = socket.getAccountTransactions();
            	var neueUmsaetze = new ArrayList<Umsatz>();
            	double calculatedSaldo = konto.getSaldo();
            	ArrayList<Umsatz> duplicatesRxNotBooked = new ArrayList<Umsatz>();
				boolean duplicateRxFound = false;
            	
            	for (int i=0; i < transactions.size(); i++) {
            		// analyze -> newUmsatz
            		var transaction = transactions.get(i).transaction;
            		var transactionDetail = transactions.get(i).details;
            		try {
            			var accountNo = transaction.optString("cashAccountNumber"); 
            			if ((accountNo == null) || (accountNo.isBlank())) {
            				// nur ein Konto vorhanden - alle Umsätze gehören zu dem Konto
            				log(Level.DEBUG, "Transaktion hat keine Kontonummer - Annahme nur ein Konto -> g\u00FCltig");
            			} else if (! accountNo.endsWith(konto.getKontonummer())) {
            				log(Level.DEBUG, "Transaktion verwerfen - Kontonummer nicht passend");
            				break;
            			} else {
            				log(Level.DEBUG, "Transaktion für aktuelle Kontonummer");
            			}
            			
            			var newUmsatz = (Umsatz) Settings.getDBService().createObject(Umsatz.class,null);
						newUmsatz.setKonto(konto);
						newUmsatz.setTransactionId(transaction.getString("id"));
						
						newUmsatz.setBetrag(transaction.getJSONObject("amount").getDouble("value"));
            			
		            	String vz = transaction.getString("title"); 
		            	String vz2 = transaction.optString("subtitle");
            			if (!vz2.isBlank()) {
            				vz = vz + " (" +  vz2 + ")";
            			}
		            	newUmsatz.setZweck(vz);
						
					var status = transaction.getString("status");
		            	if ( status.compareToIgnoreCase("CANCELED") == 0) {
		            		newUmsatz.setFlags(Umsatz.FLAG_NOTBOOKED);
		            		// intentionally no saldo setting
		            		
		            		// updating Verwendungszweck optionally
		            		// e.g. canceled tradings are marked as canceled but do not include a hint for the status in text
		            		// e.g. rejected card executions are set to canceled and subtitled with "declined"
		            		if ((newUmsatz.getZweck().indexOf("Declined") == -1) && 
		            			(newUmsatz.getZweck().indexOf("Canceled") == -1)) {
		            			newUmsatz.setZweck(newUmsatz.getZweck() + " (Canceled)");	
		            		}
		            		
		            	} else if ( status.compareToIgnoreCase("PENDING") == 0) {
		            		// intentionally no saldo setting
		            		newUmsatz.setFlags(Umsatz.FLAG_NOTBOOKED);
		            		// Verwendungszweck wird nicht angepasst, da hibiscus hier mit dem Flag ausgraut, wird der Umsatz nochmal gefunden wird das flag weggenommen
		            	} else if ( status.compareToIgnoreCase("EXECUTED") == 0) {
		            		newUmsatz.setSaldo(calculatedSaldo);
							calculatedSaldo -= newUmsatz.getBetrag();
		            	} else {
						// transaction still not executed
						newUmsatz.setFlags(Umsatz.FLAG_NOTBOOKED);
						// excluded from saldo setting
						log(Level.INFO, "Bitte einmal im Logfile (DEBUG OUT) nach `\"status\":\"` (!= EXECUTED) suchen und den Eintrag (amount und title kann geschw\u00E4rzt werden) dem Entwickler zusenden - es scheint weitere Umsatz-Stati zu geben, die bisher nicht bekannt sind. Danke");
					}

		            	var evT = transaction.optString("eventType");
		            	if ((evT.compareToIgnoreCase("card_successful_transaction") == 0) ||
	            			(evT.compareToIgnoreCase("OUTGOING_TRANSFER") == 0) ||
	            			(evT.compareToIgnoreCase("OUTGOING_TRANSFER_DELEGATION") == 0) ||
	            			(evT.compareToIgnoreCase("PAYMENT_OUTBOUND") == 0) ||
	            			(evT.compareToIgnoreCase("CARD_TRANSACTION") == 0) ||
	            			(evT.compareToIgnoreCase("card_order_billed") == 0) ||
	            			(evT.compareToIgnoreCase("card_successful_atm_withdrawal") == 0) )
		            			{
		            		newUmsatz.setArt("Kartenumsatz");
		            	}
		            	else if ((evT.compareToIgnoreCase("CARD_CASH_BACK") == 0) ) {
		            		newUmsatz.setArt("Kartenumsatz mit Geldabhebung");
		            	}
		            	else if ((evT.compareToIgnoreCase("card_failed_transaction") == 0) )
			            		{
			            		newUmsatz.setArt("Kartenumsatz Fehlgeschlagen/Storno");
		            	} 
		            	else if ((evT.compareToIgnoreCase("card_successful_verification") == 0) ||
		            			(evT.compareToIgnoreCase("CARD_VERIFICATION") == 0) ){
		            		newUmsatz.setArt("Kartenverifikation");
		            		
		            	} 
		            	else if ((evT.compareToIgnoreCase("trading_trade_executed") == 0) ||
		            			(evT.compareToIgnoreCase("ORDER_EXECUTED") == 0) ||
		            			(evT.compareToIgnoreCase("SAVINGS_PLAN_INVOICE_CREATED") == 0) ||
		            			(evT.compareToIgnoreCase("trading_savingsplan_executed") == 0) ||
		            			(evT.compareToIgnoreCase("trading_trade_executed") == 0) ||
		            			(evT.compareToIgnoreCase("benefits_spare_change_execution") == 0) ||
		            			(evT.compareToIgnoreCase("TRADE_INVOICE") == 0) ||
		            			(evT.compareToIgnoreCase("TRADE_CORRECTED") == 0) ||
		            			(evT.compareToIgnoreCase("SSP_CORPORATE_ACTION_CASH_AND_STOCK") == 0) ||
		            			(evT.compareToIgnoreCase("SSP_CORPORATE_ACTION_CASH") == 0) ||
		            			(evT.compareToIgnoreCase("timeline_legacy_migrated_events") == 0) ||
		            			(evT.compareToIgnoreCase("SAVINGS_PLAN_EXECUTED") == 0) ||
		            			(evT.compareToIgnoreCase("SSP_CORPORATE_ACTION_CASH_NON_DIVIDEND") == 0) ||
		            			(evT.compareToIgnoreCase("SSP_TAX_CORRECTION") == 0) ||
		            			(evT.compareToIgnoreCase("ssp_corporate_action_invoice_cash") == 0) )
		            			{
		            		newUmsatz.setArt("Trading ("+evT+")");
		            	} 
		            	else if ((evT.compareToIgnoreCase("SAVEBACK_AGGREGATE") == 0) ||
		            			(evT.compareToIgnoreCase("ACQUISITION_TRADE_PERK") == 0) ||  
		            			(evT.compareToIgnoreCase("benefits_saveback_execution") == 0) )
		            	{
		            		newUmsatz.setArt("Saveback Trading");
		            	}
		            	else if ( (evT.compareToIgnoreCase("INCOMING_TRANSFER_DELEGATION") == 0) ||  
		            			(evT.compareToIgnoreCase("INCOMING_TRANSFER") == 0) ||
		            			(evT.compareToIgnoreCase("BANK_TRANSACTION_INCOMING") == 0) ||
		            			(evT.compareToIgnoreCase("ACCOUNT_TRANSFER_INCOMING") == 0) ) {
		            		newUmsatz.setArt("Zahlungseingang");
		            	} 
		            	else if ((evT.compareToIgnoreCase("INTEREST_PAYOUT") == 0) || 
		            			(evT.compareToIgnoreCase("INTEREST_PAYOUT_CREATED") == 0) ) {
		            		newUmsatz.setArt("Zinzzahlung ");
		            	}
	            	
		            	else if (!evT.isBlank()) {
		            		newUmsatz.setArt("Sonstiges (evenType: " + evT +")");
		            	}
		            	else {
		            		newUmsatz.setArt("Sonstiges (kein eventType gesetzt)");
		            	}
		            	
		            	newUmsatz.setDatum(dateFormat.parse(transaction.getString("timestamp")));
		            	// wir haben keine Unterscheidung zwischen Valuta und Datum
		            	newUmsatz.setValuta(newUmsatz.getDatum());
		            	
		            	//newUmsatz.setCustomerRef(sR.optJSONObject("merchantDetails").optString("id"));
		            	
		            	var duplicate = getDuplicateById(newUmsatz); 
						if (duplicate != null)
						{	
							log(Level.DEBUG,"duplicate gefunden");
							duplicateRxFound = true;
							if (duplicate.hasFlag(Umsatz.FLAG_NOTBOOKED))
							{
								// compare by datum, id, betrag -> daher kein update
								duplicate.setFlags(newUmsatz.getFlags());
								duplicate.setSaldo(newUmsatz.getSaldo());
								duplicate.setWeitereVerwendungszwecke(newUmsatz.getWeitereVerwendungszwecke());
		
								duplicate.setGegenkontoBLZ(newUmsatz.getGegenkontoBLZ());
								duplicate.setGegenkontoName(newUmsatz.getGegenkontoName());
								duplicate.setGegenkontoNummer(newUmsatz.getGegenkontoNummer());
								duplicate.setValuta(newUmsatz.getValuta());
								duplicate.setArt(newUmsatz.getArt());
								duplicate.setCreditorId(newUmsatz.getCreditorId());
								duplicate.setMandateId(newUmsatz.getMandateId());
								duplicate.setCustomerRef(newUmsatz.getCustomerRef());
								duplicate.store();
								duplicatesRxNotBooked.add(duplicate);
								Application.getMessagingFactory().sendMessage(new ObjectChangedMessage(duplicate));
							}
						}
						else
						{
							neueUmsaetze.add(newUmsatz);
						}
	            	
            		}
            		catch (Exception ex)
					{
						log(Level.ERROR, "Fehler beim Anlegen vom Umsatz: " + ex.toString());
						log(Level.DEBUG,"trans: " + transaction.toString());
					}
            	} // for each transaction
            	
            	
            	updatePercentComplete(90);
				log(Level.INFO, "Kontoauszug erfolgreich. Importiere Daten ...");

				reverseImport(neueUmsaetze);
				
				updatePercentComplete(95);
				log(Level.INFO, "Import erfolgreich. Pr\u00FCfe Reservierungen ...");
				
				umsaetze.begin();
				while (umsaetze.hasNext())
				{
					Umsatz umsatz = umsaetze.next();
					if (umsatz.hasFlag(Umsatz.FLAG_NOTBOOKED))
					{
						if (!duplicatesRxNotBooked.contains(umsatz))
						{
							var id = umsatz.getID();
							umsatz.delete();
							Application.getMessagingFactory().sendMessage(new ObjectDeletedMessage(umsatz, id));
						}
					}
				}
				
            } // if fetchUmsatz

        } finally {
            try {
            		log(Level.INFO, "Stop Websocketclient in background ...");
                client.stop();
            } catch (Exception e) {
                e.printStackTrace();
            }
            
            // Logout
 			try 
 			{
 				response = doRequest(TRADEREP_LOGOUT_URL, 
 						HttpMethod.POST, headers, "application/json", 
 						null);
 			}
 			catch (Exception e) {}
 			
 			try {
 				if (pwrt != null) {
 					log(Level.INFO, "Stop PlayWright in background ...");
 					pwrt.stopRunning();
 					log(Level.DEBUG, "join PlayWrightRunnerThread waiting for finishing browser");
 					pwrt.join(40000 /* more than default for Playwright.waitForResponse() */);
 					log(Level.DEBUG, "stopped PlayWrightRunnerThread");
 				}
 			} catch (Exception e) {}
        }
		return true;
	}

	/**
	 * Sets x-aws-waf-token and Cookie header with the current token and the session cookies.
	 */
	private void updateRequestAuthHeaders(ArrayList<KeyValue<String, String>> headers) {
		String awsWafToken = pwrt.awsWafToken;
		replaceOrAddArrayListEntry(headers, new KeyValue<String, String>("x-aws-waf-token", awsWafToken));
		replaceOrAddArrayListEntry(headers, new KeyValue<String, String>("Cookie", buildCookieHeader(awsWafToken)));
	}

	/**
	 * Builds the cookie header from the session cookies received so far plus the given AWS WAF token.
	 */
	private String buildCookieHeader(String awsWafToken) {
		var cookies = new ArrayList<String>();
		sessionCookies.forEach((name, value) -> cookies.add(name + "=" + value));
		cookies.add("aws-waf-token=" + awsWafToken);
		return String.join("; ", cookies);
	}

	/**
	 * Takes over all cookies of set-cookie headers of the response (later values replace earlier ones).
	 */
	private void collectSessionCookies(WebResult response, String step) {
		response.getResponseHeader().forEach(nvp -> {
			if (nvp.getName().compareToIgnoreCase("set-cookie") == 0) {
				// one header may contain several cookies separated by newline
				for (String cookie : nvp.getValue().split("\n")) {
					String nameValue = cookie.split(";", 2)[0].trim();
					int eq = nameValue.indexOf('=');
					if (eq <= 0) {
						continue;
					}
					String name = nameValue.substring(0, eq);
					String value = nameValue.substring(eq + 1);
					if (name.equals("aws-waf-token")) {
						// always taken from the browser
						continue;
					}
					if (value.isEmpty()) {
						// cookie deleted by server
						sessionCookies.remove(name);
					} else {
						sessionCookies.put(name, value);
					}
					log(Level.DEBUG, step + " set-cookie: " + name);
				}
			}
		});
	}

	/**
	 * The API expects the phone number in international format (e.g. +49172...).
	 * Assumption for numbers without country code: german number.
	 */
	private static String normalizePhoneNumber(String user) {
		String phone = user.replaceAll("[\\s/()-]", "");
		if (phone.startsWith("+")) {
			return phone;
		}
		if (phone.startsWith("00")) {
			return "+" + phone.substring(2);
		}
		if (phone.startsWith("0")) {
			return "+49" + phone.substring(1);
		}
		return phone;
	}

	private static boolean isBlank(String value) {
		return (value == null) || value.isBlank();
	}
}
