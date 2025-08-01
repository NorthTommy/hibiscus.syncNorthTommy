package de.northtommy.hibiscus.syncNorthTommy.traderepublic;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.json.JSONArray;
import org.json.JSONObject;

import de.willuhn.logging.Level;

@WebSocket
public class TraderepublicWebSocket {
	
	public class Transaction {
		public JSONObject transaction = null;
		public JSONObject details = null;
	}
	
	protected TraderepublicSynchronizeJobKontoauszugI syncJob = null;
	
	private enum ProtoRequestStates{
		SUBSCRIBED,
		ANSWERED,
		// COMPLETED not needed as we simply send unsub and delete it from pending ids
		
	}
	
	private String clientVersion;
	
	//public void setClientVersion(String clientVersion) {
	//	this.clientVersion = clientVersion;
	//}

	private Date rxDateRangeUntil = null;
	
	/** set Date until the transactions should roughly be requested; null for all available transactions */
	//public void setRxDateRangerUntil(Date value) {
	//	this.rxDateRangeUntil = value;
	//}
	
	// will be set if any error occurred during sync
	private Exception errorException = null;
	
	public Exception getErrorException() {
		return this.errorException;
	}
	
	/**
	 * JSON object with available cash data for the accounts
	 * contains cashaccountnumber we can differ between
	 */
	private JSONArray accountAvailCash = null;
	/**
	 * ordered list of transactions with transaction and detail data
	 */
	private ArrayList<Transaction> accountTransactions = new ArrayList<Transaction>(); 
	
	private final CountDownLatch closeLatch = new CountDownLatch(1);
    private Session session;
    
    // first we have to connect and after that we can request data and subscribe to data
    private boolean protoConnected = false;
    /**
     * forward unique running counter for all requests of the websocket connection
     */
    private int protoCounter = 1;
    /**
     * key: protoCounter for transactionRequests
     * value: state
     * If request send, we set to subscribed
     * If key received and "A" and state is "SUBSCRIBED then we set we
     *    - add all transactions to accountTransitions
     *    - request details for each transition and add an entry for each detailRequest in protoTransitionSubscriptions
     *    - set ANSWERED as value for this key
     * If key received with "C" and state is ANSWERED we
     *    - send unsub key
     *    - delete it from this list  
     */
    private HashMap<Integer, ProtoRequestStates> protoTransactionSubscriptions = new HashMap<Integer, TraderepublicWebSocket.ProtoRequestStates>();
    
    /**
     * key: protoCounter
     * value: transaction initially set with transaction data only also stored in accountTransactions
     * 
     * If key again received we set details data as well if still null (initial)
     * If key again received we get the "Complete" and unsubscribe and delete it from this hashmap (all data stored in accountTransitions
     */
    private HashMap<Integer, Transaction> protoRequestDetailSubscriptions = new HashMap<Integer, Transaction>();
    
    private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
    
    public TraderepublicWebSocket(TraderepublicSynchronizeJobKontoauszugI syncJob, String version, Date rxDateRangeUntil) {
		if (syncJob == null) {
			throw new NullPointerException("syncJob must not be null");
		}
    	this.syncJob = syncJob;
    	this.clientVersion = version;
    	this.rxDateRangeUntil = rxDateRangeUntil;
	}
    
    public boolean awaitClose(int duration, TimeUnit unit) throws InterruptedException {
        return closeLatch.await(duration, unit);
    }

    @OnWebSocketConnect
    public void onConnect(Session session) {
        syncJob.logging(Level.DEBUG, "WebSocket connected"); 
        this.session = session;
        try {
        	String s = "connect 31  {\"locale\":\"en\",\"platformId\":\"webtrading\",\"platformVersion\":\"firefox - 140.0.0\",\"clientId\":\"app.traderepublic.com\",\"clientVersion\":\"" + this.clientVersion + "\"}";
    		syncJob.logging(Level.DEBUG, "WebSocket request subproto connect: " + s);
			session.getRemote().sendString(s);
		} catch (IOException e) {
			syncJob.logging(Level.ERROR, "WebSocket error subproto connect request: " + e.getMessage());
    		this.errorException = e;
		}
    }

