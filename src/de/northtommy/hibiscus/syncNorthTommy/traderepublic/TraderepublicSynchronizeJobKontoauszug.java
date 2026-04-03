package de.northtommy.hibiscus.syncNorthTommy.traderepublic;


import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.rmi.RemoteException;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;


import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import javax.annotation.Resource;

import org.htmlunit.HttpMethod;
import org.json.JSONArray;
import org.json.JSONObject;

import de.northtommy.hibiscus.syncNorthTommy.KeyValue;
import de.northtommy.hibiscus.syncNorthTommy.SyncNTSynchronizeJob;
import de.northtommy.hibiscus.syncNorthTommy.SyncNTSynchronizeJobKontoauszug;
import de.northtommy.hibiscus.syncNorthTommy.SyncNTSynchronizeJobKontoauszugLoggerI;
import de.northtommy.hibiscus.syncNorthTommy.WebResult;
import de.northtommy.hibiscus.syncNorthTommy.traderepublic.TraderepublicWebSocket.RxState;
import de.northtommy.hibiscus.syncNorthTommy.PlayWrightRunnerThread;

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
import de.willuhn.logging.Logger;
import de.willuhn.util.ApplicationException;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Browser.NewContextOptions;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Page.ScreenshotOptions;
import com.microsoft.playwright.Route.FulfillOptions;
import com.microsoft.playwright.options.ScreenshotAnimations;
import com.microsoft.playwright.options.ScreenshotType;
import io.github.kihdev.playwright.stealth4j.Stealth4j;
import io.github.kihdev.playwright.stealth4j.Stealth4jConfig;

// Spezifisch, eigentliche Implementierung

public class TraderepublicSynchronizeJobKontoauszug extends SyncNTSynchronizeJobKontoauszug implements SyncNTSynchronizeJob , SyncNTSynchronizeJobKontoauszugLoggerI
{

	//private static final String TRADEREP_PLAYWRIGTH_HOME = "https://traderepublic.com/";
	private static final String TRADEREP_PLAYWRIGTH_LOGIN = "https://app.traderepublic.com/login";
	
	private static final String TRADEREP_LOGIN_URL = "https://api.traderepublic.com/api/v1/auth/web/login";
	private static final String TRADEREP_ACCOUNT_URL = "https://api.traderepublic.com/api/v2/auth/account";
	private static final String TRADEREP_WSS_URL = "wss://api.traderepublic.com/";
	private static final String TRADEREP_LOGOUT_URL = "https://api.traderepublic.com/api/v1/auth/web/logout";
	
	private PlayWrightRunnerThread pwrt = null;
	
	private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
	

	@Resource
	private TraderepublicSynchronizeBackend backend = null;

	@Override
	protected SynchronizeBackend getBackend() { return backend; }

