package de.northtommy.hibiscus.syncNorthTommy.traderepublic;


import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.*;
import java.util.concurrent.CountDownLatch;


import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.TimeZone;
import java.util.UUID;

import javax.annotation.Resource;

import org.htmlunit.HttpMethod;
import org.json.JSONArray;
import org.json.JSONObject;

import de.northtommy.hibiscus.syncNorthTommy.KeyValue;
import de.northtommy.hibiscus.syncNorthTommy.SyncNTSynchronizeJob;
import de.northtommy.hibiscus.syncNorthTommy.SyncNTSynchronizeJobKontoauszug;
import de.northtommy.hibiscus.syncNorthTommy.WebResult;
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

// Spezifisch, eigentliche Implementierung

public class TraderepublicSynchronizeJobKontoauszug extends SyncNTSynchronizeJobKontoauszug implements SyncNTSynchronizeJob 
{
	@WebSocket
	public class SimpleWebSocket {

	    private final CountDownLatch closeLatch = new CountDownLatch(1);
	    private Session session;
	    private boolean protoConnected = false;
	    
	    public boolean awaitClose(int duration, TimeUnit unit) throws InterruptedException {
	        return closeLatch.await(duration, unit);
	    }

	    @OnWebSocketConnect
	    public void onConnect(Session session) {
	        System.out.println("Connected!");
	        this.session = session;
	        try {
				session.getRemote().sendString("connect 31 {\"locale\":\"en\",\"platformId\":\"webtrading\",\"platformVersion\":\"firefox - 140.0.0\",\"clientId\":\"app.traderepublic.com\",\"clientVersion\":\"3.296.0\"}");
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} // you can send messages here
	    }

	    @OnWebSocketMessage
	    public void onMessage(String msg) {
	        System.out.println("Received message: " + msg);
	        
	        try {
	        	if ( !protoConnected) {
	        		this.session.getRemote().sendString("sub 1 {\"type\":\"availableCash\"}");
	        		this.session.getRemote().sendString("sub 2 {\"type\":\"timelineTransactions\"}");
	        	}
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
	        protoConnected = true;
	    }

	    @OnWebSocketClose
	    public void onClose(int statusCode, String reason) {
	        System.out.println("Connection closed: " + reason);
	        closeLatch.countDown();
	    }

	    @OnWebSocketError
	    public void onError(Throwable cause) {
	        System.err.println("WebSocket error: " + cause.getMessage());
	    }
	}
	
	
	private static final String TRADEREP_LOGIN_URL = "https://api.traderepublic.com/api/v1/auth/web/login";
	private static final String TRADEREP_ACCOUNT_URL = "https://api.traderepublic.com/api/v2/auth/account";
	
	
	@Resource
	private TraderepublicSynchronizeBackend backend = null;

	@Override
	protected SynchronizeBackend getBackend() { return backend; }

	@Override
	public boolean process(Konto konto, boolean fetchSaldo, boolean fetchUmsatz, DBIterator<Umsatz> umsaetze, String user, String passwort) throws Exception
	{
		
		ArrayList<KeyValue<String, String>> headers = new ArrayList<>();
		
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
		
		
		  
		// Perform Pre Login somehow needed for login request, although returned data seems to be constant regarding those data used in login request
		
		WebResult response = doRequest(TRADEREP_LOGIN_URL, HttpMethod.POST, headers, "application/json", 
				"{\"phoneNumber\":\"" + user + "\",\"pin\":\"" + passwort + "\"}");
		
		if (response.getHttpStatus() != 200) {
			log(Level.DEBUG, "Response: " + response.getContent());
			throw new ApplicationException("Login Step 1 fehlgeschlagen");
		}
		
		var json = response.getJSONObject();

		String sessId[] = {""};
		response.getResponseHeader().forEach(nvp -> {
			log(Level.DEBUG, "login header: " + nvp.getName() + ": " + nvp.getValue());
			if (nvp.getName().compareToIgnoreCase("set-cookie") == 0) {
				var val = nvp.getValue();
				String[] vals = val.split(";");
				for (String v : vals) {
					if (v.startsWith("JSESSIONID")) {
						sessId[0] = v.substring(val.indexOf("JSESSIONID=") + 11);
					}
				}
			}
		});
		
		log(Level.DEBUG, "JSESSIONID: " + sessId[0]);
		log(Level.DEBUG, "login str: " + json);
		
		
		
		
		
		var requestText = "Gib den Code ein, den du per Traderepublic App erhalten hast";
		//
		var sca = Application.getCallback().askUser(requestText, "Code:");
		if (sca == null || sca.isBlank())
		{
			log(Level.WARN, "Login abgebrochen");
			return true;
		}
		
		headers.add(new KeyValue<String, String>("Cookie", String.join("; ",
				"JSESSIONID=" + sessId[0]
				)));
			
		response = doRequest(TRADEREP_LOGIN_URL + "/"+ json.getString("processId") + "/" + sca, 
				HttpMethod.POST, headers, "application/json", 
				null);
		
		if (response.getHttpStatus() != 200) {
			log(Level.DEBUG, "Response: " + response.getContent());
			throw new ApplicationException("Login Step 2 fehlgeschlagen");
		}
		log(Level.DEBUG, "Login2 Response: " + response.getContent());
		
		
		
		
		String tr_session[] = {""};
		String tr_claims[] = {""};
		String tr_device[] = {""};
		String tr_external_id[] = {""};
		
		response.getResponseHeader().forEach(nvp -> {
			log(Level.DEBUG, "login header: " + nvp.getName() + ": " + nvp.getValue());
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
					if (v.startsWith("tr_device")) {
						tr_device[0] = v.substring(val.indexOf("tr_device=") + 10);
					}
					if (v.startsWith("tr_external_id")) {
						tr_external_id[0] = v.substring(val.indexOf("tr_external_id=") + 15);
					}
				}
			}
		});
		
