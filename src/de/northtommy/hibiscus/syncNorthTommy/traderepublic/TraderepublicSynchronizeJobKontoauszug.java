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

public class TraderepublicSynchronizeJobKontoauszug extends SyncNTSynchronizeJobKontoauszug implements SyncNTSynchronizeJob , TraderepublicSynchronizeJobKontoauszugI
{

	
	
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
        TraderepublicWebSocket socket = new TraderepublicWebSocket(this, "3.296.0", null /*TODO*/);

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
		
			
		return true;
	}

	@Override
	public void logging(Level level, String msg) {
		log(level, msg);
		//System.out.println("["+level+"] " + msg);
	}
	
	@Override
	public void incrementPercentComplete(int arg0) {
		monitor.setPercentComplete(arg0);
	}
}