	@Override
	public boolean process(Konto konto, boolean fetchSaldo, boolean fetchUmsatz, DBIterator<Umsatz> umsaetze, String user, String passwort) throws Exception
	{
		boolean headlessBrowser = "true".equals(konto.getMeta(TraderepublicSynchronizeBackend.META_NOTHEADLESS, "true"));
		String firefoxPath = konto.getMeta(TraderepublicSynchronizeBackend.META_FIREFOXPATH,  null);
		
		this.pwrt = new PlayWrightRunnerThread(this, firefoxPath, headlessBrowser, webClient, TRADEREP_PLAYWRIGTH_LOGIN);

		ArrayList<KeyValue<String, String>> headers = new ArrayList<>();
		
		log(Level.INFO, "Warte auf AWS WAF token vom Browser");
		this.pwrt.start();
		// waiting playwright tread got a first token
		int pwrtTimeout = 120; /* times 1s -> 2min */
		while (pwrt.isAlive() && (pwrt.awsWafToken == null) && (pwrtTimeout-- > 0)) {
			Thread.sleep(1000);
			log(Level.INFO, ".");
		}
		if (!pwrt.isAlive()) {
			log(Level.WARN, "unable to start playwright with background browser");
			throw new ApplicationException("Fehler beim Starten von Playwright fuer AWS WAF token");
		}
		if (pwrt.awsWafToken == null) {
			log(Level.WARN, "got no AWS WAF token in time");
			throw new ApplicationException("Zeitueberschreitung AWS WAF token");
		}
		
		// add default headers for any communication
		headers.add(new KeyValue<String, String>("Accept", "*/*"));
		headers.add(new KeyValue<String, String>("Accept-Language", "en"));
		headers.add(new KeyValue<String, String>("Accept-Encoding", "gzip, deflate, br, zstd"));
		//headers.add(new KeyValue<String, String>("Cache-Control", "no-cache"));
		//headers.add(new KeyValue<String, String>("Connection", "keep-alive"));
		//headers.add(new KeyValue<String, String>("Host", "api.traderepublic.com"));
		//headers.add(new KeyValue<String, String>("Origin", "https://app.traderepublic.com"));
		//headers.add(new KeyValue<String, String>("Pragma", "no-cache"));
		//headers.add(new KeyValue<String, String>("Sec-Fetch-Dest", "empty"));
		//headers.add(new KeyValue<String, String>("Sec-Fetch-Mode", "cors"));
		//headers.add(new KeyValue<String, String>("Sec-Fetch-Site", "same-site"));
		//headers.add(new KeyValue<String, String>("TE", "trailers"));
		headers.add(new KeyValue<String, String>("X-TR-Platform", "web"));
		//headers.add(new KeyValue<String, String>("X-TR-App-Version", "3.296.0"));
		String awsWafToken = pwrt.awsWafToken;
		headers.add(new KeyValue<String, String>("x-aws-waf-token", awsWafToken));
		var cookieHeaderEntry = new KeyValue<String, String>("Cookie", String.join("; ",
				"aws-waf-token=" + awsWafToken
				));
		headers.add(cookieHeaderEntry);
		  
		// Perform Pre Login somehow needed for login request, although returned data seems to be constant regarding those data used in login request
		
		WebResult response = doRequest(TRADEREP_LOGIN_URL, HttpMethod.POST, headers, "application/json", 
				"{\"phoneNumber\":\"" + user + "\",\"pin\":\"" + passwort + "\"}");
		
		if (response.getHttpStatus() != 200) {
			// try with new AWS WAF token
			Thread.sleep(100);

			// duplicate code above
			awsWafToken = pwrt.awsWafToken;
			replaceArrayListEntry(headers, new KeyValue<String, String>("x-aws-waf-token", awsWafToken));
			cookieHeaderEntry = new KeyValue<String, String>("Cookie", String.join("; ",
					"aws-waf-token=" + awsWafToken
					));
			replaceArrayListEntry(headers, cookieHeaderEntry);
			// retry login call
			response = doRequest(TRADEREP_LOGIN_URL, HttpMethod.POST, headers, "application/json", 
					"{\"phoneNumber\":\"" + user + "\",\"pin\":\"" + passwort + "\"}");
		
			if (response.getHttpStatus() != 200) {
				log(Level.DEBUG, "login Step 1 response: " + response.getContent());
				throw new ApplicationException("Login Step 1 fehlgeschlagen");
			}
		}
		
		var json = response.getJSONObject();
		log(Level.DEBUG, "Login step 1 response: " + json);
		// processId needed for URL for sending SMS-TAN
		String processId = json.getString("processId");
		// sessionId needed for cookie when sending SMS-TAN
		String sessId[] = {""};
		String tr_device[] = {""};
		response.getResponseHeader().forEach(nvp -> {
			log(Level.DEBUG, "Login Step 1 header: " + nvp.getName() + ": " + nvp.getValue());
			if (nvp.getName().compareToIgnoreCase("set-cookie") == 0) {
				var val = nvp.getValue();
				String[] vals = val.split(";");
				for (String v : vals) {
					if (v.startsWith("JSESSIONID")) {
						sessId[0] = v.substring(val.indexOf("JSESSIONID=") + 11);
					}
					if (v.startsWith("tr_device")) {
						tr_device[0] = v.substring(val.indexOf("tr_device=") + 10);
					}
				}
			}
		});
		log(Level.DEBUG, "Login Step 1 JSESSIONID: " + sessId[0]);
		log(Level.DEBUG, "Login Step 1 tr_device: " + tr_device[0]);
		log(Level.DEBUG, "Login Step 1 processId: " + processId);
		
		
		
		var requestText = "Gib den Code ein, den du per Traderepublic App erhalten hast";
		
		var sca = Application.getCallback().askUser(requestText, "Code:");
		if (sca == null || sca.isBlank())
		{
			log(Level.WARN, "Login abgebrochen");
			return true;
		}
		
		
		awsWafToken = pwrt.awsWafToken;
		replaceArrayListEntry(headers, new KeyValue<String, String>("x-aws-waf-token", awsWafToken));
		cookieHeaderEntry = new KeyValue<String, String>("Cookie", String.join("; ",
				"JSESSIONID=" + sessId[0],
				"tr_device=" + tr_device[0],
				"aws-waf-token=" + awsWafToken
				));
		replaceArrayListEntry(headers, cookieHeaderEntry);
		
			
		response = doRequest(TRADEREP_LOGIN_URL + "/"+ processId + "/" + sca, 
				HttpMethod.POST, headers, "application/json", 
				null);
		
		if (response.getHttpStatus() != 200) {
			// try with new AWS WAF token
			Thread.sleep(100);

			// duplicate code above
			awsWafToken = pwrt.awsWafToken;
			replaceArrayListEntry(headers, new KeyValue<String, String>("x-aws-waf-token", awsWafToken));
			cookieHeaderEntry = new KeyValue<String, String>("Cookie", String.join("; ",
					"JSESSIONID=" + sessId[0],
					"aws-waf-token=" + awsWafToken
					));
			replaceArrayListEntry(headers, cookieHeaderEntry);
			// retry TAN call
			response = doRequest(TRADEREP_LOGIN_URL + "/"+ processId + "/" + sca, 
					HttpMethod.POST, headers, "application/json", 
					null);
			if (response.getHttpStatus() != 200) {
				log(Level.DEBUG, "Login Step 2 Response: " + response.getContent());
				throw new ApplicationException("Login Step 2 fehlgeschlagen");
			}
		}
		log(Level.DEBUG, "Login Step 2 Response: " + response.getContent());
		
		
		// with TAN-call we got further data
		
		// seddId is reused and updated
		String tr_session[] = {""};
		String tr_claims[] = {""};
		String tr_external_id[] = {""};
		String tr_refresh[] = {""};
		
		response.getResponseHeader().forEach(nvp -> {
			log(Level.DEBUG, "Login Step 2 header: " + nvp.getName() + ": " + nvp.getValue());
			if (nvp.getName().compareToIgnoreCase("set-cookie") == 0) {
				var val = nvp.getValue();
				String[] vals = val.split(";");
				for (String v : vals) {
					if (v.startsWith("JSESSIONID")) {
						sessId[0] = v.substring(val.indexOf("JSESSIONID=") + 11);
					}
					if (v.startsWith("tr_session")) {
						tr_session[0] = v.substring(val.indexOf("tr_session=") + 11);
					}
					if (v.startsWith("tr_claims")) {
						tr_claims[0] = v.substring(val.indexOf("tr_claims=") + 10);
					}
					if (v.startsWith("tr_refresh")) {
						tr_refresh[0] = v.substring(val.indexOf("tr_refresh=") + 11);
					}
					if (v.startsWith("tr_external_id")) {
						tr_external_id[0] = v.substring(val.indexOf("tr_external_id=") + 15);
					}
				}
			}
		});
		
		log(Level.DEBUG, "Login step 2 new JSESSIONID: " + sessId[0]);
		log(Level.DEBUG, "Login step 2 tr_session: " + tr_session[0]);
		log(Level.DEBUG, "Login step 2 tr_claims: " + tr_claims[0]);
		log(Level.DEBUG, "Login step 2 tr_device: " + tr_device[0]);
		log(Level.DEBUG, "Login step 2 tr_external_id: " + tr_external_id[0]);
		
		awsWafToken = pwrt.awsWafToken;
		replaceArrayListEntry(headers, new KeyValue<String, String>("x-aws-waf-token", awsWafToken));
		
		cookieHeaderEntry = new KeyValue<String, String>("Cookie", String.join("; ",
				"JSESSIONID=" + sessId[0],
				"tr_session=" + tr_session[0],
				"tr_claims=" + tr_claims[0],
				"tr_device=" + tr_device[0],
				"tr_refresh=" + tr_refresh[0],
				"tr_external_id=" + tr_external_id[0],
				"aws-waf-token=" + awsWafToken
				));
		replaceArrayListEntry(headers, cookieHeaderEntry);
		
		
		updatePercentComplete(5);
		log(Level.INFO, "Login erfolgreich.");
		
		
		log(Level.INFO, "Hole Kontodaten.");
		response = doRequest(TRADEREP_ACCOUNT_URL, 
				HttpMethod.GET, headers, "application/json", 
				null);
		
		if (response.getHttpStatus() != 200) {
			// try with new AWS WAF token
			Thread.sleep(100);

			// duplicate code above
			awsWafToken = pwrt.awsWafToken;
			replaceArrayListEntry(headers, new KeyValue<String, String>("x-aws-waf-token", awsWafToken));
			cookieHeaderEntry = new KeyValue<String, String>("Cookie", String.join("; ",
					"JSESSIONID=" + sessId[0],
					"tr_session=" + tr_session[0],
					"tr_claims=" + tr_claims[0],
					"tr_device=" + tr_device[0],
					"tr_refresh=" + tr_refresh[0],
					"tr_external_id=" + tr_external_id[0],
			
					"aws-waf-token=" + awsWafToken
					));
			replaceArrayListEntry(headers, cookieHeaderEntry);
			
			// retry account call
			response = doRequest(TRADEREP_ACCOUNT_URL, 
					HttpMethod.GET, headers, "application/json", 
					null);
			
			if (response.getHttpStatus() != 200) {
				log(Level.DEBUG, "Response: " + response.getContent());
				throw new ApplicationException("Holen der Kontodaten fehlgeschlagen");
			}
		}
		
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
        TraderepublicWebSocket socket = new TraderepublicWebSocket(this, "14.23.3", untilDate);

        try {
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
            
            // Add cookies as header
            request.setHeader("Cookie", String.join("; ",
                "i18n_redirected=en",
                "tr_appearance=Light",
                "JSESSIONID=" + sessId[0],
                "tr_session=" + tr_session[0],
                "tr_claims=" + tr_claims[0],
                "tr_device" + tr_device[0],
                "tr_refresh=" + tr_refresh[0],
                "tr_external_id" + tr_external_id[0],
                "aws-waf-token=" + awsWafToken
                // ... other cookies
            ));

            client.connect(socket, echoUri, request);
            log(Level.DEBUG, "WSS Connecting to: " + echoUri);

            long syncStart = System.currentTimeMillis();
            
            while (((socket.getRxState() != TraderepublicWebSocket.RxState.FINISHED)) && ((System.currentTimeMillis() - syncStart) < 60000)) {
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
            			
						var status = transaction.getString("status");
		            	if ( status.compareToIgnoreCase("CANCELED") == 0) {
		            		// intentionally no saldo setting
		            	} else if ( status.compareToIgnoreCase("EXECUTED") == 0) {
		            		newUmsatz.setSaldo(calculatedSaldo);
							calculatedSaldo -= newUmsatz.getBetrag();
		            	} else {
							// transaction still not 
							newUmsatz.setFlags(Umsatz.FLAG_NOTBOOKED);
							// excluded from saldo setting
							log(Level.INFO, "Bitte einmal im Logfile nach `\"status\":\"` (!= EXECUTED) suchen und den Eintrag (amount und title kann geschw\u00E4rzt werden) dem Entwickler zusenden - es scheint weitere Umsatz-Stati zu geben, die bisher nicht bekannt sind. Danke");
						}

		            	var evT = transaction.optString("eventType");
		            	if ((evT.compareToIgnoreCase("card_successful_transaction") == 0) ||
	            			(evT.compareToIgnoreCase("OUTGOING_TRANSFER") == 0) ||
	            			(evT.compareToIgnoreCase("OUTGOING_TRANSFER_DELEGATION") == 0) ||
	            			(evT.compareToIgnoreCase("PAYMENT_OUTBOUND") == 0) ||
	            			(evT.compareToIgnoreCase("card_order_billed") == 0) ||
	            			(evT.compareToIgnoreCase("card_successful_atm_withdrawal") == 0) )
		            			{
		            		newUmsatz.setArt("Kartenumsatz");
		            	}
		            	else if ((evT.compareToIgnoreCase("card_failed_transaction") == 0) )
			            		{
			            		newUmsatz.setArt("Kartenumsatz Fehlgeschlagen/Storno");
		            	} 
		            	else if (evT.compareToIgnoreCase("card_successful_verification") == 0) {
		            		newUmsatz.setArt("Kartenverifikation");
		            	} 
		            	else if ((evT.compareToIgnoreCase("trading_trade_executed") == 0) ||
		            			(evT.compareToIgnoreCase("ORDER_EXECUTED") == 0) ||
		            			(evT.compareToIgnoreCase("SAVINGS_PLAN_EXECUTED") == 0) ||
		            			(evT.compareToIgnoreCase("SAVINGS_PLAN_INVOICE_CREATED") == 0) ||
		            			(evT.compareToIgnoreCase("trading_savingsplan_executed") == 0) ||
		            			(evT.compareToIgnoreCase("trading_trade_executed") == 0) ||
		            			(evT.compareToIgnoreCase("benefits_spare_change_execution") == 0) ||
		            			(evT.compareToIgnoreCase("TRADE_INVOICE") == 0) ||
		            			(evT.compareToIgnoreCase("TRADE_CORRECTED") == 0) ||
		            			(evT.compareToIgnoreCase("timeline_legacy_migrated_events") == 0) )
		            			{
		            		newUmsatz.setArt("Trading");
		            	} 
		            	else if (evT.compareToIgnoreCase("ssp_corporate_action_invoice_cash") == 0) {
		            		newUmsatz.setArt("Dividendeneingang");
		            	} 
		            	else if ( (evT.compareToIgnoreCase("INCOMING_TRANSFER_DELEGATION") == 0) ||  
		            			(evT.compareToIgnoreCase("INCOMING_TRANSFER") == 0) ||
		            			(evT.compareToIgnoreCase("ACCOUNT_TRANSFER_INCOMING") == 0) ) {
		            		newUmsatz.setArt("Zahlungseingang");
		            	} 
		            	else if ( (evT.compareToIgnoreCase("ACQUISITION_TRADE_PERK") == 0) ||  
		            			(evT.compareToIgnoreCase("benefits_saveback_execution") == 0) ) {
		            		newUmsatz.setArt("Saveback Trading");
		            	} 
		            	else if ((evT.compareToIgnoreCase("INTEREST_PAYOUT") == 0) || 
		            			(evT.compareToIgnoreCase("INTEREST_PAYOUT_CREATED") == 0) ) {
		            		newUmsatz.setArt("Zinzzahlung ");
		            	}
	            	
		            	else if (!evT.isBlank()) {
		            		newUmsatz.setArt("Sonstiges (evenType: " + evT +")");
		            	}
		            	
		            	newUmsatz.setDatum(dateFormat.parse(transaction.getString("timestamp")));
		            	// wir haben keine Unterscheidung zwischen Valuta und Datum
		            	newUmsatz.setValuta(newUmsatz.getDatum());
		            	
		            	String vz = transaction.getString("title"); 
		            	String vz2 = transaction.optString("subtitle");
            			if (!vz2.isBlank()) {
            				vz = vz + " (" +  vz2 + ")";
            			}
		            	newUmsatz.setZweck(vz);
		            	
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
 					log(Level.DEBUG, "stop PlayWrightRunnerThread");
 					pwrt.stopRunning();
 					log(Level.DEBUG, "join PlayWrightRunnerThread waiting for finishing browser");
 					pwrt.join(40000 /* more than defailt for Playwright.waitForResponse() */);
 					log(Level.DEBUG, "stopped PlayWrightRunnerThread");
 				}
 			} catch (Exception e) {}
        }
		return true;
	}
}