		log(Level.DEBUG, "JSESSIONID: " + sessId[0]);
		log(Level.DEBUG, "tr_session: " + tr_session[0]);
		log(Level.DEBUG, "tr_claims: " + tr_claims[0]);
		log(Level.DEBUG, "tr_device: " + tr_device[0]);
		log(Level.DEBUG, "tr_external_id: " + tr_external_id[0]);
		
		
		
		for (int i = 0; i < headers.size(); i++ ) {
			if ( headers.get(i).getKey().compareToIgnoreCase("Cookie")== 0 ) {
				headers.remove(headers.get(i));
				break;
			}
		};
		headers.add(new KeyValue<String, String>("Cookie", String.join("; ",
				"JSESSIONID=" + sessId[0],
				"tr_session=" + tr_session[0],
				"tr_claims=" + tr_claims[0],
				"tr_device=" + tr_device[0],
				"tr_external_id=" + tr_external_id[0]
				)));
			
		response = doRequest(TRADEREP_ACCOUNT_URL, 
				HttpMethod.GET, headers, "application/json", 
				null);
		
		if (response.getHttpStatus() != 200) {
			log(Level.DEBUG, "Response: " + response.getContent());
			throw new ApplicationException("Get Account info fehlgeschlagen");
		}
		
		json = response.getJSONObject();
		
		log(Level.DEBUG, "Account Response: " + json);
		
		
		
		
		
		
		
		String destUri = "wss://api.traderepublic.com/";

        WebSocketClient client = new WebSocketClient();
        SimpleWebSocket socket = new SimpleWebSocket();

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
            
            // Add cookies as header
            request.setHeader("Cookie", String.join("; ",
                "i18n_redirected=en",
                "tr_appearance=Light",
                "JSESSIONID=" + sessId[0],
                "tr_session=" + tr_session[0],
                "tr_claims=" + tr_claims[0],
                "tr_device" + tr_device[0],
                "tr_external_id" + tr_external_id[0]
                // ... other cookies
            ));

            client.connect(socket, echoUri, request);
            System.out.println("Connecting to: " + echoUri);