    @OnWebSocketMessage
    public void onMessage(String msg) {
    	int sPC;
    	syncJob.logging(Level.DEBUG, "WebSocket Received message: " + msg);
    	//var msgId = Integer.parseInt(msg.substring(0, msg.indexOf(" ")));
    	//protoCounter = msgId + 1;
        try {
	        if ((msg.compareToIgnoreCase("connected") == 0) && (!this.protoConnected)) {
	        	syncJob.logging(Level.DEBUG, "WebSocket subproto connected:");
	        	this.protoConnected = true;
	        	
	        	// send initial transactions request and cash request
	        	sPC = protoCounter++;
	        	String s = "sub " + sPC + " {\"type\":\"availableCash\"}";
// TODO remember sPC for response
	        	syncJob.logging(Level.DEBUG, "WebSocket request chash info: " + s);
	        	this.session.getRemote().sendString(s);

	        	sPC = protoCounter++;
	    		protoTransactionSubscriptions.put(sPC, ProtoRequestStates.SUBSCRIBED);
	    		s = "sub " + sPC + " {\"type\":\"timelineTransactions\"}";
	    		syncJob.logging(Level.DEBUG, "WebSocket request first transactions: " + s);
	        	this.session.getRemote().sendString(s);
	        } else if (protoConnected) {
	        	// check for all expected responses for outstanding requests
	    		var msgId = Integer.parseInt(msg.substring(0, msg.indexOf(" ")));
	    		// check if its response for transaction request
	    		var value = protoTransactionSubscriptions.get(msgId); 
	    		if ((value != null)) {
	    			syncJob.logging(Level.DEBUG, "WebSocket transaction request msg response");
	    			switch(value) {
	    				case SUBSCRIBED:
	    					// if "id A JSON" -> analyse array 
	    					Date lastTransactionDate = null;
	    					if (msg.charAt(msg.indexOf(" ") + 1) == 'A') {
	    						JSONObject json = new JSONObject(msg.substring(msg.indexOf(" ") + 3));
	    						JSONArray jsonTransactions = json.getJSONArray("items");
	    						JSONObject jsonCursors = json.getJSONObject("cursors");
	    						
	    						for (int i = 0; i < jsonTransactions.length(); i++) {
	    							var jsonTransaction = jsonTransactions.getJSONObject(i);
	    							var tr = new Transaction();
	        						tr.transaction = jsonTransaction;
	        						tr.details = null;
	        						accountTransactions.add(tr);
	        						sPC = protoCounter++;
	        						protoRequestDetailSubscriptions.put(sPC, tr);
	        						String s = "sub " + sPC + " {\"type\":\"timelineDetailV2\", \"id\":\"" + jsonTransaction.getString("id") + "\"}";
	        			    		syncJob.logging(Level.DEBUG, "WebSocket request transactions details: " + s);
	        			        	this.session.getRemote().sendString(s);
	        			        	
	        			        	// remember date of the transaction
	        						lastTransactionDate = dateFormat.parse(jsonTransaction.getString("timestamp"));
	    						}
	    						
	    						// check cursors and if we need to request further transactions
	    						var afterCursor = jsonCursors.optString("after");
	    						if ( (! afterCursor.isBlank()) && ( (this.rxDateRangeUntil == null) || (lastTransactionDate.after(this.rxDateRangeUntil))) ) {
	    							protoTransactionSubscriptions.put(Integer.valueOf(protoCounter), ProtoRequestStates.SUBSCRIBED);
	    							String s = "sub " + this.protoCounter++ + " {\"type\":\"timelineTransactions\", \"after\":\"" + afterCursor + "\"}";
									syncJob.logging(Level.DEBUG, "WebSocket request further transactions: " + s);
	    							this.session.getRemote().sendString(s);
	    						}
	    						
	    					}
	    					
	    					protoTransactionSubscriptions.put(msgId, ProtoRequestStates.ANSWERED);
	    					break;
	    				case ANSWERED:
	    					// if // C -> unsub and remove from subscriptionArray
	    					String s = "unsub " + msgId;
	    		    		syncJob.logging(Level.DEBUG, "WebSocket transaction request finished: " + s);
	    					session.getRemote().sendString(s);
	    					protoTransactionSubscriptions.remove(msgId);
	    					break;
						default:
							// not an answer for transaction request
							break;
	    			}
	    		} else {
	    			// check if its response for transaction detail request
	    		}
	        			
	        	
	        }
        } catch (Exception e) {
    		syncJob.logging(Level.ERROR, "WebSocket error parsing rx message: " + e.getMessage());
    		this.errorException = e;
    	}
        
    }

    @OnWebSocketClose
    public void onClose(int statusCode, String reason) {
        System.out.println("Connection closed: " + reason);
        syncJob.logging(Level.DEBUG, "WebSocket connection closed");
        closeLatch.countDown();
    }

    @OnWebSocketError
    public void onError(Throwable cause) {
        System.err.println("WebSocket error: " + cause.getMessage());
        this.syncJob.logging(Level.ERROR, "WebSocket connection error: " + cause.getMessage());
        this.errorException = new Exception(cause);
    }
}