            socket.awaitClose(10, TimeUnit.SECONDS);

        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            try {
                client.stop();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
		
		
		
		
//	
//		
//		ArrayList<KeyValue<String, String>> headers = new ArrayList<>();
//
//		if ((konto.getUnterkonto().isBlank()) || (konto.getUnterkonto().length() != 4)) {
//			log(Level.ERROR,"Unterkonto (siehe Kontodaten) muss mit den letzten 4 Ziffern der Kreditkarte gespeichert sein.");
//			throw new ApplicationException("Fehlende Unterkonto-Information");
//		}
//
//		
//		// add default headers for any communication
//		headers.add(new KeyValue<String, String>("Accept", "*/*"));
//		headers.add(new KeyValue<String, String>("Accept-Language", "de-DE"));
//		headers.add(new KeyValue<String, String>("applicationLocale", "de-DE"));
//		  
//		// Perform Pre Login somehow needed for login request, although returned data seems to be constant regarding those data used in login request
//		
//		WebResult response = doRequest(AMZ_ZINIA_PRE_LOGIN_URL, HttpMethod.POST, headers, "application/json", 
//				"{\"documentType\":\"email\",\"username\":\"" + user + "\"}");
//		
//		if (response.getHttpStatus() != 200) {
//			log(Level.DEBUG, "Response: " + response.getContent());
//			throw new ApplicationException("(Pre-)Login fehlgeschlagen");
//		}
//		
//		var json = response.getJSONObject();
//		
//		/* we expect s.th. like
//		 * {"positions":["1","2","3","4"],"nonce":"FcOC9/Cw/VQ0TXvgsuLmj+fRyn8d2wupoMVPaoJDIUs="}
//		 */
//		var pwdPositions = json.optJSONArray("positions");
//		//log(Level.DEBUG, "pwdPositions: \"" + pwdPositions.toString() + "\"");
//		if ( (pwdPositions.length() != 4) ||
//				(!pwdPositions.getString(0).equals("1")) ||
//				(!pwdPositions.getString(1).equals("2")) ||
//				(!pwdPositions.getString(2).equals("3")) ||
//				(!pwdPositions.getString(3).equals("4"))
//				)
//		{
//			log(Level.DEBUG, "Response: " + response.getContent());
//			log(Level.DEBUG, "Response: " + json);
//			throw new ApplicationException("(Pre-)Login fehlgeschlagen - unerwartete Antwort");
//		}
//
//		
//		// Perform login request
//		
//		response = doRequest(AMZ_ZINIA_LOGIN_URL, HttpMethod.POST, headers, "application/json", 
//				"{ " +
//					"\"deviceInformation\": " +
//					"{ " +
//						"\"deviceUUID\":\"" + UUID.randomUUID().toString() + "\", " +
//						"\"webDeviceInfo\": {\"version\":\"Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0\"} " +
//					"}, " +
//					"\"language\":\"de-DE\", " +
//					"\"documentType\":\"email\", " + 
//					"\"isRememberMe\":false,  " +
//					"\"password\":\"" + passwort + "\", " +
//					"\"passwordPosition\":" + pwdPositions.toString() + " , " +
//					"\"username\":\"" + user + "\", " +
//					"\"customerSessionId\":null " +
//				"}"	);
//		
//		if (response.getHttpStatus() != 200) {
//			log(Level.DEBUG, "Response: " + response.getContent());
//			throw new ApplicationException("Login fehlgeschlagen");
//		}
//		
//		json = response.getJSONObject();
//
//		/* we expctect s.th. like
//		 * {
//				"accessToken": "x0dL5NZoEGy_37nLt57oCmIS3Rv6RTIr_Wt$nBojB$U=",
//				"refreshToken": "xYxbVptCNmuGQ5d4nfRCjVhwyEHEdCL2g5q_E7ukIBY=",
//				"sessionExpiresAt": "2025-07-22T13:28:28.362998003Z",
//				"expiresIn": 299914
//			}
//		 */
//		// only accessToken is used for this usecase as we are fast enough within std session timeout
//		var accessToken =  json.getString("accessToken");
//		
//		
//		boolean scaDone[] = { false };
//		int monitorComplete = 0;
//		
//		// from here on we should logout finally
//		try {
//			// for all further requests we need the accesstoken in header
//			headers.add(new KeyValue<String, String>("accessToken", accessToken));
//			
//			Logger.info("Login war erfolgreich");
//			monitorComplete = 5;
//			monitor.setPercentComplete(monitorComplete);
//
//			// Set during fetchSaldo from list of cards and compared to Unterkonto
//			// used for fetch saldo and compare with each transaction fetching transactions
//			final String cardId[] = {""};
//
//			response = doRequest(AMZ_ZINIA_SALDO_URL, HttpMethod.GET, headers, "application/json", null);
//			if (response.getHttpStatus() != 200) {
//				log(Level.DEBUG, "Response: " + response.getContent());
//				throw new ApplicationException("Abruf des Saldo fehlgeschlagen.");
//			}
//			json = response.getJSONObject();
//			JSONArray elements = json.getJSONArray("elements");
//
//			for (int i=0; i < elements.length(); i++) {
//				JSONObject jo = elements.getJSONObject(i);
//				var last4DigitsPan = jo.getString("last4DigitsPan");
//				if (konto.getUnterkonto().equals(last4DigitsPan)) {
//					cardId[0] = jo.getString("id");
//					log(Level.DEBUG, "Karte laut Unterkonto gefunden (letzten 4 Stellen) " + last4DigitsPan + " als ID: ..." + cardId[0].substring(cardId[0].length()-6));
//					
//					if (fetchSaldo) {
//						//double saldoValue = jo.getJSONObject("creditDetails").getDouble("currentAmountAuthorized");
//						double saldoValue = jo.getJSONObject("creditDetails").getDouble("currentBalance");
//						konto.setSaldo(-1.0 * saldoValue);
//						
//						double cashCreditAvail = jo.getJSONObject("creditDetails").getDouble("cashCreditAvail");
//						konto.setSaldoAvailable(cashCreditAvail);
//
//						konto.store();
//						Application.getMessagingFactory().sendMessage(new SaldoMessage(konto));
//						
//						log(Level.INFO, "Saldo abrufen erfolgreich.");
//					}
//				}
//			}
//			if (cardId[0].isBlank()) {
//				log(Level.WARN, "keine passende Karte mit den Endziffernt laut Unterkonto (siehe Kontodaten) gefunden.");
//			}
//					
//
//			if (fetchUmsatz) {
//				/*
//				 * Grobzusammenfassung des Ablaufs (Stand 20250729):
//				 * - nach Login (default header ab jetzt mit token)
//				 * - request transaction ohne cursor in der URL -> Start mit der aktuellsten Transaktion
//				 * - liefert transaktionen und nextCursor
//				 * - request transaktionen mit cursor
//				 * - irgendwann wird pendingRequests==true in den metadaten der response -> bedeutet da sind noch transactions die nicht geliefert werden
//				 *   (90 Tage grenze)
//				 * - request transactions mit inlcudeAllrecords=true -> das versendet ne SMS mit der TAN für verifikation
//				 * - liefert eine otpId und progressId
//				 * - POST auf verification mit otpId und TAN-Eingabe
//				 * - Wichtig jetzt
//				 * - request transactions mit letztem cursor UND progressId
//				 * - request transactions ohne cursor und ohne progressId -> wir müssen von vorne anfangen
//				 * - jetzt wie zu beginn mit cursor
//				 * - irgendwann kommt kein neuer cursor in der Antwort zurück -> Ende der Transaktionen erreicht
//				 * 
//				 * Wird die Sequenz nach der verification nicht eingehalten gibts entweder zwei SMS oder aber 403-error
//				 * 
//				 * - Dadurch werden die ersten transaktionen alle doppelt empfangen - es werden also alle bisherigen wieder gelöscht und neu
//				 *   in neueUmsaetze hinzugefügt
//				 * - Prüfung der Umsätze auf die cardId, die zu Beginn beim Vorspiel zum Saldo geholt wurde (Prüfung auf letzten 4 Stellen der Karte)
//				 *   -> so kann man mehrere Konten mit versch. Unterkonto-Daten für jede Karte einrichten
//				 */
//				var neueUmsaetze = new ArrayList<Umsatz>();
//				
//				// we need a final variables for lambda functions, so for fast create arrays with only single entry
//				
//				double calculatedSaldo[] = { konto.getSaldo() };
//				ArrayList<Umsatz> duplicatesRxNotBooked = new ArrayList<Umsatz>();
//				boolean duplicateRxFound[] = { false };
//				// within response the next cursor is set; first call without next cursor starts with first record
//				String transactionNextCursor = "";
//				
//				SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
//				
//				boolean transactionHasPendingRecords = false;
//				int requestCounterAfterSCA = 0;
//				
//				log(Level.INFO, "Kontoauszug abrufen...");
//				
//				do {
//					// Perform first transactions request
//					var url = AMZ_ZINIA_TRANSACTIONS_URL + "?limit=25&epp=true";
//					// add the last known cursor info (not present on first call)
//					// skip it 2nd call after SCA of 'all requests'
//					if (!transactionNextCursor.isEmpty() && 
//							( (!scaDone[0]) || (scaDone[0] && (requestCounterAfterSCA!=1))) ) {
//						url = url + "&cursor=" + transactionNextCursor;
//					}
//					if (scaDone[0]) {
//						url = url + "&includeAllRecords=true";
//						
//						// Webapp removed progressId 2nd call after SCA
//						if (requestCounterAfterSCA==1) {
//							for (KeyValue<String, String> item : headers) {
//								if (item.getKey().equals("progressId")) {
//									headers.remove(item);
//									break;
//								}
//							};
//							
//							// Neubeginn sämtlicher Transaktionsanalyse
//							neueUmsaetze.clear();
//							calculatedSaldo[0] = konto.getSaldo();
//							// bei duplikaten beenden wir eh
//						}
//						
//						if (monitorComplete < 80) {
//							monitorComplete +=2;
//						};
//						monitor.setPercentComplete(monitorComplete);
//						
//					}
//					requestCounterAfterSCA++;
//					log(Level.DEBUG, "Request transactions: " + url);
//					response = doRequest(url, HttpMethod.GET, headers, "application/json", null);
//					
//					if (response.getHttpStatus() != 200) {
//						log(Level.DEBUG, "Response: " + response.getContent());
//						throw new ApplicationException("Abruf der Transaktionen fehlgeschlagen.");
//					}
//					
//					json = response.getJSONObject();
//					
//					//log(Level.DEBUG, "Transaction returned: \n " + json.toString());
//					
//					transactionHasPendingRecords = json.getJSONObject("metadata").getBoolean("hasPendingRecords");
//					if (!transactionHasPendingRecords) {
//						log(Level.DEBUG, "default response for getting transaction"); 
//						// next cursor may not be present if there are pending records
//						transactionNextCursor = json.getJSONObject("metadata").optString("nextCursor");
//						log(Level.DEBUG, "transactionNextCursor: " + transactionNextCursor); 
//					} else if ( !scaDone[0] ){
//						// special handling for OTP and further steps until next transactions got
//						
//						log(Level.DEBUG, "first time pending records resceived");
//						
//						scaDone[0] =  true;
//						
//						log(Level.INFO, "Benoetige zweiten Faktor fuer weitere Umsaetze...");
//						monitorComplete = 25;
//						monitor.setPercentComplete(monitorComplete);
//						
//						
//						// recall transactions request with "nextStep OTP...." and current cursor
//						url = AMZ_ZINIA_TRANSACTIONS_URL + "?limit=25&epp=true&cursor=" + transactionNextCursor + "&includeAllRecords=true";
//						response = doRequest(url, HttpMethod.GET, headers, "application/json", null);
//						if (response.getHttpStatus() != 403) {
//							log(Level.DEBUG, "Request 'includeAllRecords' failed - Response: " + response.getContent());
//							throw new ApplicationException("2FA-Abruf fuer 'alle Transaktionen' fehlgeschlagen.");
//						}
//						json = response.getJSONObject();
//						var otpId = json.getString("otpId");
//						var progressId = json.getString("progressId");
//						
//						var requestText = "Gib den Bestaetigungscode ein, den du per SMS erhalten hast (fuer 'Alle Transaktionen abrufen')";	// TBD evtl. versch. Wege !?
//
//						var sca = Application.getCallback().askUser(requestText, "Bestaetigungscode:");
//						if (sca == null || sca.isBlank())
//						{
//							log(Level.WARN, "TAN-Eingabe 'Alle Transaktionen abrufen' abgebrochen");
//							break;
//						} else {
//							// call verify with OTP code
//							var progressIdHeaderEntry = new KeyValue<String, String>("progressId", progressId); 
//							headers.add(progressIdHeaderEntry);
//							url = AMZ_ZINIA_TRANSACTIONS_ALL_VERIFY_URL;
//							JSONObject verifyData = new JSONObject();
//							// body with optId and TAN input {`"id`":`"a6ct-Muv23zN4m8yQr9_SfGqXs4zFfFFQrUymziMc5A=`",`"value`":`"OUB0`"}"
//							verifyData.put("id", otpId);
//							verifyData.put("value", sca);
//							response = doRequest(url, HttpMethod.POST, headers, "application/json", verifyData.toString());
//							json = response.getJSONObject();
//							if ((response.getHttpStatus() != 201) || (json == null) || (!json.optString("nextChallengeType").equals("None"))) {
//								log(Level.DEBUG, "'All records' verification failed - Response: " + response.getContent());
//								log(Level.ERROR, "Abfrage abgebrochen - Starte Umsatzabfrage neu nach Eingabe zweiter Faktor");
//								throw new ApplicationException("2FA-Verfikation fuer 'alle Transaktionen' fehlgeschlagen.");
//							}
//		
//							// recall transactions request with current cursor (should return without pendingRecords)
//							requestCounterAfterSCA = 0;
//							continue;
//						}
//					} else {
//						// seems to be 2nd time entering verification for "all transactions" - abort and manual retry
//						log(Level.DEBUG, "2nd time pending records resceived - abort here for a new manual sync start");
//						
//						log(Level.ERROR, "Abfrage abgebrochen - Starte Umsatzabfrage neu");
//						throw new ApplicationException("Synchronisationsfehler fuer 'alle Transaktionen'.");
//					}
//					
//					var records = json.getJSONArray("elements");
//
//					log(Level.DEBUG, "Records in transaction response: " + records.length());
//					records.forEach( item -> {
//						// single record analysis
//						JSONObject sR = (JSONObject) item;  // cast to correct type
//						
//						try {
//							//log(Level.DEBUG,"trans: " + sR.toString());
//							var sRId = sR.getString("id");
//							var sRCardId = sR.getString("cardId");
//							if (!sRCardId.equals(cardId[0])) {
//								// skip this entry by return lamda-function
//								log(Level.TRACE,"Ueberspringe kartenfremde Transaktion " + sRCardId + " vs. " + cardId[0]);
//								return;
//							}
//							
//							// transaction belongs to card related to current konto
//							
//							var sRTransactionStatusCode = sR.optString("transactionStatusCode");
//							var sRDate = sR.getString("dateTime");	// Datum des Umsatzes
//							// assume: C: Credit (Income)  D: Debit (Outgoing)   notr present: Amazon-Punkte
//							String sRTransactionTypeCode = sR.optString("transactionTypeCode");
//							if (sRTransactionTypeCode.isEmpty()) {
//								// skip this entry by return lamda-function
//								log(Level.TRACE,"Ueberspringe reine Amazon-Punkte-Transaktion " + sR.toString());
//								return;
//							}
//							// Description prio:
//							// 1: merchantDetails (Anzeige in der App)
//							// 2: description (oft description länger mit Ortsangabe)
//							final String[] sRDescr = { sR.optString("description") };
//							try {
//								var sRMerchantDetails = sR.optJSONObject("merchantDetails");
//								var sRMDDescr = sRMerchantDetails.getString("name");
//								// if not empty use merchantDetails data
//								if (!sRMDDescr.isEmpty()) {
//									sRDescr[0] = sRMDDescr;
//								}
//							} catch (Exception e){
//								// intentionally this field may be optional and replaced description if exists
//							}
//							var sRAmounts = sR.optJSONArray("amounts");
//							var sRFirstAmount = sRAmounts.getJSONObject(0);
//							//sRAmounts.forEach(amountItem -> {
//							var sRAmount = sRFirstAmount.getDouble("totalAmount");
//							// assume: alle Umsätze in EUR abgerechnet -> nur fürs logging
//							var sRAmountCurr = sRFirstAmount.getString("currency");
//							log(Level.TRACE, "TransId: " + sRId + "\n   date: " + sRDate + "\n   descr: " + sRDescr[0] + "\n   Amount: " + sRAmount + sRAmountCurr +
//									"\n   TypeCode: " + sRTransactionTypeCode + "\n   StatusCode: " + sRTransactionStatusCode);
//							
//							var newUmsatz = (Umsatz) Settings.getDBService().createObject(Umsatz.class,null);
//							newUmsatz.setKonto(konto);
//							newUmsatz.setTransactionId(sRId);
//							newUmsatz.setBetrag(sRAmount);
//							
//							if (sRTransactionStatusCode.equals("AUTHORIZED")) {
//								// transaction still not 
//								newUmsatz.setFlags(Umsatz.FLAG_NOTBOOKED);
//								// excluded from saldo setting
//							} else {
//								newUmsatz.setSaldo(calculatedSaldo[0]);
//								calculatedSaldo[0] -= sRAmount;
//								
//							}
//							
//							// Hier besteht Unklarheit über die Codes
//							if (sRTransactionTypeCode.equals("C")) {
//								newUmsatz.setArt("Kontoausgleich");
//							} else if (sRTransactionTypeCode.equals("D")) {
//								newUmsatz.setArt("Debit");
//							}
//							
//							newUmsatz.setDatum(dateFormat.parse(sRDate));
//							var sRDateValuta = sR.optString("settlementDate");	// Sattlement >= date -> Valuta
//							if (!sRDateValuta.isEmpty()) {
//								// sometimes no sattlement date is given
//								Date d1 = newUmsatz.getDatum();
//								//Date d2 = dateFormatValuta.parse(sRDateValuta);
//								Date d2 = dateFormat.parse(sRDateValuta);
//								if ((d1.getYear() != d2.getYear()) || (d1.getMonth() != d2.getMonth()) || (d1.getDate() != d2.getDate())) {
//									newUmsatz.setValuta(d2);
//								} else {
//									newUmsatz.setValuta(newUmsatz.getDatum());
//								}
//							} else {
//								newUmsatz.setValuta(newUmsatz.getDatum());
//							}
//							newUmsatz.setZweck(sRDescr[0]);
//							
//							try {
//								newUmsatz.setCustomerRef(sR.optJSONObject("merchantDetails").optString("id"));
//							} catch (Exception e) {}
//							//newUmsatz.setMandateId()
//							//newUmsatz.setCreditorId()
//							
//							
//							
//							var duplicate = getDuplicateById(newUmsatz); 
//							if (duplicate != null)
//							{	
//								log(Level.DEBUG,"duplicate gefunden");
//								duplicateRxFound[0] = true;
//								if (duplicate.hasFlag(Umsatz.FLAG_NOTBOOKED))
//								{
//									// compare by datum, id, betrag -> daher kein update
//									duplicate.setFlags(newUmsatz.getFlags());
//									duplicate.setSaldo(newUmsatz.getSaldo());
//									duplicate.setWeitereVerwendungszwecke(newUmsatz.getWeitereVerwendungszwecke());
//
//									duplicate.setGegenkontoBLZ(newUmsatz.getGegenkontoBLZ());
//									duplicate.setGegenkontoName(newUmsatz.getGegenkontoName());
//									duplicate.setGegenkontoNummer(newUmsatz.getGegenkontoNummer());
//									duplicate.setValuta(newUmsatz.getValuta());
//									duplicate.setArt(newUmsatz.getArt());
//									duplicate.setCreditorId(newUmsatz.getCreditorId());
//									duplicate.setMandateId(newUmsatz.getMandateId());
//									duplicate.setCustomerRef(newUmsatz.getCustomerRef());
//									duplicate.store();
//									duplicatesRxNotBooked.add(duplicate);
//									Application.getMessagingFactory().sendMessage(new ObjectChangedMessage(duplicate));
//								}
//							}
//							else
//							{
//								neueUmsaetze.add(newUmsatz);
//							}
//							
//						}
//						catch (Exception ex)
//						{
//							log(Level.ERROR, "Fehler beim Anlegen vom Umsatz: " + ex.toString());
//						}
//
//					}); // forEarch records
//					
//				
//				} while ( ( !duplicateRxFound[0] ) && 
//						  ( !transactionNextCursor.isBlank() || transactionHasPendingRecords ) 
//						);
//				
//				monitorComplete = 90;
//				monitor.setPercentComplete(monitorComplete);
//				log(Level.INFO, "Kontoauszug erfolgreich. Importiere Daten ...");
//
//				reverseImport(neueUmsaetze);
//				
//				
//				monitorComplete = 95;
//				monitor.setPercentComplete(monitorComplete);
//				log(Level.INFO, "Import erfolgreich. Pr\u00FCfe Reservierungen ...");
//				
//				umsaetze.begin();
//				while (umsaetze.hasNext())
//				{
//					Umsatz umsatz = umsaetze.next();
//					if (umsatz.hasFlag(Umsatz.FLAG_NOTBOOKED))
//					{
//						if (!duplicatesRxNotBooked.contains(umsatz))
//						{
//							var id = umsatz.getID();
//							umsatz.delete();
//							Application.getMessagingFactory().sendMessage(new ObjectDeletedMessage(umsatz, id));
//						}
//					}
//				}
//				
//			} // if (fetchUmsatz)
//			
//		} // overall try/catch from starting after login-state
//		finally
//		{
//			// Logout
//			try 
//			{
//				doRequest(AMZ_ZINIA_LOGOUT_URL, HttpMethod.POST, headers, "application/json", "{\"accessToken\":\""+ accessToken + "\"}");
//			}
//			catch (Exception e) {}
//		}	
		return true;
	}
}
